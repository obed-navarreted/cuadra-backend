package com.cuadra.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SaleTest extends ApiTestBase {

    static String item(String name, long price, long qtyMilli) {
        return "{\"id\":\"" + UUID.randomUUID() + "\",\"name\":\"" + name + "\",\"unitPriceMinor\":" + price + ",\"quantityMilli\":" + qtyMilli + "}";
    }

    static String pay(String method, long amount, String extra) {
        return "{\"id\":\"" + UUID.randomUUID() + "\",\"method\":\"" + method + "\",\"amountMinor\":" + amount + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    static String sale(String status, String items, String payments, String extra) {
        return "{\"status\":\"" + status + "\",\"items\":[" + items + "],\"payments\":[" + payments + "]" + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    private String url(UUID b, UUID sale) { return "/api/b/" + b + "/sales/" + sale; }

    private static String tenMinutesAgo() { return "\"completedAt\":\"" + java.time.Instant.now().minusSeconds(600) + "\""; }

    @Test
    void mixedPaymentSaleComputesTotalsOnTheServerAndKeepsTheChange() throws Exception {
        String owner = login("sala");
        UUID b = createBusiness(owner, "Ventas A");
        UUID id = UUID.randomUUID();
        // 2 × C$55.00 = 110.00; 0.75 lb × C$90.00 = 67.50 → total 177.50: C$100 en efectivo (paga con 200) + C$77.50 fiado.
        String body = sale("COMPLETED", item("Coca-Cola", 5500, 2000) + "," + item("Queso seco", 9000, 750),
                pay("CASH", 10000, "\"tenderedMinor\":20000") + "," + pay("CREDIT", 7750, "\"debtorLabel\":\"fiado a doña Karla Chávez\""), "");
        call(put(url(b, id)), bearer(owner), body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalMinor", is(17750))).andExpect(jsonPath("$.subtotalMinor", is(17750)))
                .andExpect(jsonPath("$.items[0].lineTotalMinor", is(11000))).andExpect(jsonPath("$.items[1].lineTotalMinor", is(6750)))
                .andExpect(jsonPath("$.payments", hasSize(2))).andExpect(jsonPath("$.payments[0].changeMinor", is(10000)))
                .andExpect(jsonPath("$.payments[1].changeMinor", nullValue())).andExpect(jsonPath("$.businessDayId", notNullValue()))
                .andExpect(jsonPath("$.completedBy.name", is("sala"))).andExpect(jsonPath("$.status", is("COMPLETED")));
    }

    @Test
    void lineRoundingIsHalfUpWithoutFloatingPoint() throws Exception {
        String owner = login("salb");
        UUID b = createBusiness(owner, "Ventas B");
        // 0.333 × 100 = 33.3 → 33 ; 0.5 × 25 = 12.5 → 13 ; 0.001 × 1 = 0.001 → 0
        String body = sale("PARKED", item("a", 100, 333) + "," + item("b", 25, 500) + "," + item("c", 1, 1), "", "");
        call(put(url(b, UUID.randomUUID())), bearer(owner), body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].lineTotalMinor", is(33))).andExpect(jsonPath("$.items[1].lineTotalMinor", is(13)))
                .andExpect(jsonPath("$.items[2].lineTotalMinor", is(0))).andExpect(jsonPath("$.totalMinor", is(46)));
    }

    @Test
    void invalidSalesAreRejected() throws Exception {
        String owner = login("salc");
        UUID b = createBusiness(owner, "Ventas C");
        String it = item("x", 1000, 1000);
        call(put(url(b, UUID.randomUUID())), bearer(owner), sale("COMPLETED", it, pay("CASH", 900, ""), "")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("PAYMENT_MISMATCH")));
        call(put(url(b, UUID.randomUUID())), bearer(owner), sale("COMPLETED", it, pay("CASH", 1000, "\"tenderedMinor\":500"), "")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("TENDERED_TOO_LOW")));
        call(put(url(b, UUID.randomUUID())), bearer(owner), sale("COMPLETED", it, "", "")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("PAYMENT_REQUIRED")));
        call(put(url(b, UUID.randomUUID())), bearer(owner), sale("COMPLETED", "", pay("CASH", 1, ""), "")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("EMPTY_SALE")));
        call(put(url(b, UUID.randomUUID())), bearer(owner), sale("PARKED", it, pay("CASH", 1000, ""), "")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("PAYMENTS_NOT_ALLOWED")));
        call(put(url(b, UUID.randomUUID())), bearer(owner), sale("COMPLETED", it, pay("BITCOIN", 1000, ""), "")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("INVALID_METHOD")));
        call(put(url(b, UUID.randomUUID())), bearer(owner), sale("COMPLETED", it, pay("OTHER", 1000, ""), "")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("INVALID_PAYMENT")));
        call(put(url(b, UUID.randomUUID())), bearer(owner), sale("COMPLETED", item("x", 1000, 0), pay("CASH", 1000, ""), "")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("INVALID_QUANTITY")));
        call(put(url(b, UUID.randomUUID())), bearer(owner), sale("COMPLETED", it, pay("CASH", 500, ""), "\"discountMinor\":500")).andExpect(status().isCreated()).andExpect(jsonPath("$.totalMinor", is(500)));
        call(put(url(b, UUID.randomUUID())), bearer(owner), sale("COMPLETED", it, pay("CASH", 1000, ""), "\"discountMinor\":5000")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("INVALID_DISCOUNT")));
    }

    @Test
    void replayingTheSameSaleChangesNothingAndACompletedSaleNeverGoesBack() throws Exception {
        String owner = login("sald");
        UUID b = createBusiness(owner, "Ventas D");
        UUID id = UUID.randomUUID();
        String it = item("Pan", 1000, 1000);
        String pay = pay("CASH", 1000, "");
        String parked = sale("PARKED", it, "", "");
        String done = sale("COMPLETED", it, pay, "\"completedAt\":\"2026-09-20T15:00:00Z\"");

        call(put(url(b, id)), bearer(owner), parked).andExpect(status().isCreated());
        String first = call(put(url(b, id)), bearer(owner), done).andExpect(status().isOk()).andExpect(jsonPath("$.status", is("COMPLETED")))
                .andReturn().getResponse().getContentAsString();
        String replay = call(put(url(b, id)), bearer(owner), done).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertEquals((Integer) JsonPath.read(first, "$.rev"), (Integer) JsonPath.read(replay, "$.rev"));
        // Una versión vieja (apartada) llegando tarde desde otro teléfono se ignora.
        call(put(url(b, id)), bearer(owner), parked).andExpect(status().isOk()).andExpect(jsonPath("$.status", is("COMPLETED")));
    }

    @Test
    void onlyManagersEditOrCancelCompletedSalesButAnyoneDiscardsAParkedOne() throws Exception {
        String owner = login("sale");
        UUID b = createBusiness(owner, "Ventas E");
        UUID cashier = createPinMember(owner, b, "Kevin", "CASHIER");
        String device = linkDevice(owner, b);
        UUID id = UUID.randomUUID();
        String it = item("Pan", 1000, 1000);
        // Cobrada hace 10 minutos: ya pasó el rato en que el cajero puede anular su última venta.
        asDevice(put(url(b, id)), device, cashier, sale("COMPLETED", it, pay("CASH", 1000, ""), tenMinutesAgo())).andExpect(status().isCreated());

        String edited = sale("COMPLETED", item("Pan", 1000, 2000), pay("CASH", 2000, ""), "");
        asDevice(put(url(b, id)), device, cashier, edited).andExpect(status().isForbidden());
        call(put(url(b, id)), bearer(owner), edited).andExpect(status().isOk()).andExpect(jsonPath("$.totalMinor", is(2000)))
                .andExpect(jsonPath("$.editedBy.name", is("sale"))).andExpect(jsonPath("$.completedBy.name", is("Kevin")));

        asDevice(post(url(b, id) + "/cancel"), device, cashier, "{\"reason\":\"me equivoqué\"}").andExpect(status().isForbidden());
        call(post(url(b, id) + "/cancel"), bearer(owner), "{\"reason\":\"cliente devolvió\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CANCELLED"))).andExpect(jsonPath("$.cancelReason", is("cliente devolvió")))
                .andExpect(jsonPath("$.cancelledBy.name", is("sale")));
        // Cancelar dos veces es idempotente, y una cancelada no revive.
        call(post(url(b, id) + "/cancel"), bearer(owner), "{\"reason\":\"error de cobro\"}").andExpect(status().isOk());
        call(put(url(b, id)), bearer(owner), edited).andExpect(status().isOk()).andExpect(jsonPath("$.status", is("CANCELLED")));

        UUID parked = UUID.randomUUID();
        asDevice(put(url(b, parked)), device, cashier, sale("PARKED", it, "", "\"label\":\"Señora de rojo\"")).andExpect(status().isCreated());
        asDevice(post(url(b, parked) + "/cancel"), device, cashier, null).andExpect(status().isOk()).andExpect(jsonPath("$.status", is("CANCELLED")));
    }

    @Test
    void summaryCountsCompletedSalesByMethodAndSkipsCancelled() throws Exception {
        String owner = login("salf");
        UUID b = createBusiness(owner, "Ventas F");
        UUID cashier = createPinMember(owner, b, "Kevin", "CASHIER");
        String device = linkDevice(owner, b);
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        UUID s3 = UUID.randomUUID();
        call(put(url(b, s1)), bearer(owner), sale("COMPLETED", item("a", 10000, 1000), pay("CASH", 10000, ""), "")).andExpect(status().isCreated());
        asDevice(put(url(b, s2)), device, cashier, sale("COMPLETED", item("b", 5000, 1000), pay("TRANSFER", 2000, "") + "," + pay("CREDIT", 3000, "\"debtorLabel\":\"Ana\""), "")).andExpect(status().isCreated());
        call(put(url(b, s3)), bearer(owner), sale("COMPLETED", item("c", 700, 1000), pay("CARD", 700, ""), "")).andExpect(status().isCreated());
        call(post(url(b, s3) + "/cancel"), bearer(owner), "{\"reason\":\"error de cobro\"}").andExpect(status().isOk());

        call(get("/api/b/" + b + "/sales/summary"), bearer(owner), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.salesCount", is(2))).andExpect(jsonPath("$.totalMinor", is(15000)))
                .andExpect(jsonPath("$.byMethod.CASH", is(10000))).andExpect(jsonPath("$.byMethod.TRANSFER", is(2000)))
                .andExpect(jsonPath("$.byMethod.CREDIT", is(3000))).andExpect(jsonPath("$.byMethod.CARD", is(0))).andExpect(jsonPath("$.cancelledCount", is(1)));
        // Un cajero solo ve lo que él cobró.
        asDevice(get("/api/b/" + b + "/sales/summary"), device, cashier, null).andExpect(jsonPath("$.salesCount", is(1))).andExpect(jsonPath("$.totalMinor", is(5000)));
    }

    @Test
    void theBusinessDayChangesAtTheCutoffNotAtMidnight() throws Exception {
        String owner = login("salg");
        UUID b = createBusiness(owner, "Ventas G");   // Managua = UTC-6, corte 02:00
        String it = item("x", 1000, 1000);
        // 07:30Z = 01:30 en Managua del 20 → pertenece al 19. 08:30Z = 02:30 del 20 → pertenece al 20.
        call(put(url(b, UUID.randomUUID())), bearer(owner), sale("COMPLETED", it, pay("CASH", 1000, ""), "\"completedAt\":\"2026-09-20T07:30:00Z\"")).andExpect(status().isCreated());
        call(put(url(b, UUID.randomUUID())), bearer(owner), sale("COMPLETED", it, pay("CASH", 1000, ""), "\"completedAt\":\"2026-09-20T08:30:00Z\"")).andExpect(status().isCreated());

        call(get("/api/b/" + b + "/sales/summary?date=2026-09-19"), bearer(owner), null).andExpect(jsonPath("$.salesCount", is(1)));
        call(get("/api/b/" + b + "/sales/summary?date=2026-09-20"), bearer(owner), null).andExpect(jsonPath("$.salesCount", is(1)));
        call(get("/api/b/" + b + "/sales?from=2026-09-19&to=2026-09-19"), bearer(owner), null).andExpect(jsonPath("$.items", hasSize(1)));
        call(get("/api/b/" + b + "/sales?from=2026-09-19&to=2026-09-20"), bearer(owner), null).andExpect(jsonPath("$.total", is(2)));
        // Filtro por hora local: 01:30 y 02:30.
        call(get("/api/b/" + b + "/sales?hourFrom=2&hourTo=2"), bearer(owner), null).andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    void aCashierSeesOwnSalesAndParkedTicketsButNotOthers() throws Exception {
        String owner = login("salh");
        UUID b = createBusiness(owner, "Ventas H");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        UUID luisa = createPinMember(owner, b, "Luisa", "CASHIER");
        String device = linkDevice(owner, b);
        String it = item("x", 1000, 1000);
        UUID mine = UUID.randomUUID();
        UUID hers = UUID.randomUUID();
        UUID parked = UUID.randomUUID();
        asDevice(put(url(b, mine)), device, kevin, sale("COMPLETED", it, pay("CASH", 1000, ""), "")).andExpect(status().isCreated());
        asDevice(put(url(b, hers)), device, luisa, sale("COMPLETED", it, pay("CASH", 1000, ""), "")).andExpect(status().isCreated());
        asDevice(put(url(b, parked)), device, luisa, sale("PARKED", it, "", "")).andExpect(status().isCreated());

        asDevice(get("/api/b/" + b + "/sales"), device, kevin, null).andExpect(jsonPath("$.total", is(2)));
        asDevice(get(url(b, hers)), device, kevin, null).andExpect(status().isNotFound());
        asDevice(get(url(b, parked)), device, kevin, null).andExpect(status().isOk());
        call(get("/api/b/" + b + "/sales"), bearer(owner), null).andExpect(jsonPath("$.total", is(3)));
        call(get("/api/b/" + b + "/sales?byMember=" + luisa), bearer(owner), null).andExpect(jsonPath("$.total", is(2)));
        call(get("/api/b/" + b + "/sales?method=CASH&status=COMPLETED"), bearer(owner), null).andExpect(jsonPath("$.total", is(2)));
    }

    @Test
    void aParkedTicketOpenOnOnePhoneIsLockedForTheOthers() throws Exception {
        String owner = login("sali");
        UUID b = createBusiness(owner, "Ventas I");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        String phoneA = linkDevice(owner, b);
        String phoneB = linkDevice(owner, b);
        UUID id = UUID.randomUUID();
        String it = item("x", 1000, 1000);
        asDevice(put(url(b, id)), phoneA, kevin, sale("PARKED", it, "", "")).andExpect(status().isCreated());

        asDevice(post(url(b, id) + "/lock"), phoneA, kevin, null).andExpect(status().isOk()).andExpect(jsonPath("$.lockedByDeviceId", notNullValue()));
        asDevice(post(url(b, id) + "/lock"), phoneB, kevin, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("SALE_LOCKED")));
        asDevice(put(url(b, id)), phoneB, kevin, sale("PARKED", item("y", 500, 1000), "", "")).andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("SALE_LOCKED")));
        // Quien lo tiene puede seguir editándolo; al guardar se libera el bloqueo.
        asDevice(put(url(b, id)), phoneA, kevin, sale("PARKED", item("z", 700, 1000), "", "")).andExpect(status().isOk()).andExpect(jsonPath("$.lockedByDeviceId", nullValue()));
        asDevice(post(url(b, id) + "/lock"), phoneB, kevin, null).andExpect(status().isOk());
        asDevice(delete(url(b, id) + "/lock"), phoneA, kevin, null).andExpect(status().isNoContent());   // A no es el dueño del bloqueo: no lo suelta
        asDevice(post(url(b, id) + "/lock"), phoneA, kevin, null).andExpect(status().isConflict());
        asDevice(delete(url(b, id) + "/lock"), phoneB, kevin, null).andExpect(status().isNoContent());
        asDevice(post(url(b, id) + "/lock"), phoneA, kevin, null).andExpect(status().isOk());
        // Solo un teléfono vinculado puede sostener una cuenta.
        call(post(url(b, id) + "/lock"), bearer(owner), null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("DEVICE_REQUIRED")));
    }

    @Test
    void salesOfOtherBusinessesAreInvisible() throws Exception {
        String ownerA = login("salj");
        String ownerB = login("salk");
        UUID a = createBusiness(ownerA, "Ventas J");
        UUID b = createBusiness(ownerB, "Ventas K");
        UUID id = UUID.randomUUID();
        call(put(url(a, id)), bearer(ownerA), sale("COMPLETED", item("x", 1000, 1000), pay("CASH", 1000, ""), "")).andExpect(status().isCreated());
        call(get(url(a, id)), bearer(ownerB), null).andExpect(status().isNotFound());
        call(get(url(b, id)), bearer(ownerB), null).andExpect(status().isNotFound());
        call(put(url(b, id)), bearer(ownerB), sale("COMPLETED", item("x", 1, 1000), pay("CASH", 1, ""), "")).andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("ID_TAKEN")));
        call(post(url(a, id) + "/cancel"), bearer(ownerB), null).andExpect(status().isNotFound());
    }

    @Test
    void revokingAPhoneReleasesTheTicketsItWasHolding() throws Exception {
        String owner = login("salr");
        UUID b = createBusiness(owner, "Ventas R");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        String phoneA = linkDevice(owner, b);
        String phoneB = linkDevice(owner, b);
        UUID id = UUID.randomUUID();
        String it = item("x", 1000, 1000);
        asDevice(put(url(b, id)), phoneA, kevin, sale("PARKED", it, "", "")).andExpect(status().isCreated());
        asDevice(post(url(b, id) + "/lock"), phoneA, kevin, null).andExpect(status().isOk());
        asDevice(put(url(b, id)), phoneB, kevin, sale("COMPLETED", it, pay("CASH", 1000, ""), "")).andExpect(status().isConflict());

        String devices = call(get("/api/b/" + b + "/devices"), bearer(owner), null).andReturn().getResponse().getContentAsString();
        java.util.List<String> ids = JsonPath.read(devices, "$[*].id");
        // El primero vinculado (phoneA) es el más antiguo: la lista viene del más reciente al más antiguo.
        call(delete("/api/b/" + b + "/devices/" + ids.get(ids.size() - 1)), bearer(owner), null).andExpect(status().isNoContent());

        asDevice(put(url(b, id)), phoneB, kevin, sale("COMPLETED", it, pay("CASH", 1000, ""), "")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("COMPLETED")));
    }

    /** Eliminar una venta cobrada queda con quién, cuándo y por qué; la venta se conserva como anulada y sale de los totales. */
    @Test
    void deletingAPaidSaleNeedsAReasonAndLeavesATrace() throws Exception {
        String owner = login("saldel");
        UUID b = createBusiness(owner, "Venta anulada");
        UUID cashier = createPinMember(owner, b, "Kevin", "CASHIER");
        String device = linkDevice(owner, b);
        UUID id = UUID.randomUUID();
        asDevice(put(url(b, id)), device, cashier, sale("COMPLETED", item("x", 1000, 1000), pay("CASH", 1000, ""), tenMinutesAgo())).andExpect(status().isCreated());
        // Pasados los primeros minutos el cajero no elimina una venta cobrada; sin motivo, nadie.
        asDevice(post(url(b, id) + "/cancel"), device, cashier, "{\"reason\":\"me equivoqué\"}").andExpect(status().isForbidden());
        assertCode(call(post(url(b, id) + "/cancel"), bearer(owner), "{}").andExpect(status().isBadRequest()), "REASON_REQUIRED");
        assertCode(call(post(url(b, id) + "/cancel"), bearer(owner), "{\"reason\":\"no\"}").andExpect(status().isBadRequest()), "REASON_REQUIRED");
        call(get(url(b, id)), bearer(owner), null).andExpect(jsonPath("$.status", is("COMPLETED")));
        call(post(url(b, id) + "/cancel"), bearer(owner), "{\"reason\":\"cliente devolvió todo\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CANCELLED"))).andExpect(jsonPath("$.cancelReason", is("cliente devolvió todo")));
        // La venta sigue ahí (con su contenido), marcada como anulada, con quién y cuándo.
        call(get(url(b, id)), bearer(owner), null).andExpect(jsonPath("$.status", is("CANCELLED"))).andExpect(jsonPath("$.items", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty()).andExpect(jsonPath("$.cancelledBy.name").isNotEmpty());
        // Y queda en la actividad del dueño.
        call(get("/api/b/" + b + "/activity"), bearer(owner), null).andExpect(jsonPath("$.items[?(@.action=='sale.cancel')].detail").isNotEmpty());
    }
}
