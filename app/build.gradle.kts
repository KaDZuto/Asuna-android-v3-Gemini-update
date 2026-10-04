plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

import java.util.Properties

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}
val openRouterApiKey: String = localProperties.getProperty("openrouter.api.key", "")

// `org.jetbrains.kotlin.android` подтягивается автоматически AGP 9.x + Kotlin 2.0+.
// Отдельный `kotlin-android` плагин НЕ нужен, иначе конфликт дублирующих расширений.

// ====================== Копирование ассетов ======================
// Копируем JS-библиотеки Live2D из исходного Asuna VectorHeart в assets/avatar/lib/.
// Это ТОЧНО ТОТ ЖЕ набор, что используется в рабочей Electron-версии:
//   - pixi.min.js       (PIXI.js v5 — рендерер)
//   - live2d.min.js     (PIXI-live2d-display — обёртка для загрузки .moc)
//   - cubism2.min.js    (Cubism 2 Core API, JS-only без .wasm)
//
// Также копируем Live2D-модели asuna_01/02/03 из ресурсов исходника.
val copyAvatarAssets = tasks.register("copyAvatarAssets") {
    group = "assets"
    description = "Copy Live2D libs and models from Asuna VectorHeart source if available"
    val taskRef = this
    doLast {
        val projectDir = taskRef.project.projectDir
        val assetsDir = File(projectDir, "src/main/assets")
        val libDir = File(assetsDir, "avatar/lib")
        val modelsDir = File(assetsDir, "avatar/models")
        libDir.mkdirs()
        modelsDir.mkdirs()

        // If avatar assets already exist, don't fail the build
        if (File(libDir, "pixi.min.js").exists() && File(libDir, "live2d.min.js").exists()) {
            logger.lifecycle("Live2D assets already present in assets/avatar/lib, skipping copy.")
            return@doLast
        }

        val possibleSources = listOf(
            "C:/Users/Alexius/Downloads/Asuna_Motion_VAD_final (2)/repo/resources/libs",
            "C:\\Users\\Alexius\\Downloads\\Asuna_Motion_VAD_final (2)\\repo\\resources\\libs",
            "../../hermes_paperclip_agent/resources/libs",
            "../hermes_paperclip_agent/resources/libs"
        )
        val sourceLibs = possibleSources
            .map { File(it) }
            .firstOrNull { it.exists() && it.isDirectory }

        if (sourceLibs == null) {
            logger.warn("Live2D libs source directory not found; using existing checked-in assets.")
            return@doLast
        }

        for (name in listOf("pixi.min.js", "live2d.min.js", "cubism2.min.js")) {
            val src = File(sourceLibs, name)
            val dst = File(libDir, name)
            if (src.exists()) {
                src.copyTo(dst, overwrite = true)
                logger.lifecycle("Copied $name (${src.length()} bytes)")
            } else {
                logger.warn("MISSING: $src")
            }
        }

        val sourceModels = File(sourceLibs.parentFile, "models")
        if (sourceModels.exists()) {
            for (modelId in listOf("asuna_01", "asuna_02", "asuna_03")) {
                val srcModel = File(sourceModels, modelId)
                if (srcModel.exists()) {
                    val dstModel = File(modelsDir, modelId)
                    if (dstModel.exists()) dstModel.deleteRecursively()
                    srcModel.copyRecursively(dstModel, overwrite = true)
                    logger.lifecycle("Copied model $modelId")
                } else {
                    logger.warn("MISSING MODEL: $srcModel")
                }
            }
        }
    }
}

tasks.named("preBuild").configure { dependsOn(copyAvatarAssets) }

android {
    namespace = "com.vectorheart.asuna"
    compileSdk {
        version = release(35)
    }

    defaultConfig {
        applicationId = "com.vectorheart.asuna"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        buildConfigField("String", "OPENROUTER_API_KEY", "\"${openRouterApiKey}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/INDEX.LIST",
                "/META-INF/io.netty.versions.properties",
                "/META-INF/DEPENDENCIES"
            )
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Kotlinx
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Networking
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)

    // DataStore + EncryptedSharedPreferences
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // WebView (WebViewAssetLoader для безопасной загрузки assets/avatar/)
    implementation(libs.androidx.webkit)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Media3 (ExoPlayer) для локальной аудиотеки
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // STT (Vosk + Android SpeechRecognizer fallback)
    implementation(libs.vosk.android)

    // Google Sign-In + Calendar
    implementation(libs.play.services.auth)
    implementation(libs.google.http.client)
    implementation(libs.google.api.services.calendar)

    // Permissions
    implementation(libs.accompanist.permissions)

    // Image loading
    implementation(libs.coil.compose)

    // Test
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
