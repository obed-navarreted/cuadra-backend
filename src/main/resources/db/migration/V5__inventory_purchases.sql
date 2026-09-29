-- Fase 5: inventario opcional (movimientos que solo se agregan), proveedores, compras y cuentas por pagar.

-- Desde cuándo se cuenta el stock de un producto: una venta anterior ya está reflejada en el conteo inicial y no se descuenta de nuevo.
ALTER TABLE product ADD COLUMN stock_tracked_since timestamptz;
UPDATE product SET stock_tracked_since = created_at WHERE track_stock;

CREATE TABLE stock_movement (
    id                   uuid PRIMARY KEY,
    business_id          uuid        NOT NULL REFERENCES business (id),
    product_id           uuid        NOT NULL REFERENCES product (id),
    kind                 text        NOT NULL CHECK (kind IN ('INITIAL', 'PURCHASE', 'PURCHASE_REVERSAL', 'SALE', 'SALE_REVERSAL', 'ADJUSTMENT', 'DAMAGE', 'RETURN')),
    -- Con signo, en milésimas de la unidad del producto. Un conteo sin diferencia se guarda con 0.
    quantity_milli       bigint      NOT NULL,
    unit_cost_minor      bigint,
    ref_type             text,
    ref_id               uuid,
    note                 text,
    created_by_member_id uuid        REFERENCES member (id),
    device_id            uuid        REFERENCES device (id),
    occurred_at          timestamptz NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    rev                  bigint      NOT NULL DEFAULT nextval('change_rev_seq')
);
CREATE INDEX ix_stock_movement_product ON stock_movement (business_id, product_id, occurred_at DESC);
CREATE INDEX ix_stock_movement_rev ON stock_movement (business_id, rev);
CREATE INDEX ix_stock_movement_ref ON stock_movement (ref_type, ref_id);

CREATE TABLE supplier (
    id          uuid PRIMARY KEY,
    business_id uuid        NOT NULL REFERENCES business (id),
    name        text        NOT NULL,
    phone_e164  text,
    notes       text,
    active      boolean     NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    rev         bigint      NOT NULL DEFAULT nextval('change_rev_seq')
);
CREATE INDEX ix_supplier_business ON supplier (business_id, active);
CREATE INDEX ix_supplier_rev ON supplier (business_id, rev);

CREATE TABLE purchase (
    id                   uuid PRIMARY KEY,
    business_id          uuid        NOT NULL REFERENCES business (id),
    -- El proveedor es opcional: basta el nombre en texto, igual que el cliente en el fiado.
    supplier_id          uuid        REFERENCES supplier (id),
    supplier_label       text,
    total_minor          bigint      NOT NULL CHECK (total_minor >= 0),
    note                 text,
    occurred_at          timestamptz NOT NULL,
    created_by_member_id uuid        NOT NULL REFERENCES member (id),
    device_id            uuid        REFERENCES device (id),
    voided_by_member_id  uuid        REFERENCES member (id),
    voided_at            timestamptz,
    void_reason          text,
    created_at           timestamptz NOT NULL DEFAULT now(),
    rev                  bigint      NOT NULL DEFAULT nextval('change_rev_seq')
);
CREATE INDEX ix_purchase_business_time ON purchase (business_id, occurred_at DESC);
CREATE INDEX ix_purchase_supplier ON purchase (supplier_id);
CREATE INDEX ix_purchase_rev ON purchase (business_id, rev);

CREATE TABLE purchase_item (
    purchase_id      uuid   NOT NULL REFERENCES purchase (id),
    id               uuid   NOT NULL,
    business_id      uuid   NOT NULL,
    product_id       uuid   REFERENCES product (id),
    name             text   NOT NULL,
    quantity_milli   bigint NOT NULL CHECK (quantity_milli > 0),
    unit_cost_minor  bigint NOT NULL CHECK (unit_cost_minor >= 0),
    line_total_minor bigint NOT NULL CHECK (line_total_minor >= 0),
    position         integer NOT NULL,
    PRIMARY KEY (purchase_id, id)
);

-- Un pago a proveedor siempre es de una compra concreta. Su gasto lleva el mismo id (así el teléfono lo crea igual sin hablarse con el servidor).
CREATE TABLE supplier_payment (
    id                   uuid PRIMARY KEY,
    business_id          uuid        NOT NULL REFERENCES business (id),
    purchase_id          uuid        NOT NULL REFERENCES purchase (id),
    supplier_id          uuid        REFERENCES supplier (id),
    amount_minor         bigint      NOT NULL CHECK (amount_minor > 0),
    source               text        NOT NULL CHECK (source IN ('CASH_DRAWER', 'BANK', 'CARD', 'OWNER', 'OTHER')),
    note                 text,
    occurred_at          timestamptz NOT NULL,
    created_by_member_id uuid        NOT NULL REFERENCES member (id),
    device_id            uuid        REFERENCES device (id),
    voided_by_member_id  uuid        REFERENCES member (id),
    voided_at            timestamptz,
    void_reason          text,
    rev                  bigint      NOT NULL DEFAULT nextval('change_rev_seq')
);
CREATE INDEX ix_supplier_payment_purchase ON supplier_payment (purchase_id);
CREATE INDEX ix_supplier_payment_rev ON supplier_payment (business_id, rev);

-- Un gasto nacido de un pago a proveedor no se anula suelto: se anula el pago.
ALTER TABLE expense ADD COLUMN ref_type text;
ALTER TABLE expense ADD COLUMN ref_id uuid;
