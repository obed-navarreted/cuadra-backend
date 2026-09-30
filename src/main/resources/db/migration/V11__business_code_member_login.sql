-- Entrar con "código del negocio + usuario + PIN" (sin cuenta de Google para admins y cajeros).
-- Código del negocio: corto, sin caracteres confusos; no es secreto (el acceso lo dan usuario + PIN, con bloqueo por intentos).
ALTER TABLE business ADD COLUMN access_code text;
DO $$
DECLARE
    b record;
    c text;
BEGIN
    FOR b IN SELECT id FROM business WHERE access_code IS NULL LOOP
        LOOP
            c := upper(translate(substr(md5(random()::text || clock_timestamp()::text || b.id::text), 1, 6), '01', 'XY'));
            EXIT WHEN NOT EXISTS (SELECT 1 FROM business WHERE access_code = c);
        END LOOP;
        UPDATE business SET access_code = c WHERE id = b.id;
    END LOOP;
END $$;
ALTER TABLE business ALTER COLUMN access_code SET NOT NULL;
CREATE UNIQUE INDEX ux_business_access_code ON business (access_code);

-- Un teléfono nunca tiene más poder que la persona que lo vinculó: `trust_role` es el rol máximo con el que puede actuar.
ALTER TABLE device ADD COLUMN trust_role text NOT NULL DEFAULT 'OWNER' CHECK (trust_role IN ('OWNER', 'ADMIN', 'CASHIER'));
UPDATE device d SET trust_role = m.role FROM member m WHERE m.id = d.linked_by_member_id;

-- Bloqueo por intentos fallidos de PIN al entrar con código + usuario.
ALTER TABLE member ADD COLUMN pin_failed_count integer NOT NULL DEFAULT 0;
ALTER TABLE member ADD COLUMN pin_locked_until timestamptz;
CREATE INDEX ix_member_login ON member (business_id, lower(btrim(display_name)));
