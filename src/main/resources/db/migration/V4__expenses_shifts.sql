-- Fase 4: gastos, movimientos de caja, turnos y cierre.
-- Un turno pertenece a una caja física y sus movimientos se asignan POR CAJA Y HORA (no por un campo que pueda quedar mal):
-- una operación que llega tarde desde un teléfono sin conexión cae sola en el turno que le corresponde.

CREATE TABLE expense_category (
    id          uuid PRIMARY KEY,
    business_id uuid    NOT NULL REFERENCES business (id),
    -- `key` identifica una categoría de fábrica (se traduce en la app); si el negocio la renombra se guarda `name`.
    key         text,
    name        text,
    active      boolean NOT NULL DEFAULT true,
    rev         bigint  NOT NULL DEFAULT nextval('change_rev_seq'),
    CHECK (key IS NOT NULL OR name IS NOT NULL)
);
CREATE INDEX ix_expense_category_business ON expense_category (business_id);

CREATE TABLE expense (
    id                   uuid PRIMARY KEY,
    business_id          uuid        NOT NULL REFERENCES business (id),
    category_id          uuid        REFERENCES expense_category (id),
    description          text,
    amount_minor         bigint      NOT NULL CHECK (amount_minor > 0),
    -- De dónde salió el dinero: solo CASH_DRAWER afecta el cajón y el cierre.
    source               text        NOT NULL CHECK (source IN ('CASH_DRAWER', 'BANK', 'CARD', 'OWNER', 'OTHER')),
    cash_register_id     uuid        REFERENCES cash_register (id),
    created_by_member_id uuid        NOT NULL REFERENCES member (id),
    device_id            uuid        REFERENCES device (id),
    occurred_at          timestamptz NOT NULL,
    voided_by_member_id  uuid        REFERENCES member (id),
    voided_at            timestamptz,
    void_reason          text,
    rev                  bigint      NOT NULL DEFAULT nextval('change_rev_seq')
);
CREATE INDEX ix_expense_business_time ON expense (business_id, occurred_at DESC);
CREATE INDEX ix_expense_rev ON expense (business_id, rev);
CREATE INDEX ix_expense_register_time ON expense (cash_register_id, occurred_at) WHERE source = 'CASH_DRAWER' AND voided_at IS NULL;

-- Retiros (el dueño saca dinero) y entradas (se agrega sencillo): mueven el cajón pero no son gasto ni venta.
CREATE TABLE cash_movement (
    id                   uuid PRIMARY KEY,
    business_id          uuid        NOT NULL REFERENCES business (id),
    cash_register_id     uuid        NOT NULL REFERENCES cash_register (id),
    kind                 text        NOT NULL CHECK (kind IN ('WITHDRAWAL', 'DEPOSIT')),
    amount_minor         bigint      NOT NULL CHECK (amount_minor > 0),
    reason               text,
    created_by_member_id uuid        NOT NULL REFERENCES member (id),
    device_id            uuid        REFERENCES device (id),
    occurred_at          timestamptz NOT NULL,
    voided_by_member_id  uuid        REFERENCES member (id),
    voided_at            timestamptz,
    void_reason          text,
    rev                  bigint      NOT NULL DEFAULT nextval('change_rev_seq')
);
CREATE INDEX ix_cash_movement_register_time ON cash_movement (cash_register_id, occurred_at) WHERE voided_at IS NULL;
CREATE INDEX ix_cash_movement_rev ON cash_movement (business_id, rev);

CREATE TABLE shift (
    id                   uuid PRIMARY KEY,
    business_id          uuid        NOT NULL REFERENCES business (id),
    cash_register_id     uuid        NOT NULL REFERENCES cash_register (id),
    opened_by_member_id  uuid        NOT NULL REFERENCES member (id),
    opened_at            timestamptz NOT NULL,
    opening_float_minor  bigint      NOT NULL CHECK (opening_float_minor >= 0),
    closed_by_member_id  uuid        REFERENCES member (id),
    closed_at            timestamptz,
    -- Foto del esperado al cerrar; el esperado "de ahora" se recalcula siempre (una operación tardía lo mueve).
    expected_cash_minor  bigint,
    counted_cash_minor   bigint,
    difference_minor     bigint,
    denominations        text,
    note                 text,
    status               text        NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED')),
    forced_reason        text,
    -- `rev` en el momento de cerrar: lo que cambie después dentro del turno cuenta como operación tardía.
    closed_rev           bigint,
    reopened_count       integer     NOT NULL DEFAULT 0,
    rev                  bigint      NOT NULL DEFAULT nextval('change_rev_seq')
);
-- Una sola caja abierta por caja física.
CREATE UNIQUE INDEX uq_shift_one_open ON shift (cash_register_id) WHERE status = 'OPEN';
CREATE INDEX ix_shift_register_time ON shift (cash_register_id, opened_at DESC);
CREATE INDEX ix_shift_rev ON shift (business_id, rev);

-- Diferencia mínima que exige escribir una nota al cerrar (nula = nunca se exige).
ALTER TABLE business ADD COLUMN shift_note_threshold_minor bigint CHECK (shift_note_threshold_minor >= 0);

-- Un abono a fiado en efectivo entra a la caja donde se cobró.
ALTER TABLE credit_payment ADD COLUMN cash_register_id uuid REFERENCES cash_register (id);
CREATE INDEX ix_credit_payment_register_time ON credit_payment (cash_register_id, occurred_at) WHERE voided_at IS NULL AND method = 'CASH';

-- Categorías de fábrica para los negocios que ya existen (los nuevos las reciben al crearse).
INSERT INTO expense_category (id, business_id, key)
SELECT gen_random_uuid(), b.id, k.key FROM business b CROSS JOIN (VALUES ('goods'), ('utilities'), ('payroll'), ('rent'), ('transport'), ('supplies'), ('maintenance'), ('other')) AS k(key);
