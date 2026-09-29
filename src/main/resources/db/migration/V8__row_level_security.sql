-- Aislamiento entre negocios en la propia base (defensa en profundidad): aunque una consulta olvide filtrar por negocio,
-- dentro de una petición `/api/b/{negocio}/…` (o de un teléfono vinculado) la base solo muestra y solo deja escribir filas de ESE negocio.
--
-- Cómo funciona: la aplicación se conecta con su usuario de siempre (dueño de las tablas, que no está sujeto a RLS). Cuando la petición
-- pertenece a un negocio, la conexión hace `SET ROLE cuadra_app` y fija `app.business_id`; ese rol SÍ está sujeto a las políticas.
-- Sin contexto de negocio (inicio de sesión, consola de plataforma, trabajos programados, migraciones) no se cambia de rol: es a propósito
-- (esos flujos cruzan negocios por diseño). Sin `app.business_id`, el rol restringido no ve nada.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cuadra_app') THEN
        CREATE ROLE cuadra_app NOLOGIN;
    END IF;
    -- Quien conecta (y ejecuta esta migración) debe poder asumir el rol; un superusuario ya puede.
    BEGIN
        EXECUTE format('GRANT cuadra_app TO %I', current_user);
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE 'No se pudo conceder cuadra_app a %: %', current_user, SQLERRM;
    END;
END $$;

GRANT USAGE ON SCHEMA public TO cuadra_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO cuadra_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO cuadra_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO cuadra_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO cuadra_app;

-- Tablas con `business_id`: una política igual para todas (leer y escribir solo el negocio del contexto).
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'business_settings', 'member', 'invitation', 'cash_register', 'device', 'support_ticket', 'audit_log', 'category', 'product',
        'product_price_history', 'business_day', 'sale', 'sale_item', 'sale_payment', 'operation_receipt', 'customer', 'credit', 'credit_payment',
        'credit_event', 'message_template', 'expense_category', 'expense', 'cash_movement', 'shift', 'stock_movement', 'supplier', 'purchase',
        'purchase_item', 'supplier_payment', 'push_token', 'notification', 'notification_schedule', 'subscription', 'business_flag'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I FOR ALL TO cuadra_app USING (business_id = nullif(current_setting(''app.business_id'', true), '''')::uuid) WITH CHECK (business_id = nullif(current_setting(''app.business_id'', true), '''')::uuid)', t);
    END LOOP;
END $$;

-- `business` se identifica por `id`.
ALTER TABLE business ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON business FOR ALL TO cuadra_app
    USING (id = nullif(current_setting('app.business_id', true), '')::uuid)
    WITH CHECK (id = nullif(current_setting('app.business_id', true), '')::uuid);

-- Tablas hijas sin `business_id`: siguen a su padre.
ALTER TABLE notification_delivery ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notification_delivery FOR ALL TO cuadra_app
    USING (EXISTS (SELECT 1 FROM notification n WHERE n.id = notification_id))
    WITH CHECK (EXISTS (SELECT 1 FROM notification n WHERE n.id = notification_id));

ALTER TABLE notification_schedule_run ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notification_schedule_run FOR ALL TO cuadra_app
    USING (EXISTS (SELECT 1 FROM notification_schedule s WHERE s.id = schedule_id))
    WITH CHECK (EXISTS (SELECT 1 FROM notification_schedule s WHERE s.id = schedule_id));

ALTER TABLE notification_preference ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notification_preference FOR ALL TO cuadra_app
    USING (EXISTS (SELECT 1 FROM member m WHERE m.id = member_id))
    WITH CHECK (EXISTS (SELECT 1 FROM member m WHERE m.id = member_id));
