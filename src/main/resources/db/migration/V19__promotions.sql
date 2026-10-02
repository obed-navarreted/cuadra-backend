-- Promociones por cantidad (PENDIENTES.md, «Promociones por cantidad»): «3 por C$ 100» sobre uno o varios productos.
-- El precio lo calcula el TELÉFONO (funciona sin conexión) con las promociones sincronizadas; el servidor nunca vuelve a calcular una venta que llega:
-- guarda lo cobrado (el descuento de la promoción va repartido en `sale_item.discount_minor`) y, aparte, qué promociones se aplicaron (`sale_promotion`)
-- para reportes, comprobantes, devoluciones y el cierre del día.

CREATE TABLE promotion (
    id          uuid        PRIMARY KEY,
    business_id uuid        NOT NULL REFERENCES business (id),
    name        text        NOT NULL,
    -- «N por X»: cuántas unidades forman un paquete y cuánto cuesta el paquete (unidad menor).
    quantity    integer     NOT NULL CHECK (quantity >= 2),
    price_minor bigint      NOT NULL CHECK (price_minor > 0),
    active      boolean     NOT NULL DEFAULT true,
    -- Fechas opcionales en JORNADAS del negocio (ADR 0011): vale desde el inicio de `starts_on` hasta el final de `ends_on`.
    starts_on   date,
    ends_on     date,
    -- Borrada: se conserva (las ventas la nombran) y los teléfonos la quitan al sincronizar.
    deleted_at  timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    rev         bigint      NOT NULL DEFAULT nextval('change_rev_seq'),
    CHECK (starts_on IS NULL OR ends_on IS NULL OR ends_on >= starts_on)
);
CREATE INDEX ix_promotion_rev ON promotion (business_id, rev);

-- Productos a los que aplica (uno o varios: «todas las cervezas»). Cambiar la lista sube la `rev` de la promoción.
CREATE TABLE promotion_product (
    promotion_id uuid NOT NULL REFERENCES promotion (id) ON DELETE CASCADE,
    product_id   uuid NOT NULL REFERENCES product (id),
    business_id  uuid NOT NULL,
    PRIMARY KEY (promotion_id, product_id)
);
CREATE INDEX ix_promotion_product_product ON promotion_product (business_id, product_id);

-- Lo que una venta aplicó, tal como se cobró en el teléfono (nombre, N, precio, unidades en paquetes y descuento). `promotion_id` sin llave foránea a
-- propósito: la venta conserva su foto aunque la promoción cambie.
CREATE TABLE sale_promotion (
    sale_id        uuid    NOT NULL REFERENCES sale (id) ON DELETE CASCADE,
    business_id    uuid    NOT NULL,
    position       integer NOT NULL,
    promotion_id   uuid,
    name           text    NOT NULL,
    quantity       integer NOT NULL CHECK (quantity >= 2),
    price_minor    bigint  NOT NULL CHECK (price_minor >= 0),
    units          integer NOT NULL CHECK (units > 0),
    discount_minor bigint  NOT NULL CHECK (discount_minor > 0),
    PRIMARY KEY (sale_id, position)
);
CREATE INDEX ix_sale_promotion_promotion ON sale_promotion (business_id, promotion_id);

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['promotion', 'promotion_product', 'sale_promotion'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I FOR ALL TO cuadra_app USING (business_id = nullif(current_setting(''app.business_id'', true), '''')::uuid) WITH CHECK (business_id = nullif(current_setting(''app.business_id'', true), '''')::uuid)', t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO cuadra_app', t);
    END LOOP;
END $$;
