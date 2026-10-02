-- Cobro en caja (ADR 0015): quien atiende arma la cuenta y la "envía a caja"; cualquiera la cobra después desde la lista "Por cobrar en caja".
-- Se apoya en las cuentas apartadas (status PARKED, ya sincronizadas y con bloqueo por teléfono): una cuenta por cobrar en caja es una PARKED con
-- `sent_to_register_at`. La nota ("Mesa 4, Juan") es la etiqueta (`label`) de siempre. No es venta hasta que se cobra.

-- Ajuste del negocio, apagado por omisión: solo cambia la interfaz (y el servidor rechaza "enviar a caja" con el ajuste apagado).
ALTER TABLE business ADD COLUMN register_checkout boolean NOT NULL DEFAULT false;

-- Cuándo y quién la envió a caja (la última vez). `created_by_member_id` sigue siendo quien la tomó y `completed_by_member_id` quien la cobra.
ALTER TABLE sale ADD COLUMN sent_to_register_at timestamptz;
ALTER TABLE sale ADD COLUMN sent_by_member_id uuid REFERENCES member (id);

-- Quién la tiene abierta (además del teléfono): "La está cobrando Ana".
ALTER TABLE sale ADD COLUMN locked_by_member_id uuid REFERENCES member (id);

CREATE INDEX sale_register_queue_idx ON sale (business_id, sent_to_register_at) WHERE status = 'PARKED' AND sent_to_register_at IS NOT NULL;
