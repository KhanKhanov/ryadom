-- Этап 1: пользователи, способы входа и refresh-токены.
-- Данные о здоровье и инвалидности не собираем (CLAUDE.md, правило 3). Роль пользователь выбирает сам.
-- Уже применённые миграции не меняются: любое изменение схемы — новый файл V2__..., V3__... и т. д.

CREATE TABLE users (
    id                    uuid PRIMARY KEY,
    -- NULL — роль ещё не выбрана (выбирается при первом входе).
    role                  text CHECK (role IN ('blind', 'volunteer', 'admin')),
    -- Имя, которое видит собеседник. NULL — ещё не задано.
    display_name          text CHECK (char_length(display_name) BETWEEN 1 AND 50),
    -- Языки общения, коды ISO 639-1.
    languages             text[] NOT NULL CHECK (cardinality(languages) > 0),
    -- NULL — не указан.
    gender                text CHECK (gender IN ('male', 'female')),
    -- Пожелание незрячего к полу волонтёра. NULL — без пожелания.
    gender_preference     text CHECK (gender_preference IN ('male', 'female')),
    -- Часовой пояс IANA, например Europe/Moscow.
    timezone              text NOT NULL,
    -- Окно «не беспокоить» по местному времени. NULL — окно по умолчанию из конфига сервера.
    dnd_from              time,
    dnd_to                time,
    notifications_enabled boolean NOT NULL DEFAULT true,
    banned_at             timestamptz,
    created_at            timestamptz NOT NULL,
    CHECK ((dnd_from IS NULL) = (dnd_to IS NULL))
);

-- Способы входа пользователя: один пользователь может входить через разных провайдеров.
CREATE TABLE auth_identities (
    id         uuid PRIMARY KEY,
    user_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider   text NOT NULL CHECK (provider IN ('dev', 'yandex', 'vk')),
    -- Идентификатор пользователя у провайдера (для dev — логин).
    subject    text NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (provider, subject)
);

CREATE INDEX auth_identities_user_id_idx ON auth_identities (user_id);

-- Refresh-токены. Храним только SHA-256 от токена: утечка базы не даёт войти от имени пользователя.
-- Отозванные токены хранятся до истечения срока, чтобы распознать повторное использование (кражу).
CREATE TABLE refresh_tokens (
    id         uuid PRIMARY KEY,
    user_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash bytea NOT NULL UNIQUE,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz
);

CREATE INDEX refresh_tokens_user_id_idx ON refresh_tokens (user_id);
