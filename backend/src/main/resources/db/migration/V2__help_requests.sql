-- Этап 2: запросы помощи, уведомления волонтёров и оценки звонков.
-- Звонки не записываются, геолокация и данные о здоровье не хранятся (CLAUDE.md, правило 3).

CREATE TABLE help_requests (
    id                uuid PRIMARY KEY,
    -- Незрячий, который просит помощи.
    blind_user_id     uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- Язык общения, код ISO 639-1.
    language          text NOT NULL,
    -- Пожелание к полу волонтёра. NULL — без пожелания.
    gender_preference text CHECK (gender_preference IN ('male', 'female')),
    -- searching → accepted → in_call → ended; поиск может закончиться no_answer, запрос — отмениться (cancelled).
    status            text NOT NULL CHECK (status IN ('searching', 'accepted', 'in_call', 'ended', 'no_answer', 'cancelled')),
    -- Волонтёр, который принял запрос.
    accepted_by       uuid REFERENCES users (id) ON DELETE SET NULL,
    -- Номер следующей волны уведомлений: 0 — ещё ни одной не отправлено.
    next_wave         integer NOT NULL DEFAULT 0,
    created_at        timestamptz NOT NULL,
    accepted_at       timestamptz,
    -- Когда запрос закрыт: звонок завершён, отменён или никто не ответил.
    ended_at          timestamptz
);

-- У незрячего не больше одного активного запроса, у волонтёра — не больше одного звонка.
-- Это гарантирует сама база — в том числе при двойном нажатии и одновременных запросах.
CREATE UNIQUE INDEX help_requests_one_active_per_blind ON help_requests (blind_user_id)
    WHERE status IN ('searching', 'accepted', 'in_call');
CREATE UNIQUE INDEX help_requests_one_call_per_volunteer ON help_requests (accepted_by)
    WHERE status IN ('accepted', 'in_call');

-- Лимит частоты запросов: сколько запросов пользователь создал за последний час.
CREATE INDEX help_requests_blind_user_created_idx ON help_requests (blind_user_id, created_at);
-- Идущие поиски: сервер проверяет их каждую секунду.
CREATE INDEX help_requests_searching_idx ON help_requests (created_at) WHERE status = 'searching';

-- Каким волонтёрам приходило уведомление о запросе.
CREATE TABLE request_notifications (
    request_id   uuid NOT NULL REFERENCES help_requests (id) ON DELETE CASCADE,
    volunteer_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- Номер волны: 0 — первая.
    wave         integer NOT NULL,
    sent_at      timestamptz NOT NULL,
    -- accepted — принял запрос; too_late — пытался принять, но запрос уже приняли или закрыли.
    -- NULL — не отвечал.
    result       text CHECK (result IN ('accepted', 'too_late')),
    PRIMARY KEY (request_id, volunteer_id)
);

-- Когда волонтёра беспокоили в последний раз: пауза между вызовами и очерёдность подбора.
CREATE INDEX request_notifications_volunteer_sent_idx ON request_notifications (volunteer_id, sent_at);

-- Оценки звонков: помог ли звонок. Оценивают оба участника; повторная оценка заменяет прежнюю.
-- Свободного текста нет: в нём могли бы оказаться данные о здоровье.
CREATE TABLE ratings (
    request_id   uuid NOT NULL REFERENCES help_requests (id) ON DELETE CASCADE,
    from_user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    helped       boolean NOT NULL,
    created_at   timestamptz NOT NULL,
    PRIMARY KEY (request_id, from_user_id)
);
