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
 * Planes y límites. Regla de oro: los límites solo impiden AGREGAR (miembros, teléfonos, programaciones, otro negocio) o usar comodidades de Pro;
 * lo que un negocio necesita para operar (vender, cobrar, sincronizar) nunca se bloquea, ni al bajar de plan.
 */
class PlanTest extends ApiTestBase {
    @Autowired JdbcClient jdbc;

    private String base(UUID b) { return "/api/b/" + b; }

    private void free(UUID b) {
        jdbc.sql("UPDATE subscription SET plan_code = 'FREE', status = 'MANUAL', trial_ends_at = NULL, current_period_end = NULL WHERE business_id = :b").param("b", b).update();
    }

    private void expect(org.springframework.test.web.servlet.ResultActions r, String feature, int limit) throws Exception {
        r.andExpect(status().isForbidden()).andExpect(jsonPath("$.code", is("PLAN_LIMIT"))).andExpect(jsonPath("$.feature", is(feature))).andExpect(jsonPath("$.limit", is(limit)));
    }

    // ---------- prueba y vencimientos ----------

    @Test
    void aNewBusinessStartsWithAThirtyDayProTrial() throws Exception {
        String owner = login("pla");
        UUID b = createBusiness(owner, "Prueba");
        call(get(base(b) + "/plan"), bearer(owner), null).andExpect(status().isOk()).andExpect(jsonPath("$.plan", is("PRO"))).andExpect(jsonPath("$.status", is("TRIALING")))
                .andExpect(jsonPath("$.trialing", is(true))).andExpect(jsonPath("$.trialDaysLeft", is(29))).andExpect(jsonPath("$.limits.devices", is(10)))
                .andExpect(jsonPath("$.limits.export", is(true))).andExpect(jsonPath("$.usage.members", is(1))).andExpect(jsonPath("$.usage.devices", is(0)));
    }

    @Test
    void eachKindOfSubscriptionExpiresTheWayThePlanSays() throws Exception {
        String owner = login("plb");
        UUID b = createBusiness(owner, "Vencimientos");
        String url = base(b) + "/plan";
        // Prueba vencida → Gratis (no se borra nada, solo se limita).
        jdbc.sql("UPDATE subscription SET trial_ends_at = now() - interval '1 day' WHERE business_id = :b").param("b", b).update();
        call(get(url), bearer(owner), null).andExpect(jsonPath("$.plan", is("FREE"))).andExpect(jsonPath("$.limits.members", is(3))).andExpect(jsonPath("$.limits.webSections", hasItem("fiados")));
        // Pro activado a mano, con fecha futura o sin fecha → Pro.
        jdbc.sql("UPDATE subscription SET status = 'MANUAL', trial_ends_at = NULL, current_period_end = now() + interval '20 days' WHERE business_id = :b").param("b", b).update();
        call(get(url), bearer(owner), null).andExpect(jsonPath("$.plan", is("PRO"))).andExpect(jsonPath("$.trialing", is(false)));
        jdbc.sql("UPDATE subscription SET current_period_end = NULL WHERE business_id = :b").param("b", b).update();
        call(get(url), bearer(owner), null).andExpect(jsonPath("$.plan", is("PRO")));
        // Periodo vencido → Gratis.
        jdbc.sql("UPDATE subscription SET current_period_end = now() - interval '1 day' WHERE business_id = :b").param("b", b).update();
        call(get(url), bearer(owner), null).andExpect(jsonPath("$.plan", is("FREE")));
        // Pago atrasado: 7 días de gracia con Pro.
        jdbc.sql("UPDATE subscription SET status = 'PAST_DUE', current_period_end = now() - interval '3 days' WHERE business_id = :b").param("b", b).update();
        call(get(url), bearer(owner), null).andExpect(jsonPath("$.plan", is("PRO")));
        jdbc.sql("UPDATE subscription SET current_period_end = now() - interval '9 days' WHERE business_id = :b").param("b", b).update();
        call(get(url), bearer(owner), null).andExpect(jsonPath("$.plan", is("FREE")));
        // Cancelado: Pro hasta el fin de lo ya pagado.
        jdbc.sql("UPDATE subscription SET status = 'CANCELED', current_period_end = now() + interval '5 days' WHERE business_id = :b").param("b", b).update();
        call(get(url), bearer(owner), null).andExpect(jsonPath("$.plan", is("PRO")));
        jdbc.sql("UPDATE subscription SET current_period_end = now() - interval '1 day' WHERE business_id = :b").param("b", b).update();
        call(get(url), bearer(owner), null).andExpect(jsonPath("$.plan", is("FREE")));
    }

    // ---------- límites al agregar ----------

    @Test
    void theFreePlanAllowsThreeMembersIncludingTheOwnerAndBlocksTheFourth() throws Exception {
        String owner = login("plc");
        UUID b = createBusiness(owner, "Miembros");
        free(b);
        createPinMember(owner, b, "Kevin", "CASHIER");
        createPinMember(owner, b, "Ana", "CASHIER");
        call(get(base(b) + "/plan"), bearer(owner), null).andExpect(jsonPath("$.usage.members", is(3)));
        expect(call(post(base(b) + "/members"), bearer(owner), "{\"displayName\":\"Luis\",\"role\":\"CASHIER\",\"pin\":\"1234\"}"), "MEMBERS", 3);
        // Una invitación tampoco deja entrar a un cuarto.
        String inv = call(post(base(b) + "/invitations"), bearer(owner), "{\"role\":\"CASHIER\"}").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        expect(call(post("/api/invitations/" + JsonPath.read(inv, "$.code") + "/accept"), bearer(login("plc2")), null), "MEMBERS", 3);
        // Pro sí: pasar el negocio a Pro deja agregar.
        jdbc.sql("UPDATE subscription SET plan_code = 'PRO', status = 'ACTIVE' WHERE business_id = :b").param("b", b).update();
        call(post(base(b) + "/members"), bearer(owner), "{\"displayName\":\"Luis\",\"role\":\"CASHIER\",\"pin\":\"1234\"}").andExpect(status().isCreated());
    }

    @Test
    void theFreePlanAllowsTwoPhonesAndBlocksTheThird() throws Exception {
        String owner = login("pld");
        UUID b = createBusiness(owner, "Teléfonos");
        free(b);
        linkDevice(owner, b);
        linkDevice(owner, b);
        String req = mvc.perform(post("/api/devices/link-requests").contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"deviceName\":\"Tercero\"}")).andReturn().getResponse().getContentAsString();
        expect(call(post(base(b) + "/devices/claim"), bearer(owner), "{\"code\":\"" + JsonPath.read(req, "$.code") + "\",\"name\":\"Caja 3\"}"), "DEVICES", 2);
        // Revocar uno libera el cupo.
        UUID first = jdbc.sql("SELECT id FROM device WHERE business_id = :b ORDER BY linked_at LIMIT 1").param("b", b).query(UUID.class).single();
        call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(base(b) + "/devices/" + first), bearer(owner), null).andExpect(status().isNoContent());
        call(post(base(b) + "/devices/claim"), bearer(owner), "{\"code\":\"" + JsonPath.read(req, "$.code") + "\",\"name\":\"Caja 3\"}").andExpect(status().isCreated());
    }

    @Test
    void theFreePlanAllowsThreeActiveSchedules() throws Exception {
        String owner = login("ple");
        UUID b = createBusiness(owner, "Programaciones");
        free(b);
        String rule = "{\"type\":\"DAILY\",\"time\":\"09:00\"}";
        for (int i = 0; i < 3; i++) call(put(base(b) + "/notification-schedules/" + UUID.randomUUID()), bearer(owner), "{\"title\":\"t\",\"body\":\"b\",\"audience\":{\"all\":true},\"rule\":" + rule + "}").andExpect(status().isOk());
        expect(call(put(base(b) + "/notification-schedules/" + UUID.randomUUID()), bearer(owner), "{\"title\":\"t\",\"body\":\"b\",\"audience\":{\"all\":true},\"rule\":" + rule + "}"), "SCHEDULES", 3);
        // Una pausada no cuenta.
        call(put(base(b) + "/notification-schedules/" + UUID.randomUUID()), bearer(owner), "{\"title\":\"t\",\"body\":\"b\",\"active\":false,\"audience\":{\"all\":true},\"rule\":" + rule + "}").andExpect(status().isOk());
    }

    @Test
    void anOwnerCanHaveASecondBusinessOnlyIfOneOfThemIsPro() throws Exception {
        String owner = login("plf");
        UUID first = createBusiness(owner, "Primero");
        free(first);
        expect(call(post("/api/businesses"), bearer(owner), "{\"name\":\"Segundo\",\"country\":\"NI\"}"), "BUSINESSES", 1);
        jdbc.sql("UPDATE subscription SET plan_code = 'PRO', status = 'MANUAL' WHERE business_id = :b").param("b", first).update();
        call(post("/api/businesses"), bearer(owner), "{\"name\":\"Segundo\",\"country\":\"NI\"}").andExpect(status().isCreated());
    }

    // ---------- comodidades de Pro ----------

    @Test
    void theFreePlanReportsCoverThirtyDaysAndCannotExportWhileProCanDoBoth() throws Exception {
        String owner = login("plg");
        UUID b = createBusiness(owner, "Reportes");
        free(b);
        call(get(base(b) + "/reports/sales"), bearer(owner), null).andExpect(status().isOk());
        call(get(base(b) + "/reports/sales?from=" + java.time.LocalDate.now().minusDays(29) + "&to=" + java.time.LocalDate.now()), bearer(owner), null).andExpect(status().isOk());
        expect(call(get(base(b) + "/reports/sales?from=" + java.time.LocalDate.now().minusDays(120) + "&to=" + java.time.LocalDate.now()), bearer(owner), null), "REPORT_HISTORY", 30);
        expect(call(get(base(b) + "/reports/sales.csv"), bearer(owner), null), "EXPORT", 0);
        expect(call(get(base(b) + "/reports/inventory.csv"), bearer(owner), null), "EXPORT", 0);
        jdbc.sql("UPDATE subscription SET plan_code = 'PRO', status = 'MANUAL' WHERE business_id = :b").param("b", b).update();
        call(get(base(b) + "/reports/sales.csv"), bearer(owner), null).andExpect(status().isOk());
        call(get(base(b) + "/reports/sales?from=" + java.time.LocalDate.now().minusDays(120) + "&to=" + java.time.LocalDate.now()), bearer(owner), null).andExpect(status().isOk());
    }

    // ---------- lo que nunca se bloquea ----------

    @Test
    void goingBelowTheLimitNeverBlocksSellingOrSyncingOrHidesData() throws Exception {
        String owner = login("plh");
        UUID b = createBusiness(owner, "Nunca se bloquea");
        String cashier = joinAs(owner, b, "plh2", "CASHIER");
        joinAs(owner, b, "plh3", "CASHIER");
        joinAs(owner, b, "plh4", "CASHIER");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        String device1 = linkDevice(owner, b);
        String device2 = linkDevice(owner, b);
        String device3 = linkDevice(owner, b);
        // Ahora el negocio baja a Gratis con 5 miembros y 3 teléfonos (límites 3 y 2).
        free(b);
        call(get(base(b) + "/plan"), bearer(owner), null).andExpect(jsonPath("$.usage.members", is(5))).andExpect(jsonPath("$.usage.devices", is(3)));
        call(get(base(b) + "/members"), bearer(owner), null).andExpect(jsonPath("$", hasSize(5)));
        // Los teléfonos de más y los cajeros de más siguen vendiendo y sincronizando.
        UUID sale = UUID.randomUUID();
        call(put(base(b) + "/sales/" + sale), bearer(cashier), SaleTest.sale("COMPLETED", SaleTest.item("Cuajada", 2500, 1000), SaleTest.pay("CASH", 2500, ""), "")).andExpect(status().isCreated());
        String batch = "{\"ops\":[{\"opId\":\"" + UUID.randomUUID() + "\",\"kind\":\"CATEGORY_UPSERT\",\"entityId\":\"" + UUID.randomUUID() + "\",\"payload\":{\"name\":\"Lácteos\"}}]}";
        for (String d : new String[] {device1, device2, device3}) asDevice(post(base(b) + "/sync/push"), d, kevin, batch.replace(UUID.randomUUID().toString(), UUID.randomUUID().toString())).andExpect(status().isOk());
        call(get(base(b) + "/sales?status=COMPLETED"), bearer(owner), null).andExpect(status().isOk());
        assertEquals(1, jdbc.sql("SELECT count(*) FROM sale WHERE business_id = :b AND status = 'COMPLETED'").param("b", b).query(Integer.class).single());
        // Solo se impide AGREGAR más.
        expect(call(post(base(b) + "/members"), bearer(owner), "{\"displayName\":\"Otro\",\"role\":\"CASHIER\",\"pin\":\"1234\"}"), "MEMBERS", 3);
    }
}
