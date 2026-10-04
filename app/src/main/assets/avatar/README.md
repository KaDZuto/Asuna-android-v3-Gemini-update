# Avatar assets (Live2D Asuna)

WebView-аватар Asuna VectorHeart. Использует **ровно тот же стек**, что и оригинальная десктоп-версия:
- `pixi.min.js` — рендерер PIXI.js
- `live2d.min.js` — обёртка `pixi-live2d-display` для загрузки `.moc`
- `cubism2.min.js` — Cubism 2 Core API (без `.wasm`, чистый JS)
- `motion-engine.js` — процедурный движок анимаций поверх Cubism 2 параметров
- `gestures.js` — библиотека семантических жестов
- `app.js` — главный модуль, скопирован **as-is** из исходного Asuna VectorHeart

## Структура

```
avatar/
  ├ index.html         ← WebView загружает это
  ├ app.js             ← главный модуль (оригинальный из Electron-версии)
  ├ motion-engine.js
  ├ gestures.js
  ├ asuna-shim.js      ← window.vh ↔ window.Asuna bridge (для Android-моста)
  ├ style.css
  ├ sao-theme.css
  ├ lib/
  │   ├ pixi.min.js
  │   ├ live2d.min.js
  │   └ cubism2.min.js
  └ models/
      ├ asuna_01/   (moc + текстуры + expressions + motions)
      ├ asuna_02/
      └ asuna_03/
```

## Как попадает в APK

Gradle-таска `copyAvatarAssets` (в `app/build.gradle.kts`) **копирует** файлы из
`C:\Users\Alexius\Downloads\Asuna_Motion_VAD_final (2)\repo\resources\libs\`
и `...\resources\models\asuna_XX\` в `app/src/main/assets/avatar/` при каждой сборке.

Если исходник переехал — поменяй путь в `build.gradle.kts` (список `possibleSources`).

## Что можно делать

- ✅ Загружать любую из 3 моделей asuna_01/02/03
- ✅ Анимации (idle, I_FUN, I_SAD, ...) — нативно из `.mtn` файлов
- ✅ Выражения лица (F_FUN, F_SAD, F_SURPRISE, F_SLEEP, ...) — из `.exp.json`
- ✅ Липсинк от TTS (через `window.Asuna.setMouthLevel(0..1)`)
- ✅ Жесты (wave, nod, bow, ...) — через `buildGestureSpec(name, opts, capabilities)`
- ✅ Морфинг (head_x, head_y, body_angle, eye_open, mouth_open, ...)
- ✅ `applyAction({...})` от LLM через `<live2d>...</live2d>` JSON

## Что НЕ работает (намеренно)

- ❌ Плеер (`player.html`) — отдельное окно для музыки. В мобиле будет bottom-sheet с ExoPlayer.
- ❌ Внешний плеер в отдельном окне (`window.open('player.html', ...)`) — WebView блокирует popup'ы.
- ❌ `window.vh.dragWindow` — в мобиле нет окон, которые можно двигать.
- ❌ `window.vh.toggleAlwaysOnTop` — single-task activity, всегда поверх.
- ❌ `window.vh.hideWindow` / `window.vh.quit` — это Electron-функции, no-op в shim.
