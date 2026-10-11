package com.cuadra.api;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cuadra.api.sale.SaleService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** ADR 0017: un cajero solo ve sus propias ventas de los últimos 60 minutos, en todas las rutas del servidor. */
class CashierVisibilityTest extends ApiTestBase {

    @Autowired JdbcClient jdbc;

    private static String base(UUID b) { return "/api/b/" + b; }

    private UUID sell(String device, UUID who, UUID b, long ageMinutes, String status) throws Exception {
        UUID id = UUID.randomUUID();
        String body = "PARKED".equals(status)
                ? SaleTest.sale("PARKED", SaleTest.item("Pan", 1000, 1000), "", "")
                : SaleTest.sale("COMPLETED", SaleTest.item("Pan", 1000, 1000), SaleTest.pay("CASH", 1000, ""), "");
        asDevice(put(base(b) + "/sales/" + id), device, who, body).andExpect(status().isCreated());
        Timestamp t = Timestamp.from(Instant.now().minus(ageMinutes, ChronoUnit.MINUTES));
        jdbc.sql("UPDATE sale SET created_at = :t WHERE id = :id").param("t", t).param("id", id).update();
        jdbc.sql("UPDATE sale SET completed_at = :t WHERE id = :id AND completed_at IS NOT NULL").param("t", t).param("id", id).update();
        return id;
    }

    @Test
    void windowBoundariesAreExactlySixtyMinutes() {
        Instant now = Instant.parse("2026-10-11T15:00:00Z");
        assertTrue(SaleService.cashierWindow(now, now.minusSeconds(59 * 60 + 59)), "59:59 entra");
        assertTrue(SaleService.cashierWindow(now, now.minusSeconds(60 * 60)), "60:00 entra");
        assertFalse(SaleService.cashierWindow(now, now.minusSeconds(60 * 60 + 1)), "60:01 ya no");
        assertFalse(SaleService.cashierWindow(now, null));
    }

    @Test
    void aCashierOnlyReachesHisOwnSalesOfTheLastHour() throws Exception {
        String owner = login("cvis");
        UUID b = createBusiness(owner, "Visibilidad A");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        UUID lucia = createPinMember(owner, b, "Lucia", "CASHIER");
        UUID ana = createPinMember(owner, b, "Ana", "ADMIN");
        String device = linkDevice(owner, b);

        UUID recent = sell(device, kevin, b, 30, "COMPLETED");
        UUID twoHours = sell(device, kevin, b, 120, "COMPLETED");
        UUID yesterday = sell(device, kevin, b, 24 * 60 + 5, "COMPLETED");
        UUID hers = sell(device, lucia, b, 10, "COMPLETED");
        UUID parked = sell(device, lucia, b, 300, "PARKED");

        // Lista: solo la suya reciente (y las cuentas apartadas, que son del negocio), con o sin filtros.
        String today = java.time.LocalDate.now().toString();
        for (String q : new String[] {"", "?from=2000-01-01&to=2099-12-31", "?status=COMPLETED&from=" + today, "?byMember=" + lucia, "?hourFrom=0&hourTo=23"}) {
            asDevice(get(base(b) + "/sales" + q), device, kevin, null).andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[?(@.status=='COMPLETED')].id", contains(recent.toString())))
                    .andExpect(jsonPath("$.items[?(@.status=='PARKED')].id", contains(parked.toString())));
        }
        // Detalle: lo viejo y lo de otros responde igual que lo que no existe.
        asDevice(get(base(b) + "/sales/" + recent), device, kevin, null).andExpect(status().isOk());
        for (UUID hidden : new UUID[] {twoHours, yesterday, hers, UUID.randomUUID()}) {
            assertCode(asDevice(get(base(b) + "/sales/" + hidden), device, kevin, null).andExpect(status().isNotFound()), "SALE_NOT_FOUND");
        }
        // Resumen: solo lo suyo de la última hora, aunque pida otro día.
        asDevice(get(base(b) + "/sales/summary?date=2020-01-01"), device, kevin, null).andExpect(jsonPath("$.salesCount", is(1))).andExpect(jsonPath("$.totalMinor", is(1000)));
        // Cola de caja: solo cuentas apartadas (sin ventas cobradas).
        asDevice(get(base(b) + "/sales/register-queue"), device, kevin, null).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        // Reportes y exportaciones: no son de un cajero.
        for (String path : new String[] {"/reports/sales", "/reports/sales/breakdown?by=member", "/reports/daily-close", "/reports/overview", "/reports/sales.csv", "/reports/sale-items.csv", "/reports/closings"}) {
            asDevice(get(base(b) + path), device, kevin, null).andExpect(status().isForbidden());
        }
        // Devolver lo que ya no ve: no.
        String ret = "{\"items\":[{\"saleItemId\":\"" + UUID.randomUUID() + "\",\"quantityMilli\":1000}],\"reason\":\"estaba duro\",\"refundMethod\":\"CASH\"}";
        assertCode(asDevice(put(base(b) + "/sales/" + twoHours + "/returns/" + UUID.randomUUID()), device, kevin, ret).andExpect(status().isForbidden()), "RETURN_NOT_ALLOWED");

        // Dueño y admin ven todo, como antes.
        call(get(base(b) + "/sales"), bearer(owner), null).andExpect(jsonPath("$.items", hasSize(5)));
        asDevice(get(base(b) + "/sales"), device, ana, null).andExpect(jsonPath("$.items", hasSize(5)));
        asDevice(get(base(b) + "/sales/" + yesterday), device, ana, null).andExpect(status().isOk());
        asDevice(get(base(b) + "/sales?byMember=" + lucia), device, ana, null).andExpect(jsonPath("$.items", hasSize(2)));

        // La bajada del teléfono es completa para cualquier persona activa (el cursor es del teléfono): al pasar a un admin no se pierde nada.
        String all = String.join(",", recent.toString(), twoHours.toString(), yesterday.toString(), hers.toString(), parked.toString());
        for (UUID who : new UUID[] {kevin, ana}) {
            asDevice(get(base(b) + "/sync/pull?since=0"), device, who, null)
                    .andExpect(jsonPath("$.changes[?(@.type=='sale')].data.id", containsInAnyOrder(all.split(","))));
        }
    }
}
