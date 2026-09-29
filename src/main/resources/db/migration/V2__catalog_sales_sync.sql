-- Fase 2: catálogo, ventas con pagos, jornada por negocio y recibos de sincronización.

CREATE TABLE category (
    id          uuid PRIMARY KEY,
    business_id uuid    NOT NULL REFERENCES business (id),
    name        text    NOT NULL,
    active      boolean NOT NULL DEFAULT true,
    rev         bigint  NOT NULL DEFAULT nextval('change_rev_seq')
);
CREATE INDEX ix_category_business ON category (business_id);

CREATE TABLE product (
    id              uuid PRIMARY KEY,
    business_id     uuid    NOT NULL REFERENCES business (id),
    barcode         text,
    short_code      text,
    name            text    NOT NULL,
    variant         text,
    category_id     uuid    REFERENCES category (id),
    unit            text    NOT NULL DEFAULT 'UNIT' CHECK (unit IN ('UNIT', 'LB', 'KG', 'L', 'M')),
    -- BY_WEIGHT: el precio es por unidad de peso y la cantidad se escribe en la caja (peso o monto).
    pricing         text    NOT NULL DEFAULT 'FIXED' CHECK (pricing IN ('FIXED', 'BY_WEIGHT')),
    price_minor     bigint  NOT NULL CHECK (price_minor >= 0),
    cost_minor      bigint  CHECK (cost_minor >= 0),
    is_quick        boolean NOT NULL DEFAULT false,
    quick_position  integer,
    color           text,
    track_stock     boolean NOT NULL DEFAULT false,
    stock_milli     bigint  NOT NULL DEFAULT 0,
    min_stock_milli bigint,
    active          boolean NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    rev             bigint  NOT NULL DEFAULT nextval('change_rev_seq')
);
CREATE INDEX ix_product_business ON product (business_id, active);
CREATE INDEX ix_product_rev ON product (business_id, rev);
-- Un código identifica a un solo producto activo dentro del negocio.
CREATE UNIQUE INDEX uq_product_barcode_active ON product (business_id, barcode) WHERE active AND barcode IS NOT NULL;
CREATE UNIQUE INDEX uq_product_short_code_active ON product (business_id, short_code) WHERE active AND short_code IS NOT NULL;

CREATE TABLE product_price_history (
    id          bigserial PRIMARY KEY,
    business_id uuid        NOT NULL,
    product_id  uuid        NOT NULL REFERENCES product (id),
    price_minor bigint      NOT NULL,
    cost_minor  bigint,
    changed_by_member_id uuid,
    at          timestamptz NOT NULL DEFAULT now()
);

-- Jornada comercial (corte configurable por negocio): sirve para reportes y para el resumen del día.
CREATE TABLE business_day (
    id          uuid PRIMARY KEY,
    business_id uuid        NOT NULL REFERENCES business (id),
    date        date        NOT NULL,
    starts_at   timestamptz NOT NULL,
    ends_at     timestamptz NOT NULL,
    UNIQUE (business_id, date)
);

CREATE TABLE sale (
    id                   uuid PRIMARY KEY,
    business_id          uuid        NOT NULL REFERENCES business (id),
    cash_register_id     uuid        REFERENCES cash_register (id),
    device_id            uuid        REFERENCES device (id),
    business_day_id      uuid        REFERENCES business_day (id),
    status               text        NOT NULL CHECK (status IN ('OPEN', 'PARKED', 'COMPLETED', 'CANCELLED')),
    label                text,
    subtotal_minor       bigint      NOT NULL CHECK (subtotal_minor >= 0),
    discount_minor       bigint      NOT NULL DEFAULT 0 CHECK (discount_minor >= 0),
    total_minor          bigint      NOT NULL CHECK (total_minor >= 0),
    created_by_member_id uuid        NOT NULL REFERENCES member (id),
    completed_by_member_id uuid      REFERENCES member (id),
    completed_at         timestamptz,
    edited_by_member_id  uuid        REFERENCES member (id),
    edited_at            timestamptz,
    cancelled_by_member_id uuid      REFERENCES member (id),
    cancelled_at         timestamptz,
    cancel_reason        text,
    -- Bloqueo suave de una cuenta apartada mientras un teléfono la tiene abierta.
    locked_by_device_id  uuid        REFERENCES device (id),
    locked_until         timestamptz,
    content_hash         text        NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    rev                  bigint      NOT NULL DEFAULT nextval('change_rev_seq')
);
CREATE INDEX ix_sale_business_completed ON sale (business_id, completed_at DESC);
CREATE INDEX ix_sale_business_rev ON sale (business_id, rev);
CREATE INDEX ix_sale_business_status ON sale (business_id, status);

CREATE TABLE sale_item (
    id               uuid    NOT NULL,
    sale_id          uuid    NOT NULL REFERENCES sale (id) ON DELETE CASCADE,
    business_id      uuid    NOT NULL,
    product_id       uuid    REFERENCES product (id),
    barcode          text,
    name             text    NOT NULL,
    variant          text,
    unit_price_minor bigint  NOT NULL CHECK (unit_price_minor >= 0),
    unit_cost_minor  bigint,
    quantity_milli   bigint  NOT NULL CHECK (quantity_milli > 0),
    discount_minor   bigint  NOT NULL DEFAULT 0 CHECK (discount_minor >= 0),
    position         integer NOT NULL,
    -- El id de la línea es del teléfono: solo tiene que ser único dentro de su venta.
    PRIMARY KEY (sale_id, id)
);
CREATE INDEX ix_sale_item_sale ON sale_item (sale_id);
CREATE INDEX ix_sale_item_product ON sale_item (business_id, product_id);

CREATE TABLE sale_payment (
    id             uuid    NOT NULL,
    sale_id        uuid    NOT NULL REFERENCES sale (id) ON DELETE CASCADE,
    business_id    uuid    NOT NULL,
    method         text    NOT NULL CHECK (method IN ('CASH', 'TRANSFER', 'CARD', 'CREDIT', 'OTHER')),
    other_label    text,
    -- Parte de la venta que cubre este pago. Solo el efectivo puede entregarse de más (vuelto).
    amount_minor   bigint  NOT NULL CHECK (amount_minor > 0),
    tendered_minor bigint,
    change_minor   bigint,
    reference      text,
    position       integer NOT NULL,
    PRIMARY KEY (sale_id, id)
);
CREATE INDEX ix_sale_payment_sale ON sale_payment (sale_id);

-- Una operación de sincronización se aplica una sola vez aunque el teléfono la repita.
CREATE TABLE operation_receipt (
    op_id       uuid PRIMARY KEY,
    business_id uuid        NOT NULL,
    device_id   uuid,
    kind        text        NOT NULL,
    entity_id   uuid,
    status      text        NOT NULL,
    code        text,
    rev         bigint,
    received_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_operation_receipt_business ON operation_receipt (business_id, received_at);
