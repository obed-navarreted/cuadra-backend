-- Entrar con CÓDIGO DEL NEGOCIO + PIN (sin usuario, ADR 0012 · actualización 2026-09-30): el PIN identifica a la persona dentro del negocio,
-- así que no se repite entre las personas ACTIVAS de un negocio (lo revisa el servidor al crear o cambiar un PIN; en la base solo hay hashes).

-- Una persona dada de baja cuyo PIN quedó igual al de alguien activo no puede reactivarse sin un PIN nuevo (409 PIN_TAKEN).
ALTER TABLE member ADD COLUMN pin_conflict boolean NOT NULL DEFAULT false;

-- Como no se sabe QUIÉN se equivoca, el bloqueo por intentos es por NEGOCIO: 10 fallos en 15 minutos pausan la entrada con código 15 minutos.
ALTER TABLE business ADD COLUMN member_login_failures int NOT NULL DEFAULT 0;
ALTER TABLE business ADD COLUMN member_login_window_start timestamptz;
ALTER TABLE business ADD COLUMN member_login_locked_until timestamptz;

-- La caja muestra por defecto las DOS pestañas: «Manual» (TYPE) y «Productos» (QUICK/LIST).
ALTER TABLE business ALTER COLUMN pos_views SET DEFAULT '["TYPE","QUICK","LIST"]';
UPDATE business SET pos_views = '["TYPE","QUICK","LIST"]', rev = nextval('change_rev_seq')
 WHERE pos_views NOT LIKE '%"TYPE"%' OR (pos_views NOT LIKE '%"QUICK"%' AND pos_views NOT LIKE '%"LIST"%');
