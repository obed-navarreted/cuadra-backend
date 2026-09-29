-- Fase 8: planes y suscripciones, consola de plataforma (auditoría, avisos, banderas), "Ver como" y suspensión de negocios.

CREATE TABLE subscription (
    business_id        uuid PRIMARY KEY REFERENCES business (id),
    plan_code          text        NOT NULL CHECK (plan_code IN ('FREE', 'PRO')),
    status             text        NOT NULL CHECK (status IN ('TRIALING', 'ACTIVE', 'PAST_DUE', 'CANCELED', 'MANUAL')),
    trial_ends_at      timestamptz,
    current_period_end timestamptz,
    provider           text        NOT NULL DEFAULT 'MANUAL',
    provider_ref       text,
    note               text,
    updated_at         timestamptz NOT NULL DEFAULT now()
);
-- Todo negocio existente empieza con la prueba de Pro (30 días desde ahora).
INSERT INTO subscription (business_id, plan_code, status, trial_ends_at) SELECT id, 'PRO', 'TRIALING', now() + interval '30 days' FROM business;

-- "Ver como" es una sesión de solo lectura, corta, ligada a un negocio.
ALTER TABLE auth_session DROP CONSTRAINT auth_session_kind_check;
ALTER TABLE auth_session ADD CONSTRAINT auth_session_kind_check CHECK (kind IN ('APP', 'WEB', 'VIEW_AS'));
ALTER TABLE auth_session ADD COLUMN view_as_business_id uuid REFERENCES business (id);

-- Toda acción de la consola queda registrada.
CREATE TABLE platform_audit_log (
    id            bigserial PRIMARY KEY,
    actor_user_id uuid        NOT NULL,
    action        text        NOT NULL,
    target        text,
    payload       text,
    at            timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_platform_audit_log_at ON platform_audit_log (at DESC);

-- Avisos a segmentos: notificación en la bandeja de dueños/admins y, si se pide, un banner dentro de la app.
CREATE TABLE platform_announcement (
    id            uuid PRIMARY KEY,
    title         text        NOT NULL,
    body          text        NOT NULL,
    deep_link     text,
    segment       text        NOT NULL,
    audience      text        NOT NULL CHECK (audience IN ('OWNERS', 'OWNERS_ADMINS', 'ALL')),
    banner        boolean     NOT NULL DEFAULT false,
    banner_until  timestamptz,
    scheduled_at  timestamptz,
    sent_at       timestamptz,
    recipients    integer     NOT NULL DEFAULT 0,
    cancelled_at  timestamptz,
    created_by    uuid        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_platform_announcement_due ON platform_announcement (scheduled_at) WHERE sent_at IS NULL AND cancelled_at IS NULL;

-- Módulos en beta por negocio.
CREATE TABLE business_flag (
    business_id uuid    NOT NULL REFERENCES business (id),
    key         text    NOT NULL,
    enabled     boolean NOT NULL,
    PRIMARY KEY (business_id, key)
);

ALTER TABLE business ADD COLUMN suspended_reason text;
ALTER TABLE business ADD COLUMN suspended_at timestamptz;
