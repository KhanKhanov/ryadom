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
| `shared/` | Kotlin Multiplatform: общая логика для Android, iOS и сервера |
| `android/` | Android-приложение (Jetpack Compose) |
| `web/` | Кабинет волонтёра и админка (React + TypeScript, Vite) |
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
   4. Любой участник завершает звонок вызовом `DELETE /requests/{id}`, оба получают `request.ended`.

   Пока push-уведомлений нет (этап 5), вызов получают только волонтёры с открытым WebSocket.

3. Веб — кабинет волонтёра (<http://localhost:5173>):

   ```bash
   cd web && npm install && npm run dev
   ```

   Сайт обращается к API по адресу `/api/...`, а Vite пересылает эти запросы на backend `http://localhost:8080`, поэтому backend должен быть запущен.

   В режиме разработки есть вход без Яндекс ID (по логину, например `volunteer-1`) и страница «тестовый незрячий»: она создаёт запрос помощи и показывает камеру компьютера. Так звонок проверяется без Android-приложения:
   1. Войдите как `volunteer-1` и нажмите «Стать волонтёром». Если сейчас время тишины (по умолчанию 22:00–08:00), вызовы не придут — отключите его в кабинете.
   2. В окне инкогнито или другом браузере (вкладки одного окна делят сохранённый вход) откройте тот же адрес, войдите как `blind-1`, нажмите «Стать тестовым незрячим» и «Попросить помощи».
   3. В кабинете волонтёра появится вызов со звуком — примите его.

   Вход через Яндекс ID на сайте: скопируйте `web/.env.example` в `web/.env.local` и укажите `VITE_YANDEX_CLIENT_ID`; в настройках приложения на <https://oauth.yandex.ru> добавьте Callback URI `http://localhost:5173/`.

4. Android: откройте корень репозитория в Android Studio и запустите конфигурацию `android.app`.

## Тесты и линтеры

Тесты backend поднимают настоящий PostgreSQL в Docker (Testcontainers), поэтому Docker должен быть запущен.

```bash
./gradlew ktlintCheck :backend:test :shared:jvmTest :android:app:lintDebug :android:app:testDebugUnitTest
cd web && npm run lint && npm test && npm run build
```

`npm run build` заодно проверяет типы TypeScript. Тесты web работают с поддельными сервером, WebSocket и звонком (`web/src/testing`), backend для них не нужен.

Доступность Android: в Compose-тесте каждого экрана вызывайте `assertScreenIsAccessible()` (`android/app/src/test/.../testing/AccessibilityChecks.kt`) — тест упадёт, если у кнопки нет описания для TalkBack или она меньше 48 dp.

Проверка контракта API (из корня репозитория; правила — в `redocly.yaml`):

```bash
REDOCLY_TELEMETRY=off npx --yes @redocly/cli@2.54.3 lint
```

`REDOCLY_TELEMETRY=off` отключает отправку статистики использования в Redocly.

Форматирование Kotlin: `./gradlew ktlintFormat`.

## Лицензия

[GNU AGPL-3.0](LICENSE) (версия 3 или любая более поздняя).

Кратко: код можно свободно использовать и менять, но изменённую версию нужно публиковать под той же лицензией — в том числе если она работает как сетевой сервис и не распространяется в виде программы.
