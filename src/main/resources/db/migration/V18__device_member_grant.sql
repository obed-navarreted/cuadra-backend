-- Elevación por PIN verificado (ADR 0012, actualización 2026-10-01). Cualquier persona del negocio puede usar cualquier teléfono del negocio con su PIN.
-- `device.trust_role` deja de ser un techo: es el ROL BASE del teléfono (el de quien lo vinculó). Quien tenga más rol que eso actúa con su rol solo
-- si el SERVIDOR comprobó su PIN en ese teléfono (`POST /api/devices/me/members/{id}/verify-pin`): queda un permiso corto aquí.
-- Sin permiso, la persona actúa con el rol base (vender y cobrar nunca lo necesita) y lo de más arriba responde 403 PIN_VERIFICATION_REQUIRED.
COMMENT ON COLUMN device.trust_role IS 'Rol base del teléfono (de quien lo vinculó). Más rol exige un permiso en device_member_grant.';

CREATE TABLE device_member_grant (
    id          uuid        PRIMARY KEY,
    device_id   uuid        NOT NULL REFERENCES device (id),
    member_id   uuid        NOT NULL REFERENCES member (id),
    business_id uuid        NOT NULL REFERENCES business (id),
    granted_at  timestamptz NOT NULL,
    -- Se corre (12 h desde el último uso) mientras se usa; vencido o revocado ya no vale para lo nuevo, pero su ventana sigue sirviendo para lo que
    -- se hizo sin conexión DENTRO de ella.
    expires_at  timestamptz NOT NULL,
    -- Baja de la persona, PIN restablecido o teléfono revocado.
    revoked_at  timestamptz
);
CREATE INDEX ix_device_member_grant_lookup ON device_member_grant (device_id, member_id, granted_at DESC);
CREATE INDEX ix_device_member_grant_member ON device_member_grant (business_id, member_id) WHERE revoked_at IS NULL;

ALTER TABLE device_member_grant ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON device_member_grant FOR ALL TO cuadra_app
    USING (business_id = nullif(current_setting('app.business_id', true), '')::uuid)
    WITH CHECK (business_id = nullif(current_setting('app.business_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON device_member_grant TO cuadra_app;
