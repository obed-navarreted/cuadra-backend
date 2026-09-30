-- Al abrir un 3.er teléfono personal con usuario y PIN, el más viejo se cierra con este motivo.
ALTER TABLE device DROP CONSTRAINT device_revoked_reason_check;
ALTER TABLE device ADD CONSTRAINT device_revoked_reason_check CHECK (revoked_reason IN ('MANUAL', 'MEMBER_DISABLED', 'REPLACED'));
