-- Identidad, negocios, equipo, dispositivos y soporte (Fase 1).
-- Convenciones: uuid generados por el cliente cuando nacen offline, montos en unidad menor (bigint),
-- `rev` tomado de una secuencia global para el cursor de sincronización.

CREATE SEQUENCE change_rev_seq;

CREATE TABLE user_account (
    id               uuid PRIMARY KEY,
    google_sub       text        NOT NULL UNIQUE,
    email            text        NOT NULL,
    email_verified   boolean     NOT NULL DEFAULT false,
    full_name        text,
    photo_url        text,
    locale           text        NOT NULL DEFAULT 'es',
    is_platform_admin boolean    NOT NULL DEFAULT false,
    created_at       timestamptz NOT NULL DEFAULT now(),
    last_login_at    timestamptz,
    deleted_at       timestamptz
);
CREATE INDEX ix_user_account_email ON user_account (lower(email));

CREATE TABLE business (
    id                uuid PRIMARY KEY,
    name              text        NOT NULL,
    type              text,
    country           text        NOT NULL,
    currency          text        NOT NULL,
    timezone          text        NOT NULL,
    default_locale    text        NOT NULL DEFAULT 'es',
    day_cutoff        time        NOT NULL DEFAULT '02:00',
    inventory_mode    text        NOT NULL DEFAULT 'OFF' CHECK (inventory_mode IN ('OFF', 'PER_PRODUCT')),
    -- Módulos activados/ocultos (fiado, gastos, inventario, turnos, catálogo, equipo).
    modules           text        NOT NULL DEFAULT '{"credit":true,"expenses":true,"inventory":false,"shifts":false,"catalog":true,"team":true}',
    pos_views         text        NOT NULL DEFAULT '["TYPE"]',
    credit_requires_customer boolean NOT NULL DEFAULT false,
    credit_default_due_days  integer,
    credit_overdue_days      integer NOT NULL DEFAULT 30,
    credit_limit_enforced    boolean NOT NULL DEFAULT false,
    shift_required    boolean     NOT NULL DEFAULT false,
    status            text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETING')),
    deletion_requested_at timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    rev               bigint      NOT NULL DEFAULT nextval('change_rev_seq')
);

CREATE TABLE business_settings (
    business_id uuid NOT NULL REFERENCES business (id),
    key         text NOT NULL,
    value       text NOT NULL,
    PRIMARY KEY (business_id, key)
);

CREATE TABLE member (
    id              uuid PRIMARY KEY,
    business_id     uuid        NOT NULL REFERENCES business (id),
    user_account_id uuid        REFERENCES user_account (id),
    display_name    text        NOT NULL,
    role            text        NOT NULL CHECK (role IN ('OWNER', 'ADMIN', 'CASHIER')),
    pin_hash        text,
    pin_set_at      timestamptz,
    pin_must_change boolean     NOT NULL DEFAULT false,
    color           text,
    status          text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_by_member_id uuid,
    created_at      timestamptz NOT NULL DEFAULT now(),
    rev             bigint      NOT NULL DEFAULT nextval('change_rev_seq')
);
CREATE UNIQUE INDEX uq_member_business_user ON member (business_id, user_account_id) WHERE user_account_id IS NOT NULL;
-- Exactamente un dueño activo por negocio.
CREATE UNIQUE INDEX uq_member_one_owner ON member (business_id) WHERE role = 'OWNER' AND status = 'ACTIVE';
CREATE INDEX ix_member_user ON member (user_account_id);

CREATE TABLE auth_session (
    id              uuid PRIMARY KEY,
    user_account_id uuid        NOT NULL REFERENCES user_account (id),
    token_hash      text        NOT NULL UNIQUE,
    kind            text        NOT NULL CHECK (kind IN ('APP', 'WEB')),
    user_agent      text,
    issued_at       timestamptz NOT NULL DEFAULT now(),
    last_seen_at    timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL,
    revoked_at      timestamptz
);
CREATE INDEX ix_auth_session_user ON auth_session (user_account_id);

CREATE TABLE invitation (
    id                   uuid PRIMARY KEY,
    business_id          uuid        NOT NULL REFERENCES business (id),
    role                 text        NOT NULL CHECK (role IN ('ADMIN', 'CASHIER')),
    code                 text        NOT NULL UNIQUE,
    email                text,
    max_uses             integer     NOT NULL DEFAULT 1,
    used_count           integer     NOT NULL DEFAULT 0,
    expires_at           timestamptz NOT NULL,
    created_by_member_id uuid        NOT NULL REFERENCES member (id),
    revoked_at           timestamptz,
    created_at           timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_invitation_business ON invitation (business_id);

CREATE TABLE cash_register (
    id          uuid PRIMARY KEY,
    business_id uuid    NOT NULL REFERENCES business (id),
    name        text    NOT NULL,
    active      boolean NOT NULL DEFAULT true,
    rev         bigint  NOT NULL DEFAULT nextval('change_rev_seq')
);
CREATE INDEX ix_cash_register_business ON cash_register (business_id);

CREATE TABLE device (
    id                  uuid PRIMARY KEY,
    business_id         uuid        NOT NULL REFERENCES business (id),
    kind                text        NOT NULL DEFAULT 'SHARED' CHECK (kind IN ('SHARED', 'PERSONAL')),
    name                text        NOT NULL,
    model               text,
    os_version          text,
    app_version         text,
    token_hash          text        UNIQUE,
    cash_register_id    uuid        REFERENCES cash_register (id),
    linked_by_member_id uuid        REFERENCES member (id),
    linked_at           timestamptz NOT NULL DEFAULT now(),
    last_seen_at        timestamptz,
    last_sync_at        timestamptz,
    pending_ops         integer     NOT NULL DEFAULT 0,
    revoked_at          timestamptz,
    rev                 bigint      NOT NULL DEFAULT nextval('change_rev_seq')
);
CREATE INDEX ix_device_business ON device (business_id);

-- El teléfono nuevo muestra un código corto; un dueño/admin lo reclama y se emite el token de dispositivo.
CREATE TABLE device_link_request (
    code            text PRIMARY KEY,
    device_name     text        NOT NULL,
    model           text,
    os_version      text,
    app_version     text,
    -- Secreto que solo conoce el teléfono que pidió el código; sirve para recoger el token.
    poll_secret_hash text       NOT NULL,
    expires_at      timestamptz NOT NULL,
    device_id       uuid        REFERENCES device (id),
    -- Token entregado una única vez al teléfono; se borra al recogerlo.
    pending_token   text,
    claimed_at      timestamptz
);

CREATE TABLE support_ticket (
    id               uuid PRIMARY KEY,
    user_account_id  uuid        REFERENCES user_account (id),
    member_id        uuid        REFERENCES member (id),
    business_id      uuid        REFERENCES business (id),
    category         text        NOT NULL CHECK (category IN ('QUESTION', 'PROBLEM', 'SUGGESTION', 'BILLING')),
    message          text        NOT NULL,
    reply_to_email   text,
    reply_to_phone   text,
    diagnostics      text,
    locale           text        NOT NULL DEFAULT 'es',
    status           text        NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW', 'ANSWERED', 'CLOSED')),
    emailed_at       timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE remote_config (
    key        text PRIMARY KEY,
    value      text        NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE audit_log (
    id              bigserial PRIMARY KEY,
    business_id     uuid,
    actor_member_id uuid,
    actor_user_id   uuid,
    device_id       uuid,
    action          text        NOT NULL,
    entity          text,
    entity_id       text,
    detail          text,
    at              timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_log_business ON audit_log (business_id, at DESC);
