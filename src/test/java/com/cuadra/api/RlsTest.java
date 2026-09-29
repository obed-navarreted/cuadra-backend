package com.cuadra.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cuadra.api.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Aislamiento por negocio en la base (RLS): aunque una consulta olvidara filtrar por negocio, dentro de un negocio solo se ven y se escriben SUS filas.
 * Además, una guardia: toda tabla nueva con `business_id` debe tener la política puesta.
 */
class RlsTest extends ApiTestBase {
    @Autowired JdbcClient jdbc;

    private UUID product(String owner, UUID business, String name) throws Exception {
        UUID id = UUID.randomUUID();
        call(put("/api/b/" + business + "/products/" + id), bearer(owner), "{\"name\":\"" + name + "\",\"priceMinor\":500}").andExpect(status().isCreated());
        return id;
    }

    private long count(String sql, UUID id) {
        return jdbc.sql(sql).param("id", id).query(Long.class).single();
    }

    @Test
    void insideABusinessTheDatabaseOnlyShowsAndAllowsThatBusinessRows() throws Exception {
        String ownerA = login("rls-a");
        String ownerB = login("rls-b");
        UUID a = createBusiness(ownerA, "RLS A");
        UUID b = createBusiness(ownerB, "RLS B");
        UUID pa = product(ownerA, a, "De A");
        UUID pb = product(ownerB, b, "De B");

        // Sin contexto (inicio de sesión, consola, trabajos) todo es visible: es a propósito.
        assertEquals(1, count("SELECT count(*) FROM product WHERE id = :id", pb));

        // Con el contexto de A: el rol es el restringido, y lo de B no existe.
        TenantContext.call(a, () -> {
            assertEquals("cuadra_app", jdbc.sql("SELECT current_user").query(String.class).single());
            assertEquals(1, count("SELECT count(*) FROM product WHERE id = :id", pa));
            assertEquals(0, count("SELECT count(*) FROM product WHERE id = :id", pb), "una consulta sin filtro de negocio no debe ver filas ajenas");
            assertEquals(0, count("SELECT count(*) FROM business WHERE id = :id", b));
            assertEquals(1, count("SELECT count(*) FROM business WHERE id = :id", a));
            // Ni modificar ni borrar lo ajeno.
            assertEquals(0, jdbc.sql("UPDATE product SET name = 'robado' WHERE id = :id").param("id", pb).update());
            assertEquals(0, jdbc.sql("DELETE FROM product WHERE id = :id").param("id", pb).update());
            // Ni escribir en otro negocio.
            assertThrows(RuntimeException.class, () -> jdbc.sql("INSERT INTO product (id, business_id, name, price_minor) VALUES (:id, :b, 'colado', 1)")
                    .param("id", UUID.randomUUID()).param("b", b).update());
            return null;
        });
        assertEquals("De B", jdbc.sql("SELECT name FROM product WHERE id = :id").param("id", pb).query(String.class).single());
    }

    @Test
    void withNoBusinessSetTheRestrictedRoleSeesNothing() {
        // Un contexto inexistente (p. ej. un negocio que no es de nadie) no ve nada de nadie.
        long visible = TenantContext.call(UUID.randomUUID(), () -> jdbc.sql("SELECT count(*) FROM sale").query(Long.class).single());
        assertEquals(0, visible);
        long members = TenantContext.call(UUID.randomUUID(), () -> jdbc.sql("SELECT count(*) FROM member").query(Long.class).single());
        assertEquals(0, members);
    }

    @Test
    void aPooledConnectionNeverKeepsTheRoleOrTheBusinessAfterItIsReturned() throws Exception {
        String owner = login("rls-c");
        UUID a = createBusiness(owner, "RLS C");
        for (int i = 0; i < 30; i++) {
            TenantContext.call(a, () -> jdbc.sql("SELECT 1").query(Integer.class).single());
            assertEquals("cuadra_app".equals(jdbc.sql("SELECT current_user").query(String.class).single()), false, "el rol restringido se quedó en la conexión");
            assertTrue(jdbc.sql("SELECT coalesce(current_setting('app.business_id', true), '')").query(String.class).single().isEmpty(), "el negocio se quedó en la conexión");
        }
    }

    @Test
    void everyTableWithABusinessIdHasItsPolicy() {
        List<String> unprotected = jdbc.sql("""
                        SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                         WHERE n.nspname = 'public' AND c.relkind = 'r' AND c.relname <> 'flyway_schema_history'
                           AND EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attname = 'business_id' AND NOT a.attisdropped)
                           AND NOT (c.relrowsecurity AND EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid))
                         ORDER BY 1
                        """).query(String.class).list();
        assertTrue(unprotected.isEmpty(), "Tablas con business_id sin aislamiento (agrega su política en la migración): " + unprotected);
        assertFalse(jdbc.sql("SELECT relrowsecurity FROM pg_class WHERE relname = 'business'").query(Boolean.class).single() == false);
    }
}
