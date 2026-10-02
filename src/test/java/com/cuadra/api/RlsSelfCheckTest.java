package com.cuadra.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cuadra.api.tenancy.RlsSelfCheck;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** La comprobación de arranque del aislamiento (RLS): pasa con el esquema real y se queja si el rol restringido deja de serlo. */
class RlsSelfCheckTest extends ApiTestBase {
    @Autowired JdbcClient jdbc;

    @Test
    void passesOnTheRealSchema() {
        assertEquals(java.util.List.of(), RlsSelfCheck.problems(jdbc));
        assertTrue(RlsSelfCheck.connectionSummary(jdbc).contains("bypassrls="));
    }

    @Test
    void complainsWhenTheRestrictedRoleCouldBypassRls() {
        jdbc.sql("ALTER ROLE cuadra_app BYPASSRLS").update();
        try {
            var problems = RlsSelfCheck.problems(jdbc);
            assertFalse(problems.isEmpty());
            assertTrue(problems.stream().anyMatch(p -> p.contains("BYPASSRLS")), problems.toString());
        } finally {
            jdbc.sql("ALTER ROLE cuadra_app NOBYPASSRLS").update();
        }
        assertEquals(java.util.List.of(), RlsSelfCheck.problems(jdbc));
    }

    @Test
    void complainsWhenTheRestrictedRoleOwnsABusinessTable() {
        jdbc.sql("CREATE TABLE rls_probe_owned (id int, business_id uuid)").update();
        jdbc.sql("ALTER TABLE rls_probe_owned OWNER TO cuadra_app").update();
        try {
            var problems = RlsSelfCheck.problems(jdbc);
            assertTrue(problems.stream().anyMatch(p -> p.contains("dueño de tablas")), problems.toString());
            assertTrue(problems.stream().anyMatch(p -> p.contains("sin RLS")), problems.toString());
        } finally {
            jdbc.sql("DROP TABLE rls_probe_owned").update();
        }
    }
}
