-- Fase 6: bandeja de notificaciones, programador, preferencias y tokens de push.
-- Toda notificación queda en la bandeja (sincronizable al teléfono); FCM solo añade inmediatez.

CREATE TABLE push_token (
    id              uuid PRIMARY KEY,
    business_id     uuid        REFERENCES business (id),
    device_id       uuid        REFERENCES device (id),
    member_id       uuid        REFERENCES member (id),
    user_account_id uuid        REFERENCES user_account (id),
    fcm_token       text        NOT NULL UNIQUE,
    platform        text        NOT NULL DEFAULT 'ANDROID',
    locale          text        NOT NULL DEFAULT 'es',
    app_version     text,
    updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_push_token_device ON push_token (device_id);
CREATE INDEX ix_push_token_member ON push_token (member_id);

CREATE TABLE notification (
    id                  uuid PRIMARY KEY,
    business_id         uuid        NOT NULL REFERENCES business (id),
    recipient_member_id uuid        REFERENCES member (id),
    recipient_device_id uuid        REFERENCES device (id),
    type                text        NOT NULL,
    -- Datos del aviso: la app arma el texto en su idioma; `title`/`body` son el respaldo ya localizado por el servidor.
    args                text        NOT NULL DEFAULT '{}',
    title               text,
    body                text,
    deep_link           text,
    -- false = solo bandeja (horas de silencio): no se muestra como aviso del sistema.
    push                boolean     NOT NULL DEFAULT true,
    schedule_id         uuid,
    dedupe_key          text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    read_at             timestamptz,
    rev                 bigint      NOT NULL DEFAULT nextval('change_rev_seq'),
    CHECK (recipient_member_id IS NOT NULL OR recipient_device_id IS NOT NULL)
);
CREATE INDEX ix_notification_member ON notification (business_id, recipient_member_id, created_at DESC);
CREATE INDEX ix_notification_device ON notification (business_id, recipient_device_id, created_at DESC);
CREATE INDEX ix_notification_rev ON notification (business_id, rev);
CREATE INDEX ix_notification_schedule ON notification (schedule_id);
-- "Máximo un aviso por producto por día", "un solo cierre olvidado por turno y día": el servidor lo garantiza aunque el evento se repita.
CREATE UNIQUE INDEX uq_notification_dedupe_member ON notification (business_id, recipient_member_id, dedupe_key) WHERE dedupe_key IS NOT NULL AND recipient_member_id IS NOT NULL;
CREATE UNIQUE INDEX uq_notification_dedupe_device ON notification (business_id, recipient_device_id, dedupe_key) WHERE dedupe_key IS NOT NULL AND recipient_device_id IS NOT NULL;

CREATE TABLE notification_delivery (
    id              bigserial PRIMARY KEY,
    notification_id uuid        NOT NULL REFERENCES notification (id),
    push_token_id   uuid        REFERENCES push_token (id) ON DELETE SET NULL,
    status          text        NOT NULL CHECK (status IN ('SENT', 'FAILED', 'SKIPPED_LATE')),
    error           text,
    at              timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_notification_delivery_notification ON notification_delivery (notification_id);

CREATE TABLE notification_preference (
    member_id uuid    NOT NULL REFERENCES member (id),
    type      text    NOT NULL,
    enabled   boolean NOT NULL,
    PRIMARY KEY (member_id, type)
);

CREATE TABLE notification_schedule (
    id                   uuid PRIMARY KEY,
    business_id          uuid        NOT NULL REFERENCES business (id),
    created_by_member_id uuid        NOT NULL REFERENCES member (id),
    title                text        NOT NULL,
    body                 text        NOT NULL,
    deep_link            text,
    audience             text        NOT NULL,
    rule                 text        NOT NULL,
    timezone             text        NOT NULL,
    next_run_at          timestamptz,
    last_run_at          timestamptz,
    active               boolean     NOT NULL DEFAULT true,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    deleted_at           timestamptz,
    rev                  bigint      NOT NULL DEFAULT nextval('change_rev_seq')
);
CREATE INDEX ix_notification_schedule_due ON notification_schedule (next_run_at) WHERE active AND deleted_at IS NULL;
CREATE INDEX ix_notification_schedule_business ON notification_schedule (business_id) WHERE deleted_at IS NULL;

-- Historial de envíos de cada programación (entregadas, saltadas por atraso).
CREATE TABLE notification_schedule_run (
    id          bigserial PRIMARY KEY,
    schedule_id uuid        NOT NULL REFERENCES notification_schedule (id),
    run_at      timestamptz NOT NULL,
    status      text        NOT NULL CHECK (status IN ('SENT', 'SKIPPED_LATE')),
    recipients  integer     NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_notification_schedule_run ON notification_schedule_run (schedule_id, run_at DESC);
