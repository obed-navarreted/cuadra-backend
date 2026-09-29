package com.cuadra.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cuadra.api.business.BusinessDayService;
import com.cuadra.api.business.BusinessDayService.Info;
import com.cuadra.api.business.BusinessDayService.Rule;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * La zona horaria y el corte son del NEGOCIO y tienen historial: cambiarlos no reagrupa los días ya vividos, los días siguen siendo contiguos
 * (sin huecos ni traslapes) y la regla en Java (servidor), en SQL (reportes) y en el teléfono es una sola.
 */
class BusinessDayRulesTest extends ApiTestBase {
    @Autowired JdbcClient jdbc;

    private static final ZoneId MANAGUA = ZoneId.of("America/Managua");
    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    private static Instant at(ZoneId zone, String localDateTime) {
        return java.time.LocalDateTime.parse(localDateTime).atZone(zone).toInstant();
    }

    // ---------- lógica pura ----------

    @Test
    void oneRuleKeepsTheTwoAmCutoff() {
        Info info = new Info(MANAGUA, LocalTime.of(2, 0));
        assertThat(info.dateOf(at(MANAGUA, "2026-09-29T01:30:00"))).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(info.dateOf(at(MANAGUA, "2026-09-29T02:00:00"))).isEqualTo(LocalDate.of(2026, 9, 29));
        assertThat(info.startOf(LocalDate.of(2026, 9, 29))).isEqualTo(at(MANAGUA, "2026-09-29T02:00:00"));
        assertThat(info.endOf(LocalDate.of(2026, 9, 29))).isEqualTo(at(MANAGUA, "2026-09-30T02:00:00"));
    }

    @Test
    void changingTheCutoffOnlyAffectsDaysFromTheEffectiveDate() {
        Info info = new Info(List.of(new Rule(BusinessDayService.SINCE_FOREVER, MANAGUA, LocalTime.of(2, 0)), new Rule(LocalDate.of(2026, 10, 10), MANAGUA, LocalTime.of(4, 0))));
        // Antes del cambio, todo igual que antes.
        assertThat(info.dateOf(at(MANAGUA, "2026-10-05T03:00:00"))).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(info.startOf(LocalDate.of(2026, 10, 5))).isEqualTo(at(MANAGUA, "2026-10-05T02:00:00"));
        // El último día viejo dura hasta que empieza el primero nuevo (una sola vez, 26 h), sin hueco ni traslape.
        assertThat(info.endOf(LocalDate.of(2026, 10, 9))).isEqualTo(at(MANAGUA, "2026-10-10T04:00:00")).isEqualTo(info.startOf(LocalDate.of(2026, 10, 10)));
        assertThat(info.dateOf(at(MANAGUA, "2026-10-10T03:59:00"))).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(info.dateOf(at(MANAGUA, "2026-10-10T04:00:00"))).isEqualTo(LocalDate.of(2026, 10, 10));
        // Después, el corte nuevo.
        assertThat(info.dateOf(at(MANAGUA, "2026-10-11T03:00:00"))).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(info.dateOf(at(MANAGUA, "2026-10-11T04:00:00"))).isEqualTo(LocalDate.of(2026, 10, 11));
    }

    @Test
    void changingTheTimeZoneKeepsDaysContiguous() {
        Info info = new Info(List.of(new Rule(BusinessDayService.SINCE_FOREVER, MANAGUA, LocalTime.of(2, 0)), new Rule(LocalDate.of(2026, 10, 10), BOGOTA, LocalTime.of(2, 0))));
        Instant boundary = at(BOGOTA, "2026-10-10T02:00:00");
        assertThat(info.dateOf(boundary.minusSeconds(1))).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(info.dateOf(boundary)).isEqualTo(LocalDate.of(2026, 10, 10));
        // Para cualquier instante de dos semanas alrededor del cambio: pertenece al día cuyos límites lo contienen, y los días encadenan.
        for (Instant t = boundary.minusSeconds(7 * 86400); t.isBefore(boundary.plusSeconds(7 * 86400)); t = t.plusSeconds(1800)) {
            LocalDate d = info.dateOf(t);
            assertThat(t).isAfterOrEqualTo(info.startOf(d));
            assertThat(t).isBefore(info.endOf(d));
            assertThat(info.endOf(d)).isEqualTo(info.startOf(d.plusDays(1)));
        }
        assertThat(info.pendingFrom(boundary.minusSeconds(3600))).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(info.pendingFrom(boundary)).isNull();
    }

    @Test
    void theSqlFunctionAgreesWithTheJavaRule() {
        UUID b = UUID.randomUUID();
        jdbc.sql("INSERT INTO business (id, name, country, currency, timezone) VALUES (:b, 'SQL', 'NI', 'NIO', 'America/Managua')").param("b", b).update();
        jdbc.sql("DELETE FROM business_day_rule WHERE business_id = :b").param("b", b).update();
        List<Rule> rules = List.of(new Rule(BusinessDayService.SINCE_FOREVER, MANAGUA, LocalTime.of(2, 0)), new Rule(LocalDate.of(2026, 10, 10), BOGOTA, LocalTime.of(3, 30)));
        for (Rule r : rules) {
            jdbc.sql("INSERT INTO business_day_rule (business_id, effective_from, timezone, day_cutoff) VALUES (:b, :f, :tz, :c)")
                    .param("b", b).param("f", r.from()).param("tz", r.zone().getId()).param("c", r.cutoff()).update();
        }
        Info info = new Info(rules);
        Instant start = at(MANAGUA, "2026-10-08T00:00:00");
        for (int i = 0; i < 24 * 8 * 2; i++) {
            Instant t = start.plusSeconds(i * 1800L);
            LocalDate sql = jdbc.sql("SELECT business_date(:b, :t)").param("b", b).param("t", Timestamp.from(t)).query(LocalDate.class).single();
            assertThat(sql).as("instante %s", t).isEqualTo(info.dateOf(t));
        }
    }

    // ---------- por la API ----------

    @Test
    void aBusinessWithoutActivityChangesItsZoneImmediatelyAndAnActiveOneFromTomorrow() throws Exception {
        String owner = login("rules-a");
        UUID b = createBusiness(owner, "Reglas A");
        // Sin actividad: se aplica de una vez y sin historial.
        call(put("/api/b/" + b), bearer(owner), "{\"timezone\":\"America/Bogota\",\"dayCutoff\":\"03:00\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone", is("America/Bogota"))).andExpect(jsonPath("$.dayCutoff", is("03:00")))
                .andExpect(jsonPath("$.dayRules", hasSize(1))).andExpect(jsonPath("$.dayRuleEffectiveFrom", is(nullValue())));
        // Con actividad (aquí, un gasto): rige desde la próxima jornada y queda como pendiente.
        UUID expense = UUID.randomUUID();
        call(put("/api/b/" + b + "/expenses/" + expense), bearer(owner), "{\"amountMinor\":1000,\"source\":\"CASH_DRAWER\",\"category\":\"other\",\"note\":\"x\"}");
        String json = call(put("/api/b/" + b), bearer(owner), "{\"dayCutoff\":\"05:00\"}").andExpect(status().isOk()).andExpect(jsonPath("$.dayRules", hasSize(2)))
                .andReturn().getResponse().getContentAsString();
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(json, "$.dayRuleEffectiveFrom")).isNotNull();
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(json, "$.dayRules[0].dayCutoff")).isEqualTo("03:00");
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(json, "$.dayRules[1].dayCutoff")).isEqualTo("05:00");
        // Cambiarlo otra vez antes de que rija reemplaza la pendiente (no acumula reglas).
        call(put("/api/b/" + b), bearer(owner), "{\"dayCutoff\":\"06:00\"}").andExpect(jsonPath("$.dayRules", hasSize(2))).andExpect(jsonPath("$.dayRules[1].dayCutoff", is("06:00")));
        call(get("/api/b/" + b), bearer(owner), null).andExpect(jsonPath("$.dayRuleEffectiveFrom").isNotEmpty());
    }

    @Test
    void theDefaultCutoffAvoidsTheDaylightSavingHour() throws Exception {
        String owner = login("rules-b");
        String json = call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/businesses"), bearer(owner), "{\"name\":\"Nueva York\",\"country\":\"US\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(json, "$.timezone")).isEqualTo("America/New_York");
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(json, "$.dayCutoff")).isEqualTo("04:00");
        // Y donde no hay horario de verano se conserva el 02:00 de siempre.
        UUID ni = createBusiness(login("rules-c"), "Managua");
        call(get("/api/b/" + ni), bearer(login("rules-c")), null).andExpect(jsonPath("$.dayCutoff", is("02:00")));
    }
}
