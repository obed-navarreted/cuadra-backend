package com.cuadra.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Lo que el panel web pidió al servidor: invitar a un correo concreto, ver la caja de cada teléfono y filtrar el historial de turnos. */
class ApiFiltersTest extends ApiTestBase {
    @Autowired JdbcClient jdbc;

    private String base(UUID b) { return "/api/b/" + b; }

    // ---------- invitaciones ----------

    @Test
    void theDeviceListNamesTheCashRegisterOfEachPhone() throws Exception {
        String owner = login("apfe");
        UUID b = createBusiness(owner, "Teléfonos");
        linkDevice(owner, b);
        jdbc.sql("UPDATE device SET cash_register_id = (SELECT id FROM cash_register WHERE business_id = :b LIMIT 1) WHERE business_id = :b").param("b", b).update();
        call(get(base(b) + "/devices"), bearer(owner), null).andExpect(jsonPath("$[0].cashRegisterName", is("Caja 1")));
    }

    // ---------- turnos ----------

    private UUID shift(String token, UUID b, boolean close) throws Exception {
        UUID id = UUID.randomUUID();
        call(put(base(b) + "/shifts/" + id), bearer(token), "{\"openingFloatMinor\":1000}").andExpect(status().isCreated());
        if (close) call(post(base(b) + "/shifts/" + id + "/close"), bearer(token), "{\"countedMinor\":1000}").andExpect(status().isOk());
        return id;
    }

    @Test
    void theShiftHistoryFiltersByStatusPersonAndDate() throws Exception {
        String owner = login("apff");
        UUID b = createBusiness(owner, "Turnos");
        String cashier = joinAs(owner, b, "apff2", "CASHIER");
        UUID closedByCashier = shift(cashier, b, true);
        UUID openByOwner = shift(owner, b, false);
        UUID cashierMember = jdbc.sql("SELECT opened_by_member_id FROM shift WHERE id = :s").param("s", closedByCashier).query(UUID.class).single();
        call(get(base(b) + "/shifts"), bearer(owner), null).andExpect(jsonPath("$.items", hasSize(2)));
        call(get(base(b) + "/shifts?status=CLOSED"), bearer(owner), null).andExpect(jsonPath("$.items", hasSize(1))).andExpect(jsonPath("$.items[0].id", is(closedByCashier.toString())));
        call(get(base(b) + "/shifts?status=OPEN"), bearer(owner), null).andExpect(jsonPath("$.items", hasSize(1))).andExpect(jsonPath("$.items[0].id", is(openByOwner.toString())));
        call(get(base(b) + "/shifts?member=" + cashierMember), bearer(owner), null).andExpect(jsonPath("$.items", hasSize(1))).andExpect(jsonPath("$.items[0].id", is(closedByCashier.toString())));
        String today = LocalDate.now(ZoneId.of("America/Managua")).toString();
        call(get(base(b) + "/shifts?from=" + today + "&to=" + today), bearer(owner), null).andExpect(jsonPath("$.items", hasSize(2)));
        call(get(base(b) + "/shifts?from=2020-01-01&to=2020-01-31"), bearer(owner), null).andExpect(jsonPath("$.items", hasSize(0))).andExpect(jsonPath("$.total", is(0)));
        call(get(base(b) + "/shifts?status=WHENEVER"), bearer(owner), null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("INVALID_STATUS")));
    }
}
