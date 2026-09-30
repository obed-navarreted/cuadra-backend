-- Devoluciones, días cerrados que no cambian y ventas que llegan después de una baja (docs/adr/0013-devoluciones-y-dias-cerrados.md).

-- Una devolución: productos de una venta cobrada que el cliente regresa. Cuenta en la jornada en que se HACE (no en la de la venta).
CREATE TABLE sale_return (
    id                   uuid PRIMARY KEY,
    business_id          uuid        NOT NULL REFERENCES business (id),
    sale_id              uuid        NOT NULL REFERENCES sale (id),
    reason               text        NOT NULL,
    -- CASH: sale del cajón. SAME: por los mismos medios con que se pagó la venta. CREDIT_NOTE: baja el fiado de esa venta.
    refund_method        text        NOT NULL CHECK (refund_method IN ('CASH', 'SAME', 'CREDIT_NOTE')),
    total_minor          bigint      NOT NULL CHECK (total_minor >= 0),
    created_by_member_id uuid        NOT NULL REFERENCES member (id),
    device_id            uuid        REFERENCES device (id),
    cash_register_id     uuid        REFERENCES cash_register (id),
    occurred_at          timestamptz NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    rev                  bigint      NOT NULL DEFAULT nextval('change_rev_seq')
);
CREATE INDEX ix_sale_return_sale ON sale_return (sale_id);
CREATE INDEX ix_sale_return_business_at ON sale_return (business_id, occurred_at);
CREATE INDEX ix_sale_return_rev ON sale_return (business_id, rev);

-- Las líneas devueltas. `sale_item_id` no es llave foránea: editar una venta reemplaza sus líneas (una venta con devoluciones ya no se edita).
CREATE TABLE sale_return_item (
    id              uuid PRIMARY KEY,
    return_id       uuid   NOT NULL REFERENCES sale_return (id),
    business_id     uuid   NOT NULL REFERENCES business (id),
    sale_item_id    uuid   NOT NULL,
    product_id      uuid   REFERENCES product (id),
    name            text   NOT NULL,
    quantity_milli  bigint NOT NULL CHECK (quantity_milli > 0),
    amount_minor    bigint NOT NULL CHECK (amount_minor >= 0),
    unit_cost_minor bigint,
    position        int    NOT NULL DEFAULT 0
);
CREATE INDEX ix_sale_return_item_return ON sale_return_item (return_id);

-- Cómo se devolvió el dinero (uno o varios medios). CREDIT = nota de crédito: bajó el fiado `credit_id`.
CREATE TABLE sale_return_refund (
    id           uuid PRIMARY KEY,
    return_id    uuid   NOT NULL REFERENCES sale_return (id),
    business_id  uuid   NOT NULL REFERENCES business (id),
    method       text   NOT NULL CHECK (method IN ('CASH', 'TRANSFER', 'CARD', 'CREDIT', 'OTHER')),
    amount_minor bigint NOT NULL CHECK (amount_minor > 0),
    credit_id    uuid   REFERENCES credit (id),
    position     int    NOT NULL DEFAULT 0
);
CREATE INDEX ix_sale_return_refund_return ON sale_return_refund (return_id);

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['sale_return', 'sale_return_item', 'sale_return_refund'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I FOR ALL TO cuadra_app USING (business_id = nullif(current_setting(''app.business_id'', true), '''')::uuid) WITH CHECK (business_id = nullif(current_setting(''app.business_id'', true), '''')::uuid)', t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO cuadra_app', t);
    END LOOP;
END $$;

-- Para revisar en Ventas: LATE_AFTER_DISABLE = llegó (sin conexión) después de que se dio de baja a quien la hizo; CLOCK_ADJUSTED = el teléfono tenía la
-- hora imposible (antes de vincularse o en el futuro) y se usó la hora del servidor.
ALTER TABLE sale ADD COLUMN review_flag text CHECK (review_flag IN ('LATE_AFTER_DISABLE', 'CLOCK_ADJUSTED'));
CREATE INDEX ix_sale_review_flag ON sale (business_id) WHERE review_flag IS NOT NULL;

-- Foto de los teléfonos del negocio en el momento de la baja (último contacto y operaciones pendientes que informaron): decide qué de lo que llega
-- después es plausible (hecho sin conexión antes de la baja) y qué no.
ALTER TABLE member ADD COLUMN disable_snapshot text;
