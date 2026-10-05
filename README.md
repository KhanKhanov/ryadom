# Рядом

Бесплатный сервис видеопомощи незрячим. Незрячий нажимает кнопку в Android-приложении, волонтёр отвечает по видео — с Android или из браузера.

> «Рядом» — рабочее название.

- Архитектура и этапы разработки: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- Контракт API: [docs/api/openapi.yaml](docs/api/openapi.yaml)
- Правила работы над кодом: [CLAUDE.md](CLAUDE.md)

## Структура

| Папка | Что внутри |
|---|---|
| `backend/` | Сервер на Kotlin + Ktor |
| `shared/` | Kotlin Multiplatform: модели API, клиент API и WebSocket, вход, состояния запроса и звонка — общее для Android, iOS и сервера |
| `android/app` | Android-приложение (Jetpack Compose): вход, выбор роли, push FCM, сборка модулей вместе |
| `android/feature-help` | Экраны незрячего: большая кнопка, поиск, звонок, оценка |
| `android/feature-volunteer` | Экраны волонтёра: «Готов помогать», входящий вызов (в приложении и уведомлением на весь экран), звонок с видео незрячего, оценка |
| `android/feature-call` | Видеозвонок на LiveKit и foreground service на время поиска и звонка |
| `android/core-ui` | Тема и доступные компоненты, общие для всех экранов |
| `android/testing` | Проверки доступности для тестов (`assertScreenIsAccessible()`) |
| `web/` | Кабинет волонтёра и админка (React + TypeScript, Vite), Service Worker для push-уведомлений, сквозной тест звонка (`web/e2e`, Playwright) |
| `infra/` | `docker-compose.yml`, конфиг LiveKit |
| `docs/` | Архитектура, API, чек-листы |

## Что нужно для разработки

- JDK 17 или новее, чтобы запустить Gradle. Подойдёт JDK из Android Studio: укажите переменную `JAVA_HOME` на папку `jbr` внутри Android Studio (Windows: `C:\Program Files\Android\Android Studio\jbr`). JDK 21 для сборки проекта Gradle скачает сам.
- Android SDK (ставится вместе с Android Studio) — для модулей `android` и `shared`
- Node.js 24+ — для `web`
- Docker — для локального окружения и тестов backend

## Локальный запуск

1. Окружение (PostgreSQL, LiveKit):

   ```bash
   cp infra/.env.example infra/.env   # заполните пустые значения
   docker compose -f infra/docker-compose.yml up
   ```

2. Сервер (порт 8080, проверка: <http://localhost:8080/health>):

   ```bash
   ./gradlew :backend:run
   ```

   Сервер берёт настройки из того же `infra/.env` (нужны `POSTGRES_PASSWORD`, `JWT_SECRET`, `LIVEKIT_API_KEY` и `LIVEKIT_API_SECRET`) и при старте сам применяет миграции базы.
   Все настройки и их переменные окружения — в `backend/src/main/resources/application.conf`, в том числе параметры подбора волонтёров (размер волн, интервалы, время поиска).

   LiveKit из docker-compose сообщает backend о начале и конце звонка (webhook на `http://host.docker.internal:8080/webhooks/livekit`), поэтому backend должен работать на этом же компьютере на порту 8080.

   Войти без Яндекс ID (работает при `AUTH_DEV_ENABLED=true`) — ответ содержит `accessToken` для заголовка `Authorization: Bearer`:

   ```bash
   curl -X POST http://localhost:8080/auth/dev -H "Content-Type: application/json" -d '{"login":"volunteer-1"}'
   ```

   Вход через Яндекс ID включается переменными `YANDEX_CLIENT_ID` и `YANDEX_CLIENT_SECRET` (приложение регистрируется на <https://oauth.yandex.ru>).

   Как устроен звонок со стороны сервера (полностью — в `docs/api/openapi.yaml`):
   1. Волонтёр (роль выбирается через `PATCH /me`) держит открытым WebSocket `ws://localhost:8080/ws` и первым сообщением отправляет `{"type":"auth","accessToken":"…"}`.
   2. Незрячий вызывает `POST /requests` с телом `{}` — волонтёру приходит событие `request.incoming`.
   3. Волонтёр вызывает `POST /requests/{id}/accept` и получает адрес и токен LiveKit; незрячему приходит `request.accepted` со своим токеном.
   4. Любой участник завершает звонок вызовом `DELETE /requests/{id}`, оба получают `request.ended`. Исключение: если незрячий отменил запрос, пока в комнату звонка никто не вошёл, запрос становится `cancelled`, а волонтёр получает `request.cancelled`.

   Вызов получают волонтёры с открытым WebSocket и с устройствами, зарегистрированными для push-уведомлений (`POST /devices`). Каналы push включаются переменными окружения в `infra/.env` (все пустые — push выключен):

   - Web Push (уведомления браузера): создайте пару ключей и добавьте выведенные строки в `infra/.env` вместе с `WEB_PUSH_SUBJECT` — вашим адресом в виде `mailto:you@example.com` (по нему push-сервисы браузеров свяжутся с владельцем сервера):

     ```bash
     ./gradlew :backend:generateWebPushKeys
     ```

   - FCM (вызовы волонтёру в Android-приложении на телефоне с сервисами Google): `FCM_SERVICE_ACCOUNT_FILE` — полный путь к файлу сервисного аккаунта Firebase (консоль Firebase, настройки проекта, сервисные аккаунты). Файл — секрет, в репозиторий его не добавляйте. Приложению нужны настройки того же проекта Firebase — шаг 4.
   - RuStore Push (Android без сервисов Google): `RUSTORE_PROJECT_ID` и `RUSTORE_SERVICE_TOKEN` из консоли RuStore. Сервер его уже умеет, а приложение пока регистрирует только FCM, поэтому канал можно не включать (`docs/ARCHITECTURE.md`, раздел 7).

   При старте сервер пишет в лог, какие каналы включены: `Push channels: [WEB_PUSH]`.

3. Веб — кабинет волонтёра (<http://localhost:5173>):

   ```bash
   cd web && npm install && npm run dev
   ```

   Сайт обращается к API по адресу `/api/...`, а Vite пересылает эти запросы на backend `http://localhost:8080`, поэтому backend должен быть запущен.

   В режиме разработки есть вход без Яндекс ID (по логину, например `volunteer-1`) и страница «тестовый незрячий»: она создаёт запрос помощи и показывает камеру компьютера. Так звонок проверяется без Android-приложения:
   1. Войдите как `volunteer-1` и нажмите «Стать волонтёром». Если сейчас время тишины (по умолчанию 22:00–08:00), вызовы не придут — отключите его в кабинете.
   2. В окне инкогнито или другом браузере (все обычные окна браузера делят сохранённый вход) откройте тот же адрес, войдите как `blind-1`, нажмите «Стать тестовым незрячим» и «Попросить помощи».
   3. В кабинете волонтёра появится вызов со звуком — примите его. Звук браузер включает только после нажатия на странице: если после загрузки вы ничего не нажимали, вызов придёт беззвучно — нажмите «Проверить звук».

   Уведомления о вызовах при закрытой вкладке (Web Push): включите Web Push на сервере (шаг 2) и нажмите «Включить уведомления» в кабинете волонтёра. На `localhost` это работает в Chrome, Edge и Firefox; доставляет уведомления push-сервис браузера (Google, Mozilla, Microsoft), поэтому компьютеру нужен интернет. Закрыть можно вкладку, но не сам браузер: на компьютере уведомления приходят, только пока браузер запущен. В Brave push-сервис по умолчанию выключен — включите «Use Google services for push messaging» в `brave://settings/privacy`. Встроенные браузеры сред разработки уведомления обычно запрещают — кабинет тогда объясняет, как их разрешить. На iPhone уведомления работают только у сайта по HTTPS, добавленного на экран «Домой», — проверить это можно только на сервере (этап 9).

   Service Worker (`web/src/push/serviceWorker.ts`) в режиме разработки собирается заново при каждом запросе `/sw.js`; новая версия начинает работать после перезагрузки страницы.

   Сайт по адресу компьютера в локальной сети (`http://192.168…`, например с телефона) войти через Яндекс, позвонить и включить уведомления не сможет: браузер даёт камеру, микрофон, криптографию для входа и push только по HTTPS или на `localhost`.

   Вход через Яндекс ID на сайте: скопируйте `web/.env.example` в `web/.env.local` и укажите `VITE_YANDEX_CLIENT_ID`; в настройках приложения на <https://oauth.yandex.ru> добавьте Callback URI `http://localhost:5173/`.

4. Android — приложение незрячего и волонтёра (<https://developer.android.com/studio/run/emulator>):

   Откройте корень репозитория в Android Studio, запустите эмулятор (или подключите телефон по USB с включённой отладкой) и пробросьте порты компьютера в устройство. Так приложение на эмуляторе или телефоне обращается к `localhost` компьютера: к backend (8080) и LiveKit (7880 — сигнализация, 7881 — видео и звук по TCP):

   ```bash
   adb reverse tcp:8080 tcp:8080 && adb reverse tcp:7880 tcp:7880 && adb reverse tcp:7881 tcp:7881
   ```

   Проброс сбрасывается при перезапуске эмулятора, переподключении телефона и после UI-тестов (`connectedDebugAndroidTest`) — выполните команду снова. Настраивать `LIVEKIT_NODE_IP` и `LIVEKIT_URL` в `infra/.env` для этого не нужно.

   Затем запустите конфигурацию `android.app`. Отладочная сборка входит без Яндекс ID — по логину (по умолчанию `blind-1`); роль выберите «Мне нужна помощь». Сквозной звонок: волонтёр в браузере (шаг 3) нажимает «Принять» и видит камеру телефона.

   Волонтёр на телефоне: войдите под другим логином (например, `volunteer-2`) и выберите «Я хочу помогать». Незрячего изображает «тестовый незрячий» сайта (шаг 3) в браузере на компьютере. Разрешите в кабинете микрофон и уведомления. Вызов звонит, пока приложение открыто; свёрнутое приложение получает вызов уведомлением, а на выключенном экране вызов открывается на весь экран — но без FCM только пока процесс приложения жив. Чтобы вызов приходил и в закрытое приложение, нужен проект Firebase (настройки ниже) и `FCM_SERVICE_ACCOUNT_FILE` на сервере (шаг 2).

   Настройки сборки — в `local.properties` в корне репозитория (файл не попадает в git):

   - `ryadom.apiUrl` — адрес backend, по умолчанию `http://localhost:8080`. Для телефона по Wi-Fi без USB укажите адрес компьютера в локальной сети (`http://192.168.1.10:8080`) и заполните `LIVEKIT_NODE_IP` и `LIVEKIT_URL` в `infra/.env` (см. `infra/.env.example`); брандмауэр компьютера должен пропускать эти порты.
   - `ryadom.yandexClientId` — client_id приложения в Яндекс ID (<https://oauth.yandex.ru>) для кнопки «Войти через Яндекс ID». Пусто — кнопки нет. Если у Android-приложения отдельная регистрация, добавьте её client_id в `YANDEX_EXTRA_CLIENT_IDS` на сервере.
   - `ryadom.firebase.appId`, `ryadom.firebase.apiKey`, `ryadom.firebase.projectId`, `ryadom.firebase.senderId` — проект Firebase для push о вызовах волонтёру (FCM). Значения — в консоли Firebase: «Настройки проекта → Общие → Ваши приложения», Android-приложение с пакетом `ru.ryadom` («ID приложения», «Ключ API», «ID проекта», «Номер проекта»). Файл `google-services.json` не нужен и в репозиторий не добавляется. Google Analytics при создании проекта выключите: для push она не нужна. Пусто — push в этой сборке нет, кабинет волонтёра об этом говорит.

## Тесты и линтеры

Тесты backend поднимают настоящий PostgreSQL в Docker (Testcontainers), поэтому Docker должен быть запущен.

```bash
./gradlew ktlintCheck :backend:test :shared:jvmTest :android:app:lintDebug testDebugUnitTest
cd web && npm run lint && npm test && npm run build
```

`testDebugUnitTest` без имени модуля запускает unit-тесты всех Android-модулей. `npm run build` заодно проверяет типы TypeScript — и сайта, и Service Worker (`web/tsconfig.sw.json`). Тесты web работают с поддельными сервером, WebSocket и звонком (`web/src/testing`), backend для них не нужен. Тесты `shared` тоже обходятся без сервера: поддельные HTTP (`MockEngine`) и WebSocket — в `shared/src/commonTest/.../testing`, поддельный звонок — в `BlindHelpControllerTest`.

Доступность Android проверяется в двух местах:

- в Robolectric-тесте каждого экрана вызывайте `assertScreenIsAccessible()` (`android/testing`) — тест упадёт, если у кнопки нет описания для TalkBack или она меньше 48 dp;
- UI-тесты на эмуляторе (`android/app/src/androidTest`) прогоняют каждый экран через Accessibility Test Framework в светлой и тёмной теме: контраст текста, размеры, подписи, повторяющиеся описания. Новый экран — добавьте его в `ScreensAccessibilityTest`. Запуск при включённом эмуляторе:

  ```bash
  ./gradlew :android:app:connectedDebugAndroidTest
  ```

  Запускайте не чаще раза в минуту: Gradle ещё несколько секунд после окончания прогона удаляет тестовое приложение с эмулятора и может удалить его посреди следующего прогона (тест падает с «Test failed with status -1»).

  Перед запуском отключите от компьютера телефон: Gradle запускает UI-тесты на всех подключённых устройствах, и на этапе 5 при подключённом телефоне он удалял приложение с эмулятора посреди прогона (тест падает без сообщения, в logcat — `deletePackageX`). Если телефон нужен, тесты можно запустить только на эмуляторе без Gradle: установите `app-debug.apk` и `app-debug-androidTest.apk` (`android/app/build/outputs/apk`) через `adb -s emulator-5554 install -r -t` и выполните `adb -s emulator-5554 shell am instrument -w ru.ryadom.test/androidx.test.runner.AndroidJUnitRunner`.

Голос TalkBack автоматически не проверить: перед сборкой для тестировщиков пройдите экраны с включённым TalkBack по чек-листу [docs/talkback-checklist.md](docs/talkback-checklist.md).

Тесты backend сверяют каждый ответ сервера с `docs/api/openapi.yaml` (`backend/src/test/.../testing/OpenApiContract.kt`): если тест падает с «не соответствует docs/api/openapi.yaml», поправьте спецификацию или ответ сервера.

### Сквозной тест

Звонок целиком в браузере (Playwright, `web/e2e`): «тестовый незрячий» просит помощи, волонтёр принимает вызов, видит камеру и завершает звонок. Камера и микрофон — поддельные, из Chrome. Нужны окружение и backend (шаги 1 и 2 «Локального запуска»); сайт тест собирает и запускает сам (`vite preview` на порту 4173).

```bash
cd web && npx playwright install chromium && npm run e2e
```

Если backend не запущен, тест сразу об этом скажет. Против уже запущенного сайта: `E2E_BASE_URL=http://localhost:5173 npm run e2e` (адрес backend — `E2E_BACKEND_URL`, по умолчанию `http://localhost:8080`). При падении — `npx playwright show-report`. В CI тест идёт отдельной задачей.

### Контракт API

Проверка контракта API (из корня репозитория; правила — в `redocly.yaml`):

```bash
REDOCLY_TELEMETRY=off npx --yes @redocly/cli@2.54.3 lint
```

`REDOCLY_TELEMETRY=off` отключает отправку статистики использования в Redocly.

Форматирование Kotlin: `./gradlew ktlintFormat`.

## Лицензия

[GNU AGPL-3.0](LICENSE) (версия 3 или любая более поздняя).

Кратко: код можно свободно использовать и менять, но изменённую версию нужно публиковать под той же лицензией — в том числе если она работает как сетевой сервис и не распространяется в виде программы.
