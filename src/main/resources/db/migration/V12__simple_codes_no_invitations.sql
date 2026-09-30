-- Identidad simple (ADR 0012): sin invitaciones por Google y con código del negocio de 5 dígitos.
DROP TABLE IF EXISTS invitation;

-- Los códigos pasan a ser numéricos de 5 dígitos (fáciles de decir): se reasignan, únicos.
DO $$
DECLARE
    b record;
    c text;
BEGIN
    UPDATE business SET access_code = 'X' || id::text;   -- libera el espacio para reasignar sin choques
    FOR b IN SELECT id FROM business LOOP
        LOOP
            c := (10000 + floor(random() * 90000))::int::text;
            EXIT WHEN NOT EXISTS (SELECT 1 FROM business WHERE access_code = c);
        END LOOP;
        UPDATE business SET access_code = c WHERE id = b.id;
    END LOOP;
END $$;
ALTER TABLE business ADD CONSTRAINT business_access_code_format CHECK (access_code ~ '^[1-9][0-9]{4}$');
