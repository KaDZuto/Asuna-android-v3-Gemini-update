# Аудит репозитория Asuna-android-v3-Gemini-update — Шаг 1

Дата: 2026-10-04. Метод: статическое чтение кода (4064 строки Kotlin, JS-ассеты, Gradle, манифест, мост на Python) + сверка с актуальной документацией. **Сборка не запускалась.** Всё, что ниже помечено «проверить на устройстве/в сборке», — гипотезы по коду, а не подтверждённые падения.

Приоритеты: 🔴 ломает работу сейчас · 🟠 серьёзный риск · 🟡 улучшение.

---

## 1. Что ломает работу

| # | Находка | Где | Что делать | Кто |
|---|---|---|---|---|
| 🔴 1 | **`gemini-2.0-flash` отключён Google 1 июня 2026.** Это дефолт для провайдера Gemini, поэтому «Gemini Pro / AI Studio» сейчас вернёт ошибку модели. README тоже рекомендует эту модель и несуществующую `gemini-1.5-pro`. Актуальная замена в таблице Google — `gemini-3.8-flash` (выпущена 2 сентября 2026). | `LlmClient.kt:349`, `ChatScreen.kt:615`, `README*.md` | Заменить на `gemini-3.8-flash` | Claude (шаг 2) |
| 🔴 2 | **Дефолт Anthropic `claude-3-5-sonnet-20241022` устарел.** | `LlmClient.kt:308`, `ChatScreen.kt:633` | `claude-sonnet-5-5` | Claude (шаг 2) |
| 🔴 3 | **Мост `agy -p` почти наверняка зависнет или вернёт пусто.** У Antigravity CLI есть открытые баги: в неинтерактивном режиме (pipe/subprocess) `-p` теряет stdout или висит бесконечно; флаг `--model` описывают как ненадёжный. Мост запускает `subprocess.run(capture_output=True)` без `stdin=DEVNULL` и без разбора ошибок. | `server/antigravity_bridge.py:106-118` | Закрыть stdin, поставить `--print-timeout`, добавить откат через transcript. **Требует живой проверки на ПК с `agy`.** | Claude (шаг 4) + владелец (тест) |
| 🔴 4 | **`server/tts_server.py` отсутствует**, хотя README и режим Silero HTTP на него опираются. Дефолтный адрес — жёстко зашитый `192.168.1.100:8000`. | `README.md`, `TtsManager.kt:49`, `ChatScreen.kt:~181` | Написать сервер (шаг 4) | Claude |
| 🔴 5 | **`calendar_remove` удаляет всё, у чего название содержит переданную строку.** Если модель передаст «встреча», пропадут все такие события. | `ToolRegistry.kt:151` | Точное совпадение по id, по названию — только если оно одно | Claude (шаг 2) |
| 🔴 6 | **`calendar_list` игнорирует `days`** и показывает прошедшие события. | `ToolRegistry.kt:132-146` | Фильтр от сегодня до +N дней | Claude (шаг 2) |
| 🔴 7 | **`web_search` на DuckDuckGo Instant Answer** отвечает только на «энциклопедические» запросы; на обычные (погода, места, новости, русский текст) почти всегда пусто. Резервная ветка отдаёт модели сырой JSON (`RelatedTopics.toString().take(250)`). | `ToolRegistry.kt:164-190` | Разбирать `RelatedTopics`, добавить второй источник (Wikipedia API) | Claude (шаг 2) |
| 🔴 8 | **Теги протекают в чат и в озвучку.** Ответ обрезается по `max_tokens=800`, `<live2d>` остаётся незакрытым, и регулярка `<live2d>…</live2d>` его не снимает. То же для `<think>…</think>` у DeepSeek-R1/локальных моделей. Если `content` пуст, мысли модели (`reasoning_content`) отдаются как ответ. | `LlmClient.kt:80-96, 279-285`, `TtsEngine.kt:103-113` | Снимать и незакрытые теги, `<think>`, не использовать reasoning как ответ | Claude (шаг 2) |

## 2. Безопасность

| # | Находка | Где | Что делать |
|---|---|---|---|
| 🟠 1 | **WebView слишком открыт.** `allowUniversalAccessFromFileURLs`, `allowFileAccessFromFileURLs`, `allowFileAccess` = true, `MIXED_CONTENT_ALWAYS_ALLOW`, отладка WebView включена **в релизе**, `onPermissionRequest` выдаёт любые права без проверки origin, `onCreateWindow` открывает окна с доступом к файлам, нет `shouldOverrideUrlLoading`. Контент локальный (через `WebViewAssetLoader`), поэтому прямой эксплуатации не вижу, но JS-мост `Asuna` доступен любой загруженной странице. | `AvatarWebView.kt:119-132, 168-188` |
| 🟠 2 | **API-ключ лежит в открытом `SharedPreferences`**, а `allowBackup=true` при пустых правилах бэкапа → ключ, память Асуны и календарь уходят в облачный бэкап. `security-crypto` в зависимостях есть, но не используется. | `ChatScreen.kt:179-199`, `res/xml/*.xml` |
| 🟠 3 | **Мост слушает `0.0.0.0` без авторизации**, CORS `*`, однопоточный `HTTPServer`, нет лимита тела, `model` из запроса уходит в аргументы CLI без проверки. Любой в Wi-Fi (или веб-страница в браузере ПК) может расходовать вашу подписку. | `antigravity_bridge.py:35-38, 75-76, 106-109, 177-179` |
| 🟠 4 | **Ключ Gemini передаётся и в URL (`?key=`), и в заголовке.** Ключ в URL попадает в логи и тексты ошибок. Заголовка достаточно. | `LlmClient.kt:355, 385` |
| 🟡 5 | Cleartext HTTP включён глобально. Для LAN-мостов это осознанный компромисс, но ключи уходят по HTTP, если пользователь укажет `http://` для облачного провайдера. | `AndroidManifest.xml` |
| 🟡 6 | `BuildConfig.OPENROUTER_API_KEY` запекается в APK, если задан в `local.properties`. Такой APK нельзя раздавать. | `app/build.gradle.kts` |

## 3. Сборка и репозиторий

| # | Находка | Где |
|---|---|---|
| 🟠 1 | **Дубликат моделей Live2D ≈55 МБ.** `assets/avatar/models` и `assets/resources/models` идентичны (`diff -rq` пуст). `app.js` и `control.js` читают `../resources/models/…`, значит `avatar/models` — мёртвый груз в APK и в репо. | `assets/` |
| 🟠 2 | Задача `copyAvatarAssets` содержит пути `C:\Users\Alexius\…` и `../hermes_paperclip_agent`, пишет в `avatar/models` и воссоздаст дубликат. Ассеты уже закоммичены, задача не нужна. | `app/build.gradle.kts:20-80` |
| 🟠 3 | **JDK: в шине и README написано 17, а `gradle-daemon-jvm.properties` требует 25** (`toolchainVersion=25`, скачивание через `api.foojay.io`). Сборочному окружению нужен доступ к foojay либо готовый JDK 25. | `gradle/gradle-daemon-jvm.properties` |
| 🟠 4 | **Версии не проверены:** AGP 9.3.2, Gradle 9.5.0, Kotlin 2.0.21 + KSP 2.0.21-1.0.28 вместе с AGP 9 (встроенный Kotlin), Hilt 2.59, webkit 1.17.0, google-api-client-android 2.9.0, vosk 0.3.45. Выяснится только сборкой. | `gradle/libs.versions.toml` |
| 🟡 5 | Манифест без `<queries>` для `TTS_SERVICE`: на Android 11+ из-за видимости пакетов TTS-движки могут не находиться (особенно на Xiaomi). | `AndroidManifest.xml` |
| 🟡 6 | `AsunaCompanionService` объявлен как foreground-сервис (microphone), но не вызывает `startForeground`, и нигде не запускается. Если его когда-нибудь запустят через `startForegroundService`, будет краш через ~5 с. `BootReceiver`, `ReminderReceiver`, `CalendarSyncService`, `SttManager` — заглушки с `TODO`. | `service/`, `calendar/`, `stt/` |
| 🟡 7 | Жёстко зашит IP `192.168.1.100` в трёх местах. | `TtsManager.kt:49`, `ChatScreen.kt:~181, 623` |
| 🟡 8 | `HTTP-Referer` для OpenRouter указывает на старый репозиторий `Asuna_Brand_New_Angent_v2`. | `LlmClient.kt:232` |
| 🟡 9 | `proguard-rules.pro` указан в Gradle, файла нет (безвредно при `minify=false`, упадёт при включении). | `app/build.gradle.kts` |
| 🟡 10 | Смесь CRLF/LF между файлами, нет `.gitattributes` → шумные диффы. | весь репо |
| 🟡 11 | README описывает `AIgametest/`, `AsunaTesting/`, корневой `antigravity_bridge.py`; фактически `app/`, `server/`. Устарел. | `README*.md` |
| 🟡 12 | Тестов нет (`app/src/test` отсутствует). | — |

## 4. Заметки по продукту (для редизайна)

- `ChatScreen.kt` — монолит на 1394 строки: чат, настройки, провайдеры, локальные модели, аватар, TTS/STT. `AsunaApp` показывает только его, навигации нет; строки `nav_*` в `strings.xml` не используются.
- OpenRouter-дефолт `google/gemini-2.0-flash-exp:free` почти наверняка тоже мёртв, но актуальный бесплатный идентификатор я не проверял → нужна проверка на живом OpenRouter.
- Имена `*-high` у моста (`gemini-3.8-flash-high`) — это суффиксы Antigravity CLI. Сама `gemini-3.8-flash` в Gemini API существует. Какие именно строки принимает `agy --model`, я не подтвердил (см. 🔴 3).
- JS-мост `Asuna.init()` отдаёт одну модель `asuna_01`, хотя в `models_manifest.json` их ~50.

## 5. План дальнейших шагов (без сборки)

1. ✅ Аудит и запись в шину.
2. Исправления ядра: `LlmClient`, `ToolRegistry`, дефолтные модели, чистка тегов.
3. Безопасность и конфиг: WebView, манифест, правила бэкапа, ключ из бэкапа, удаление дубликата моделей и мёртвой Gradle-задачи, `.gitattributes`.
4. Мост Antigravity (усиление) и новый `server/tts_server.py`.
5. Редизайн интерфейса: разбиение `ChatScreen`, экран настроек, обои.
6. Юнит-тесты на чистую логику, актуализация README и шины.

Всё, что требует сборки или телефона (компиляция, версии зависимостей, ключи, живые API, тест Live2D на Mi 11T), остаётся build-инженеру и владельцу и фиксируется в шине.

## Источники

- Таблица устаревания моделей Gemini API (обновлена 2026-10-01): https://ai.google.dev/gemini-api/docs/deprecations
- Баги `agy -p` в неинтерактивном режиме: https://github.com/google-antigravity/antigravity-cli/issues/76 и https://github.com/google-antigravity/antigravity-cli/issues/318
