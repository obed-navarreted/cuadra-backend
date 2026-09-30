-- Seguridad de lo hecho sin conexión (docs/notas/revision-flujo-negocio.md, hallazgos 2, 3 y 4).

-- Cuándo se dio de baja a una persona: sus operaciones hechas ANTES (sin conexión) se siguen aceptando; las posteriores no.
ALTER TABLE member ADD COLUMN disabled_at timestamptz;
UPDATE member SET disabled_at = now() WHERE status = 'DISABLED';

-- Por qué se revocó un teléfono. MEMBER_DISABLED: el teléfono personal de alguien dado de baja; solo puede terminar de ENVIAR lo que hizo antes.
ALTER TABLE device ADD COLUMN revoked_reason text CHECK (revoked_reason IN ('MANUAL', 'MEMBER_DISABLED'));
UPDATE device SET revoked_reason = 'MANUAL' WHERE revoked_at IS NOT NULL;

-- Una venta cobrada sin conexión que chocó con otra versión (la cuenta ya se había cobrado o descartado en otro teléfono) se guarda aparte, marcada
-- para revisión: un pago recibido nunca se pierde. `conflict_of_sale_id` apunta a la venta original.
ALTER TABLE sale ADD COLUMN conflict_of_sale_id uuid REFERENCES sale (id);
CREATE INDEX ix_sale_conflict ON sale (business_id) WHERE conflict_of_sale_id IS NOT NULL;
