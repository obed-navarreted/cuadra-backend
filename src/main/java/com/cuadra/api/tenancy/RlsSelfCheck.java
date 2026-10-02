package com.cuadra.api.tenancy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Comprobación de que el aislamiento por negocio (RLS) de verdad protege con el rol EFECTIVO de las peticiones (el que queda DESPUÉS de `SET ROLE cuadra_app`),
 * no con el de la conexión: la API puede conectar como superusuario (que se salta RLS) y aun así estar protegida, porque cada petición de un negocio baja al rol
 * restringido. Lo que NO se acepta: que ese rol sea superusuario o `BYPASSRLS`, que sea dueño de tablas del negocio (el dueño se salta RLS sin FORCE), que falte
 * RLS o su política en una tabla con `business_id`, o que con otro negocio como contexto se vea cualquier fila.
 *
 * No se usa `FORCE ROW LEVEL SECURITY`: los flujos SIN contexto de negocio (inicio de sesión, consola de plataforma, trabajos programados, migraciones) corren como
 * el dueño de las tablas a propósito; con FORCE tendrían que ver cero filas. La barrera está en el rol restringido y en esta comprobación (ADR 0014).
 */
public final class RlsSelfCheck {
    public static final String APP_ROLE = "cuadra_app";
    private static final List<String> PROBE_TABLES = List.of("business", "member", "product", "sale", "sale_item", "credit", "customer", "expense", "device", "notification");

    private RlsSelfCheck() {}

    /** Lista de problemas (vacía = el aislamiento está activo y bien puesto). Se ejecuta como cualquier petición de un negocio ajeno a todos. */
    public static List<String> problems(JdbcClient jdbc) {
        List<String> out = new ArrayList<>();
        UUID probe = UUID.randomUUID();
        String user = TenantContext.call(probe, () -> jdbc.sql("SELECT current_user").query(String.class).single());
        if (!APP_ROLE.equals(user)) out.add("el rol efectivo de las peticiones es " + user + ", no " + APP_ROLE);

        var role = jdbc.sql("SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = :r").param("r", APP_ROLE)
                .query((rs, n) -> new boolean[] {rs.getBoolean(1), rs.getBoolean(2)}).optional();
        if (role.isEmpty()) out.add("no existe el rol " + APP_ROLE);
        else {
            if (role.get()[0]) out.add(APP_ROLE + " es superusuario (se salta RLS)");
            if (role.get()[1]) out.add(APP_ROLE + " tiene BYPASSRLS");
        }

        List<String> owned = jdbc.sql("SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tableowner = :r ORDER BY 1").param("r", APP_ROLE).query(String.class).list();
        if (!owned.isEmpty()) out.add(APP_ROLE + " es dueño de tablas (el dueño se salta RLS sin FORCE): " + owned);

        List<String> unprotected = jdbc.sql("""
                        SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                         WHERE n.nspname = 'public' AND c.relkind = 'r' AND c.relname <> 'flyway_schema_history'
                           AND EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attname = 'business_id' AND NOT a.attisdropped)
                           AND NOT (c.relrowsecurity AND EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid))
                         ORDER BY 1
                        """).query(String.class).list();
        if (!unprotected.isEmpty()) out.add("tablas con business_id sin RLS o sin política: " + unprotected);

        for (String table : PROBE_TABLES) {
            long visible = TenantContext.call(probe, () -> jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single());
            if (visible != 0) out.add("con un negocio ajeno como contexto se ven " + visible + " filas de " + table);
        }
        return out;
    }

    /** Cómo se conecta la API (para el registro de arranque): el rol de la conexión y si ese rol se salta RLS por sí mismo. */
    public static String connectionSummary(JdbcClient jdbc) {
        return jdbc.sql("SELECT session_user || ' (superusuario=' || rolsuper || ', bypassrls=' || rolbypassrls || ')' FROM pg_roles WHERE rolname = session_user")
                .query(String.class).optional().orElse("desconocido");
    }
}
