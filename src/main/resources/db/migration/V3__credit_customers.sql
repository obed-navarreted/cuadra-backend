-- Fase 3: fiado (libreta), clientes, abonos y plantillas de mensajes.

CREATE TABLE customer (
    id                   uuid PRIMARY KEY,
    business_id          uuid        NOT NULL REFERENCES business (id),
    name                 text        NOT NULL,
    phone_e164           text,
    notes                text,
    credit_limit_minor   bigint      CHECK (credit_limit_minor >= 0),
    last_reminder_at     timestamptz,
    archived             boolean     NOT NULL DEFAULT false,
    -- Cachés recalculadas desde los fiados; nunca se editan a mano.
    balance_minor        bigint      NOT NULL DEFAULT 0,
    oldest_open_at       timestamptz,
    created_by_member_id uuid        REFERENCES member (id),
    created_at           timestamptz NOT NULL DEFAULT now(),
    rev                  bigint      NOT NULL DEFAULT nextval('change_rev_seq')
);
CREATE INDEX ix_customer_business ON customer (business_id, archived);
CREATE INDEX ix_customer_rev ON customer (business_id, rev);

-- Un fiado es una deuda concreta. Siempre tiene un deudor en texto ("doña Karla"); el cliente vinculado es opcional.
CREATE TABLE credit (
    id                     uuid PRIMARY KEY,
    business_id            uuid        NOT NULL REFERENCES business (id),
    sale_id                uuid        REFERENCES sale (id),
    sale_payment_id        uuid,
    customer_id            uuid        REFERENCES customer (id),
    debtor_label           text        NOT NULL,
    debtor_phone_e164      text,
    amount_minor           bigint      NOT NULL CHECK (amount_minor > 0),
    balance_minor          bigint      NOT NULL CHECK (balance_minor >= 0),
    status                 text        NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'PAID', 'WRITTEN_OFF', 'CANCELLED')),
    due_date               date,
    note                   text,
    last_reminder_at       timestamptz,
    created_by_member_id   uuid        NOT NULL REFERENCES member (id),
    device_id              uuid        REFERENCES device (id),
    created_at             timestamptz NOT NULL,
    written_off_by_member_id uuid      REFERENCES member (id),
    written_off_at         timestamptz,
    write_off_reason       text,
    rev                    bigint      NOT NULL DEFAULT nextval('change_rev_seq')
);
CREATE INDEX ix_credit_business_status ON credit (business_id, status, created_at);
CREATE INDEX ix_credit_customer ON credit (customer_id);
CREATE INDEX ix_credit_rev ON credit (business_id, rev);
-- Cada pago a fiado de una venta origina exactamente un fiado.
CREATE UNIQUE INDEX uq_credit_sale_payment ON credit (sale_id, sale_payment_id) WHERE sale_id IS NOT NULL;

CREATE TABLE credit_payment (
    id                   uuid PRIMARY KEY,
    business_id          uuid        NOT NULL REFERENCES business (id),
    credit_id            uuid        NOT NULL REFERENCES credit (id),
    customer_id          uuid        REFERENCES customer (id),
    -- Un abono al cliente se reparte FIFO entre sus fiados: un pago por fiado, todos con el mismo group_id.
    group_id             uuid,
    amount_minor         bigint      NOT NULL CHECK (amount_minor > 0),
    method               text        NOT NULL CHECK (method IN ('CASH', 'TRANSFER', 'CARD', 'OTHER')),
    reference            text,
    created_by_member_id uuid        NOT NULL REFERENCES member (id),
    device_id            uuid        REFERENCES device (id),
    occurred_at          timestamptz NOT NULL,
    voided_by_member_id  uuid        REFERENCES member (id),
    voided_at            timestamptz,
    void_reason          text,
    rev                  bigint      NOT NULL DEFAULT nextval('change_rev_seq')
);
CREATE INDEX ix_credit_payment_credit ON credit_payment (credit_id);
CREATE INDEX ix_credit_payment_group ON credit_payment (group_id);
CREATE INDEX ix_credit_payment_rev ON credit_payment (business_id, rev);

CREATE TABLE credit_event (
    id          bigserial PRIMARY KEY,
    business_id uuid        NOT NULL,
    credit_id   uuid,
    customer_id uuid,
    kind        text        NOT NULL,
    payload     text,
    member_id   uuid,
    at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_credit_event_credit ON credit_event (credit_id);

CREATE TABLE message_template (
    id          uuid        NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    business_id uuid        NOT NULL REFERENCES business (id),
    kind        text        NOT NULL,
    locale      text        NOT NULL,
    body        text        NOT NULL,
    rev         bigint      NOT NULL DEFAULT nextval('change_rev_seq'),
    PRIMARY KEY (business_id, kind, locale)
);

-- El pago a fiado de una venta lleva quién debe (texto libre), su teléfono y, si existe, el cliente.
ALTER TABLE sale_payment ADD COLUMN debtor_label text;
ALTER TABLE sale_payment ADD COLUMN debtor_phone_e164 text;
ALTER TABLE sale_payment ADD COLUMN customer_id uuid REFERENCES customer (id);
