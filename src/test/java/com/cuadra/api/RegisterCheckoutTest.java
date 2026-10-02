package com.cuadra.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cuadra.api.tenancy.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Cobro en caja (ADR 0015): enviar a caja, la lista, cobrar desde otro teléfono, reserva, anular con motivo, aviso del cierre y el ajuste. */
class RegisterCheckoutTest extends ApiTestBase {

    private String url(UUID b, UUID sale) { return "/api/b/" + b + "/sales/" + sale; }

    private static String sent(String items, String note) {
        return SaleTest.sale("PARKED", items, "", "\"sendToRegister\":true" + (note == null ? "" : ",\"label\":\"" + note + "\""));
    }

    private void turnOn(String owner, UUID b, boolean on) throws Exception {
        call(put("/api/b/" + b), bearer(owner), "{\"registerCheckout\":" + on + "}").andExpect(status().isOk()).andExpect(jsonPath("$.registerCheckout", is(on)));
    }

    @Test
    void aWaiterSendsATicketAndAnotherMemberChargesIt() throws Exception {
        String owner = login("rc-a");
        UUID b = createBusiness(owner, "Cobro en caja A");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        UUID ana = createPinMember(owner, b, "Ana", "CASHIER");
        String phoneK = linkDevice(owner, b);
        String phoneA = linkDevice(owner, b);
        UUID id = UUID.randomUUID();
        String items = SaleTest.item("Cerveza", 6000, 2000) + "," + SaleTest.item("Nacatamal", 8000, 1000);

        // Apagado por omisión: enviar a caja se rechaza (la interfaz ni lo ofrece).
        call(get("/api/b/" + b), bearer(owner), null).andExpect(jsonPath("$.registerCheckout", is(false)));
        asDevice(put(url(b, id)), phoneK, kevin, sent(items, "Mesa 4")).andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("COBRO_EN_CAJA_OFF")));
        turnOn(owner, b, true);

        asDevice(put(url(b, id)), phoneK, kevin, sent(items, "Mesa 4")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("PARKED"))).andExpect(jsonPath("$.label", is("Mesa 4"))).andExpect(jsonPath("$.sentToRegisterAt", notNullValue()))
                .andExpect(jsonPath("$.sentBy.name", is("Kevin"))).andExpect(jsonPath("$.pendingCheckout", is(true))).andExpect(jsonPath("$.totalMinor", is(20000)));
        // Una cuenta apartada común no está en la lista.
        asDevice(put(url(b, UUID.randomUUID())), phoneK, kevin, SaleTest.sale("PARKED", SaleTest.item("x", 100, 1000), "", "")).andExpect(status().isCreated());

        // Todos los teléfonos la ven (también otra cajera), y no es una venta.
        asDevice(get("/api/b/" + b + "/sales/register-queue"), phoneA, ana, null).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is(id.toString()))).andExpect(jsonPath("$[0].items", hasSize(2))).andExpect(jsonPath("$[0].createdBy.name", is("Kevin")));
        call(get("/api/b/" + b + "/sales/summary"), bearer(owner), null).andExpect(jsonPath("$.salesCount", is(0)));

        // Ana la abre para cobrar: queda reservada. Kevin ve quién la tiene.
        asDevice(post(url(b, id) + "/lock"), phoneA, ana, null).andExpect(status().isOk()).andExpect(jsonPath("$.lockedBy.name", is("Ana")));
        asDevice(post(url(b, id) + "/lock"), phoneK, kevin, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("SALE_LOCKED")))
                .andExpect(jsonPath("$.memberName", is("Ana")));
        asDevice(put(url(b, id)), phoneK, kevin, SaleTest.sale("COMPLETED", items, SaleTest.pay("CASH", 20000, ""), "\"fromStatus\":\"PARKED\""))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("SALE_LOCKED")));

        // Ana cobra en efectivo con vuelto: la tomó Kevin, la cobró Ana.
        asDevice(put(url(b, id)), phoneA, ana, SaleTest.sale("COMPLETED", items, SaleTest.pay("CASH", 20000, "\"tenderedMinor\":50000"), "\"fromStatus\":\"PARKED\",\"label\":\"Mesa 4\""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("COMPLETED"))).andExpect(jsonPath("$.createdBy.name", is("Kevin")))
                .andExpect(jsonPath("$.completedBy.name", is("Ana"))).andExpect(jsonPath("$.payments[0].changeMinor", is(30000)))
                .andExpect(jsonPath("$.sentBy.name", is("Kevin"))).andExpect(jsonPath("$.pendingCheckout", is(false))).andExpect(jsonPath("$.lockedBy", nullValue()));
        asDevice(get("/api/b/" + b + "/sales/register-queue"), phoneA, ana, null).andExpect(jsonPath("$", hasSize(0)));
        // Ana la ve en sus ventas (la cobró) y Kevin también (la tomó).
        asDevice(get(url(b, id)), phoneK, kevin, null).andExpect(status().isOk());
        asDevice(get(url(b, id)), phoneA, ana, null).andExpect(status().isOk());
    }

    @Test
    void cancellingAPendingTicketNeedsAReasonAndIsAudited() throws Exception {
        String owner = login("rc-b");
        UUID b = createBusiness(owner, "Cobro en caja B");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        String phone = linkDevice(owner, b);
        turnOn(owner, b, true);
        UUID id = UUID.randomUUID();
        asDevice(put(url(b, id)), phone, kevin, sent(SaleTest.item("Café", 3000, 1000), "Mesa 2")).andExpect(status().isCreated());

        asDevice(post(url(b, id) + "/cancel"), phone, kevin, "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("REASON_REQUIRED")));
        asDevice(post(url(b, id) + "/cancel"), phone, kevin, "{\"reason\":\"abc\"}").andExpect(status().isBadRequest());
        asDevice(post(url(b, id) + "/cancel"), phone, kevin, "{\"reason\":\"Se fueron sin pedir\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CANCELLED"))).andExpect(jsonPath("$.cancelReason", is("Se fueron sin pedir")));
        long audited = baseJdbc.sql("SELECT count(*) FROM audit_log WHERE business_id = :b AND action = 'sale.register_cancel' AND entity_id = :id")
                .param("b", b).param("id", id.toString()).query(Long.class).single();
        assertEquals(1, audited);
        call(get("/api/b/" + b + "/sales/register-queue"), bearer(owner), null).andExpect(jsonPath("$", hasSize(0)));
        // No es una venta: no cuenta como venta eliminada.
        call(get("/api/b/" + b + "/sales/summary"), bearer(owner), null).andExpect(jsonPath("$.cancelledCount", is(0)));
        call(get("/api/b/" + b + "/reports/sales"), bearer(owner), null).andExpect(jsonPath("$.sales.cancelledCount", is(0)));
        // Una cuenta apartada común se sigue descartando sin motivo.
        UUID plain = UUID.randomUUID();
        asDevice(put(url(b, plain)), phone, kevin, SaleTest.sale("PARKED", SaleTest.item("x", 100, 1000), "", "")).andExpect(status().isCreated());
        asDevice(post(url(b, plain) + "/cancel"), phone, kevin, "{}").andExpect(status().isOk());
    }

    @Test
    void addingProductsKeepsItPendingAndResendingUpdatesWhoSentIt() throws Exception {
        String owner = login("rc-c");
        UUID b = createBusiness(owner, "Cobro en caja C");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        UUID ana = createPinMember(owner, b, "Ana", "CASHIER");
        String phone = linkDevice(owner, b);
        turnOn(owner, b, true);
        UUID id = UUID.randomUUID();
        asDevice(put(url(b, id)), phone, kevin, sent(SaleTest.item("Café", 3000, 1000), "Mesa 2")).andExpect(status().isCreated());
        // Apartada otra vez sin decir nada (versión vieja, o se retomó y se apartó): sigue por cobrar en caja.
        asDevice(put(url(b, id)), phone, kevin, SaleTest.sale("PARKED", SaleTest.item("Café", 3000, 2000), "", "\"label\":\"Mesa 2\""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pendingCheckout", is(true))).andExpect(jsonPath("$.sentBy.name", is("Kevin")));
        asDevice(put(url(b, id)), phone, ana, sent(SaleTest.item("Café", 3000, 3000), "Mesa 2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sentBy.name", is("Ana"))).andExpect(jsonPath("$.totalMinor", is(9000)));
        // Explícitamente de vuelta a cuenta común.
        asDevice(put(url(b, id)), phone, ana, SaleTest.sale("PARKED", SaleTest.item("Café", 3000, 3000), "", "\"label\":\"Mesa 2\",\"sendToRegister\":false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pendingCheckout", is(false))).andExpect(jsonPath("$.sentToRegisterAt", nullValue()));
    }

    @Test
    void offlineSendsGoThroughTheOutboxAndWithTheSettingOffTheTicketIsKeptAsParked() throws Exception {
        String owner = login("rc-d");
        UUID b = createBusiness(owner, "Cobro en caja D");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        String phone = linkDevice(owner, b);
        UUID off = UUID.randomUUID();
        String push = "{\"ops\":[{\"opId\":\"" + UUID.randomUUID() + "\",\"kind\":\"SALE_UPSERT\",\"entityId\":\"" + off + "\",\"payload\":" + sent(SaleTest.item("Té", 2000, 1000), "Mesa 9") + "}]}";
        asDevice(post("/api/b/" + b + "/sync/push"), phone, kevin, push).andExpect(jsonPath("$.results[0].status", is("APPLIED")));
        // Con el ajuste apagado no se pierde: queda como cuenta apartada común con su nota.
        call(get(url(b, off)), bearer(owner), null).andExpect(jsonPath("$.status", is("PARKED"))).andExpect(jsonPath("$.label", is("Mesa 9")))
                .andExpect(jsonPath("$.pendingCheckout", is(false)));

        turnOn(owner, b, true);
        UUID on = UUID.randomUUID();
        push = "{\"ops\":[{\"opId\":\"" + UUID.randomUUID() + "\",\"kind\":\"SALE_UPSERT\",\"entityId\":\"" + on + "\",\"payload\":" + sent(SaleTest.item("Té", 2000, 1000), "Mesa 10") + "}]}";
        asDevice(post("/api/b/" + b + "/sync/push"), phone, kevin, push).andExpect(jsonPath("$.results[0].status", is("APPLIED")));
        // Llega a los demás teléfonos por la descarga.
        String other = linkDevice(owner, b);
        String body = asDevice(get("/api/b/" + b + "/sync/pull?since=0"), other, kevin, null).andReturn().getResponse().getContentAsString();
        java.util.List<Object> rows = com.jayway.jsonpath.JsonPath.read(body, "$.changes[?(@.type=='sale' && @.data.id=='" + on + "')].data.sentToRegisterAt");
        assertEquals(1, rows.size());
    }

    @Test
    void theDailyCloseWarnsAboutTicketsStillPendingAtTheRegister() throws Exception {
        String owner = login("rc-e");
        UUID b = createBusiness(owner, "Cobro en caja E");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        String phone = linkDevice(owner, b);
        turnOn(owner, b, true);
        asDevice(put(url(b, UUID.randomUUID())), phone, kevin, sent(SaleTest.item("Café", 3000, 1000), "Mesa 1")).andExpect(status().isCreated());
        asDevice(put(url(b, UUID.randomUUID())), phone, kevin, sent(SaleTest.item("Pollo", 12000, 1000), "Mesa 3")).andExpect(status().isCreated());
        UUID charged = UUID.randomUUID();
        asDevice(put(url(b, charged)), phone, kevin, sent(SaleTest.item("Agua", 1000, 1000), "Mesa 5")).andExpect(status().isCreated());
        asDevice(put(url(b, charged)), phone, kevin, SaleTest.sale("COMPLETED", SaleTest.item("Agua", 1000, 1000), SaleTest.pay("CASH", 1000, ""), "\"fromStatus\":\"PARKED\""))
                .andExpect(status().isOk());

        call(get("/api/b/" + b + "/reports/daily-close"), bearer(owner), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].pendingCheckoutCount", is(2))).andExpect(jsonPath("$.days[0].pendingCheckoutMinor", is(15000)))
                .andExpect(jsonPath("$.days[0].salesCount", is(1))).andExpect(jsonPath("$.days[0].salesMinor", is(1000)));
    }

    @Test
    void rowLevelSecurityHidesAnotherBusinessQueue() throws Exception {
        String ownerA = login("rc-f");
        String ownerB = login("rc-g");
        UUID a = createBusiness(ownerA, "Cobro en caja F");
        UUID b = createBusiness(ownerB, "Cobro en caja G");
        turnOn(ownerB, b, true);
        UUID id = UUID.randomUUID();
        call(put(url(b, id)), bearer(ownerB), sent(SaleTest.item("Café", 3000, 1000), "Mesa 1")).andExpect(status().isCreated());
        call(get("/api/b/" + a + "/sales/register-queue"), bearer(ownerA), null).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        call(get("/api/b/" + b + "/sales/register-queue"), bearer(ownerA), null).andExpect(status().isNotFound());
        // Con el contexto de A la cuenta de B no existe en la base (RLS con el rol de las peticiones).
        long seen = TenantContext.call(a, () -> baseJdbc.sql("SELECT count(*) FROM sale WHERE id = :id AND sent_to_register_at IS NOT NULL").param("id", id).query(Long.class).single());
        assertEquals(0, seen);
        // Y A no puede cobrarla ni anularla por su ruta.
        call(post("/api/b/" + a + "/sales/" + id + "/cancel"), bearer(ownerA), "{\"reason\":\"no es mía\"}").andExpect(status().isNotFound());
    }

    @Test
    void turningOffNeedsConfirmationAndCancelsPendingTickets() throws Exception {
        String owner = login("rc-h");
        UUID b = createBusiness(owner, "Cobro en caja H");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        String phone = linkDevice(owner, b);
        // Sin cuentas pendientes: se apaga sin preguntar.
        turnOn(owner, b, true);
        turnOn(owner, b, false);
        turnOn(owner, b, true);
        UUID one = UUID.randomUUID();
        UUID two = UUID.randomUUID();
        asDevice(put(url(b, one)), phone, kevin, sent(SaleTest.item("Café", 3000, 1000), "Mesa 1")).andExpect(status().isCreated());
        asDevice(put(url(b, two)), phone, kevin, sent(SaleTest.item("Pollo", 12000, 1000), "Mesa 2")).andExpect(status().isCreated());

        call(put("/api/b/" + b), bearer(owner), "{\"registerCheckout\":false}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("REGISTER_QUEUE_NOT_EMPTY"))).andExpect(jsonPath("$.count", is(2))).andExpect(jsonPath("$.totalMinor", is(15000)));
        call(get("/api/b/" + b), bearer(owner), null).andExpect(jsonPath("$.registerCheckout", is(true)));
        call(get("/api/b/" + b + "/sales/register-queue"), bearer(owner), null).andExpect(jsonPath("$", hasSize(2)));

        // Una cuenta que alguien está cobrando bloquea el apagado.
        asDevice(post(url(b, one) + "/lock"), phone, kevin, null).andExpect(status().isOk());
        call(put("/api/b/" + b), bearer(owner), "{\"registerCheckout\":false,\"confirmDiscardPending\":true}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("REGISTER_QUEUE_BUSY")));
        asDevice(delete(url(b, one) + "/lock"), phone, kevin, null).andExpect(status().isNoContent());

        call(put("/api/b/" + b), bearer(owner), "{\"registerCheckout\":false,\"confirmDiscardPending\":true}").andExpect(status().isOk())
                .andExpect(jsonPath("$.registerCheckout", is(false)));
        call(get("/api/b/" + b + "/sales/register-queue"), bearer(owner), null).andExpect(jsonPath("$", hasSize(0)));
        call(get(url(b, one)), bearer(owner), null).andExpect(jsonPath("$.status", is("CANCELLED"))).andExpect(jsonPath("$.cancelReason", is("Cobro en caja desactivado")));
        long audited = baseJdbc.sql("SELECT count(*) FROM audit_log WHERE business_id = :b AND action = 'sale.register_cancel'").param("b", b).query(Long.class).single();
        assertEquals(2, audited);
    }
}
