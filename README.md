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

- JDK 21 (подойдёт JDK из Android Studio; если JDK нет, Gradle скачает его сам)
- Android SDK (ставится вместе с Android Studio) — для модулей `android` и `shared`
- Node.js 24+ — для `web`
- Docker — для локального окружения

## Локальный запуск

1. Окружение (PostgreSQL, Redis, LiveKit):

   ```bash
   cp infra/.env.example infra/.env   # заполните пустые значения
   docker compose -f infra/docker-compose.yml up
   ```

2. Сервер (порт 8080, проверка: <http://localhost:8080/health>):

   ```bash
   ./gradlew :backend:run
   ```

3. Веб:

   ```bash
   cd web && npm install && npm run dev
   ```

4. Android: откройте корень репозитория в Android Studio и запустите конфигурацию `android.app`.

## Тесты и линтеры

```bash
./gradlew ktlintCheck :backend:test :shared:jvmTest :android:app:lintDebug :android:app:testDebugUnitTest
cd web && npm run lint && npm test
```

Форматирование Kotlin: `./gradlew ktlintFormat`.

## Лицензия

[Apache-2.0](LICENSE)
