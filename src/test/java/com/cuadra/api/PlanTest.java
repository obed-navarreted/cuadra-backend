package com.cuadra.api;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Todo gratis en esta versión: cada negocio tiene las funciones completas y el único límite es de 10 personas. El código de planes (Gratis/Pro, prueba)
 * queda dormido para las suscripciones futuras. Lo que un negocio necesita para operar nunca se bloquea.
 */
class PlanTest extends ApiTestBase {
    @Autowired JdbcClient jdbc;

    private String base(UUID b) { return "/api/b/" + b; }

    private void expect(org.springframework.test.web.servlet.ResultActions r, String feature, int limit) throws Exception {
        r.andExpect(status().isForbidden()).andExpect(jsonPath("$.code", is("PLAN_LIMIT"))).andExpect(jsonPath("$.feature", is(feature))).andExpect(jsonPath("$.limit", is(limit)));
    }

    @Test
    void everyBusinessGetsTheFullFeaturesWithoutATrial() throws Exception {
        String owner = login("pla");
        UUID b = createBusiness(owner, "Prueba");
        call(get(base(b) + "/plan"), bearer(owner), null).andExpect(status().isOk()).andExpect(jsonPath("$.trialing", is(false))).andExpect(jsonPath("$.trialDaysLeft", is(0)))
                .andExpect(jsonPath("$.limits.members", is(10))).andExpect(jsonPath("$.limits.devices", is(20))).andExpect(jsonPath("$.limits.schedules", is(50)))
                .andExpect(jsonPath("$.limits.reportHistoryDays", is(-1))).andExpect(jsonPath("$.limits.export", is(true))).andExpect(jsonPath("$.limits.multipleBusinesses", is(true)))
                .andExpect(jsonPath("$.limits.webSections", hasSize(11))).andExpect(jsonPath("$.usage.members", is(1))).andExpect(jsonPath("$.usage.devices", is(0)));
        assertEquals(0, jdbc.sql("SELECT count(*) FROM subscription WHERE business_id = :b AND status = 'TRIALING'").param("b", b).query(Integer.class).single());
    }

    @Test
    void aFreeRowOrAnExpiredProRowChangesNothing() throws Exception {
        String owner = login("plb");
        UUID b = createBusiness(owner, "Vencimientos");
        jdbc.sql("UPDATE subscription SET plan_code = 'PRO', status = 'MANUAL', current_period_end = now() - interval '2 days' WHERE business_id = :b").param("b", b).update();
        call(get(base(b) + "/plan"), bearer(owner), null).andExpect(jsonPath("$.limits.members", is(10))).andExpect(jsonPath("$.limits.export", is(true)));
        call(get(base(b) + "/reports/sales.csv"), bearer(owner), null).andExpect(status().isOk());
    }

    @Test
    void theOnlyLimitIsTenMembersPerBusinessIncludingTheOwner() throws Exception {
        String owner = login("plc");
        UUID b = createBusiness(owner, "Miembros");
        for (int i = 1; i <= 9; i++) createPinMember(owner, b, "Persona" + i, "CASHIER");
        call(get(base(b) + "/plan"), bearer(owner), null).andExpect(jsonPath("$.usage.members", is(10)));
        expect(call(post(base(b) + "/members"), bearer(owner), "{\"displayName\":\"Luis\",\"role\":\"CASHIER\",\"pin\":\"12345\"}"), "MEMBERS", 10);
        // Dar de baja a uno libera el cupo.
        UUID first = jdbc.sql("SELECT id FROM member WHERE business_id = :b AND display_name = 'Persona1'").param("b", b).query(UUID.class).single();
        call(put(base(b) + "/members/" + first), bearer(owner), "{\"status\":\"DISABLED\"}").andExpect(status().isOk());
        call(post(base(b) + "/members"), bearer(owner), "{\"displayName\":\"Luis\",\"role\":\"CASHIER\",\"pin\":\"12345\"}").andExpect(status().isCreated());
    }

    @Test
    void phonesSchedulesSecondBusinessExportAndHistoryAreAllOpen() throws Exception {
        String owner = login("pld");
        UUID b = createBusiness(owner, "Todo abierto");
        for (int i = 0; i < 3; i++) linkDevice(owner, b);
        String rule = "{\"type\":\"DAILY\",\"time\":\"09:00\"}";
        for (int i = 0; i < 4; i++) call(put(base(b) + "/notification-schedules/" + UUID.randomUUID()), bearer(owner), "{\"title\":\"t\",\"body\":\"b\",\"audience\":{\"all\":true},\"rule\":" + rule + "}").andExpect(status().isOk());
        call(post("/api/businesses"), bearer(owner), "{\"name\":\"Segundo\",\"country\":\"NI\"}").andExpect(status().isCreated());
        call(get(base(b) + "/reports/sales.csv"), bearer(owner), null).andExpect(status().isOk());
        call(get(base(b) + "/reports/inventory.csv"), bearer(owner), null).andExpect(status().isOk());
        call(get(base(b) + "/reports/sales?from=" + java.time.LocalDate.now().minusDays(120) + "&to=" + java.time.LocalDate.now()), bearer(owner), null).andExpect(status().isOk());
    }

    @Test
    void goingOverTheLimitNeverBlocksSellingOrSyncingOrHidesData() throws Exception {
        String owner = login("plh");
        UUID b = createBusiness(owner, "Nunca se bloquea");
        String cashier = joinAs(owner, b, "plh2", "CASHIER");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        String device1 = linkDevice(owner, b);
        // Un negocio antiguo con 12 personas activas (más que el límite) sigue funcionando entero.
        for (int i = 0; i < 10; i++) {
            jdbc.sql("INSERT INTO member (id, business_id, display_name, role, created_by_member_id) VALUES (:id, :b, :n, 'CASHIER', :id)").param("id", UUID.randomUUID()).param("b", b).param("n", "Extra" + i).update();
        }
        call(get(base(b) + "/members"), bearer(owner), null).andExpect(jsonPath("$", hasSize(13)));
        UUID sale = UUID.randomUUID();
        call(put(base(b) + "/sales/" + sale), bearer(cashier), SaleTest.sale("COMPLETED", SaleTest.item("Cuajada", 2500, 1000), SaleTest.pay("CASH", 2500, ""), "")).andExpect(status().isCreated());
        String batch = "{\"ops\":[{\"opId\":\"" + UUID.randomUUID() + "\",\"kind\":\"CATEGORY_UPSERT\",\"entityId\":\"" + UUID.randomUUID() + "\",\"payload\":{\"name\":\"Lácteos\"}}]}";
        asDevice(post(base(b) + "/sync/push"), device1, kevin, batch).andExpect(status().isOk());
        call(get(base(b) + "/sales?status=COMPLETED"), bearer(owner), null).andExpect(status().isOk());
        expect(call(post(base(b) + "/members"), bearer(owner), "{\"displayName\":\"Otro\",\"role\":\"CASHIER\",\"pin\":\"12345\"}"), "MEMBERS", 10);
    }
}
