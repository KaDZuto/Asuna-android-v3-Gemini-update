package com.vectorheart.asuna.ui.avatar

import android.annotation.SuppressLint
import android.os.Message
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebViewAssetLoader
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Asuna JS-bridge — объект `window.Asuna` в WebView.
 */
class AsunaJsBridge(
    val onMouthLevel: (Float) -> Unit,
    val onAvatarReact: (String) -> Unit,
    val onSendVoice: (String) -> Unit,
    val onSendTouch: (String) -> Unit,
    val onOpenControl: () -> Unit,
    val onHideWindow: () -> Unit,
    val onQuit: () -> Unit
) {
    @JavascriptInterface
    fun init(): String {
        return """{"settings":{"alwaysOnTop":true,"currentModel":"asuna_01","models":[{"id":"asuna_01","expressions":0,"motions":0}]}}"""
    }
    @JavascriptInterface
    fun sendVoice(base64Pcm: String) { onSendVoice(base64Pcm) }
    @JavascriptInterface
    fun sendTouch(zone: String) { onSendTouch(zone) }
    @JavascriptInterface
    fun openControl() { onOpenControl() }
    @JavascriptInterface
    fun hideWindow() { onHideWindow() }
    @JavascriptInterface
    fun quit() { onQuit() }
    @JavascriptInterface
    fun playMusic(path: String) { }
    @JavascriptInterface
    fun stopMusic() { }
    @JavascriptInterface
    fun pauseMusic() { }
    @JavascriptInterface
    fun resumeMusic() { }
    @JavascriptInterface
    fun nextTrack() { }
    @JavascriptInterface
    fun prevTrack() { }
    @JavascriptInterface
    fun setVolume(volume: Float) { }
    @JavascriptInterface
    fun toggleAlwaysOnTop(value: Boolean) { }
}

/**
 * Touch passthrough. Возвращает true для нижней зоны (bottom bar),
 * false для остальной (WebView обрабатывает touch — драг/клик по аватару).
 *
 * Bottom bar — нижние 100dp экрана.
 */
private class TouchPassthroughListener(
    private val webView: WebView,
    private val bottomBarHeightPx: Int
) : View.OnTouchListener {
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        val h = (v as WebView).height
        val w = v.width
        if (w == 0 || h == 0) return false
        // Bottom bar занимает нижние bottomBarHeightPx
        if (event.y >= h - bottomBarHeightPx) {
            // НЕ потребляем — событие пройдёт наверх в Compose
            v.performClick()
            return false
        }
        // Иначе — WebView обрабатывает нормально (драг аватара и т.п.)
        return false
    }
}

/**
 * Compose-обёртка над WebView с Live2D-аватаром.
 *
 * Важные детали:
 *  - **Не пересоздаёт WebView** при recompose (factory создаёт один раз).
 *  - **Touch passthrough** для нижней зоны: в нижних 100dp WebView пропускает события,
 *    чтобы Compose-кнопки (chat log, settings, calendar, media, live vision) получали клики.
 *  - **Подробное логирование** всех событий загрузки.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AvatarWebView(
    bridge: AsunaJsBridge,
    modifier: Modifier = Modifier,
    onWebViewCreated: (WebView) -> Unit = {}
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val bottomBarHeightPx = with(density) { 100.dp.toPx().toInt() }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.apply {
                    javaScriptEnabled = true
                    allowFileAccess = false
                    allowContentAccess = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    allowFileAccessFromFileURLs = false
                    allowUniversalAccessFromFileURLs = false
                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }

                // Отладка WebView включается только в debug-сборке (FLAG_DEBUGGABLE)
                if (0 != (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)) {
                    WebView.setWebContentsDebuggingEnabled(true)
                }

                // Touch passthrough: в нижней зоне НЕ потребляем события —
                // они идут наверх к Compose overlay-кнопкам.
                setOnTouchListener(TouchPassthroughListener(this, bottomBarHeightPx))

                // Прозрачный фон WebView — чтобы пользовательский фон чата был виден
                setBackgroundColor(android.graphics.Color.TRANSPARENT)

                val assetLoader = WebViewAssetLoader.Builder()
                    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                    .build()

                webViewClient = object : android.webkit.WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        return assetLoader.shouldInterceptRequest(request.url)
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        val host = request.url.host
                        // Разрешаем навигацию только внутри WebViewAssetLoader (appassets.androidplatform.net)
                        val allowed = host == "appassets.androidplatform.net"
                        if (!allowed) {
                            Log.w(TAG, "Blocked navigation to: ${request.url}")
                        }
                        return !allowed
                    }

                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                        Log.d(TAG, "onPageStarted: " + url)
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        Log.d(TAG, "onPageFinished: " + url)
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: android.webkit.WebResourceError
                    ) {
                        Log.e(TAG, "onReceivedError: " + request.url + " code=" + error.errorCode + " desc=" + error.description)
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest) {
                        val origin = request.origin
                        // Выдаём права только контенту из локального asset-лоадера
                        if (origin != null && origin.toString().startsWith("https://appassets.androidplatform.net")) {
                            request.grant(request.resources)
                        } else {
                            Log.w(TAG, "Denied permission request from origin: $origin")
                            request.deny()
                        }
                    }

                    override fun onCreateWindow(
                        view: WebView,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: Message?
                    ): Boolean {
                        // Попапы/новые окна не поддерживаем — безопаснее не создавать
                        Log.w(TAG, "onCreateWindow blocked (dialog=$isDialog, gesture=$isUserGesture)")
                        return false
                    }

                    override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                        if (msg == null) return false
                        val priority = when (msg.messageLevel()) {
                            ConsoleMessage.MessageLevel.ERROR -> Log.ERROR
                            ConsoleMessage.MessageLevel.WARNING -> Log.WARN
                            ConsoleMessage.MessageLevel.LOG -> Log.INFO
                            ConsoleMessage.MessageLevel.DEBUG -> Log.DEBUG
                            else -> Log.INFO
                        }
                        Log.println(
                            priority,
                            "AsunaJS",
                            "[${msg.messageLevel()}] ${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})"
                        )
                        return true
                    }
                }

                addJavascriptInterface(bridge, "Asuna")
                loadUrl("https://appassets.androidplatform.net/assets/avatar/index.html")

                onWebViewCreated(this)
            }
        },
        update = { /* НЕ пересоздаём WebView при recompose */ }
    )
}

private const val TAG = "AsunaWebView"
