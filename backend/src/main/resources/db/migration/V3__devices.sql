-- Этап 5: устройства для push-уведомлений о вызовах (FCM, RuStore, Web Push).
-- APNs для iOS добавится на этапе 11 — новой миграцией, расширяющей проверку push_provider.

CREATE TABLE devices (
    id             uuid PRIMARY KEY,
    user_id        uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- Канал: fcm — Firebase Cloud Messaging, rustore — RuStore Push, webpush — Web Push браузера.
    push_provider  text NOT NULL CHECK (push_provider IN ('fcm', 'rustore', 'webpush')),
    -- Токен устройства от FCM или RuStore; у Web Push — адрес подписки (https://...).
    token          text NOT NULL CHECK (char_length(token) BETWEEN 1 AND 4096),
    -- Ключи подписки Web Push (base64url): ими шифруется уведомление. У других каналов — NULL.
    webpush_p256dh text,
    webpush_auth   text,
    created_at     timestamptz NOT NULL,
    -- Последняя регистрация: клиент регистрирует устройство при каждом запуске.
    updated_at     timestamptz NOT NULL,
    -- Один токен — одно устройство: при входе под другим пользователем оно переходит к нему.
    UNIQUE (push_provider, token),
    CHECK ((push_provider = 'webpush') = (webpush_p256dh IS NOT NULL AND webpush_auth IS NOT NULL))
);

-- Устройства волонтёров, которым уходит вызов.
CREATE INDEX devices_user_id_idx ON devices (user_id);
