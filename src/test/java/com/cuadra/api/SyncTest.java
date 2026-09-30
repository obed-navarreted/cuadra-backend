package com.cuadra.api;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SyncTest extends ApiTestBase {

    private static String op(UUID opId, String kind, UUID entityId, String payload) {
        return "{\"opId\":\"" + opId + "\",\"kind\":\"" + kind + "\",\"entityId\":\"" + entityId + "\",\"payload\":" + payload + "}";
    }

    private static String push(String... ops) {
        return "{\"ops\":[" + String.join(",", ops) + "]}";
    }

    private static final String PRODUCT = "{\"name\":\"Cuajada\",\"priceMinor\":2500,\"isQuick\":true}";

    private static String completedSale(long price) {
        return SaleTest.sale("COMPLETED", SaleTest.item("Cuajada", price, 1000), SaleTest.pay("CASH", price, ""), "\"completedAt\":\"2026-09-20T15:00:00Z\"");
    }

    @Test
    void aBatchIsAppliedOnceEvenIfThePhoneRepeatsIt() throws Exception {
        String owner = login("syna");
        UUID b = createBusiness(owner, "Sync A");
        UUID member = createPinMember(owner, b, "Kevin", "CASHIER");
        String device = linkDevice(owner, b);
        UUID product = UUID.randomUUID();
        UUID sale = UUID.randomUUID();
        UUID opProduct = UUID.randomUUID();
        UUID opSale = UUID.randomUUID();
        String batch = push(op(opProduct, "PRODUCT_UPSERT", product, PRODUCT), op(opSale, "SALE_UPSERT", sale, completedSale(2500)));

        asDevice(post("/api/b/" + b + "/sync/push"), device, member, batch).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status", is("APPLIED"))).andExpect(jsonPath("$.results[1].status", is("APPLIED")))
                .andExpect(jsonPath("$.results[0].rev", notNullValue()));
        // Timeout después de que el servidor guardó: el teléfono reenvía todo y no se duplica nada.
        asDevice(post("/api/b/" + b + "/sync/push"), device, member, batch).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status", is("DUPLICATE"))).andExpect(jsonPath("$.results[1].status", is("DUPLICATE")));

        call(get("/api/b/" + b + "/products"), bearer(owner), null).andExpect(jsonPath("$.total", is(1)));
        call(get("/api/b/" + b + "/sales"), bearer(owner), null).andExpect(jsonPath("$.total", is(1)));
        // El teléfono queda marcado como sincronizado.
        call(get("/api/b/" + b + "/devices"), bearer(owner), null).andExpect(jsonPath("$[0].lastSyncAt", notNullValue()));
    }

    @Test
    void oneRejectedOperationDoesNotBlockTheRest() throws Exception {
        String owner = login("synb");
        UUID b = createBusiness(owner, "Sync B");
        UUID member = createPinMember(owner, b, "Kevin", "CASHIER");
        String device = linkDevice(owner, b);
        UUID badOp = UUID.randomUUID();
        UUID goodOp = UUID.randomUUID();
        String bad = op(badOp, "SALE_UPSERT", UUID.randomUUID(), SaleTest.sale("COMPLETED", SaleTest.item("x", 1000, 1000), SaleTest.pay("CASH", 5, ""), ""));
        String good = op(goodOp, "SALE_UPSERT", UUID.randomUUID(), completedSale(1000));
        String unknown = op(UUID.randomUUID(), "TELEPORT", UUID.randomUUID(), "{}");

        asDevice(post("/api/b/" + b + "/sync/push"), device, member, push(bad, good, unknown)).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status", is("REJECTED"))).andExpect(jsonPath("$.results[0].code", is("PAYMENT_MISMATCH")))
                .andExpect(jsonPath("$.results[1].status", is("APPLIED"))).andExpect(jsonPath("$.results[2].code", is("UNKNOWN_KIND")));
        // Reenviar la rechazada devuelve el mismo veredicto en vez de reintentar sin fin.
        asDevice(post("/api/b/" + b + "/sync/push"), device, member, push(bad)).andExpect(jsonPath("$.results[0].status", is("DUPLICATE")))
                .andExpect(jsonPath("$.results[0].code", is("PAYMENT_MISMATCH")));
    }

    @Test
    void anOldVersionArrivingLateIsMarkedStaleNotAnError() throws Exception {
        String owner = login("sync");
        UUID b = createBusiness(owner, "Sync C");
        UUID member = createPinMember(owner, b, "Kevin", "CASHIER");
        String device = linkDevice(owner, b);
        UUID sale = UUID.randomUUID();
        asDevice(post("/api/b/" + b + "/sync/push"), device, member, push(op(UUID.randomUUID(), "SALE_UPSERT", sale, completedSale(1000)))).andExpect(status().isOk());
        String old = SaleTest.sale("PARKED", SaleTest.item("Cuajada", 1000, 1000), "", "");
        asDevice(post("/api/b/" + b + "/sync/push"), device, member, push(op(UUID.randomUUID(), "SALE_UPSERT", sale, old)))
                .andExpect(jsonPath("$.results[0].status", is("STALE")));
        // (El teléfono se vinculó hoy: la fecha vieja de la venta es imposible y se tomó la del servidor. Se la envejece para la prueba.)
        baseJdbc.sql("UPDATE sale SET completed_at = now() - interval '1 hour' WHERE id = :s").param("s", sale).update();
        asDevice(post("/api/b/" + b + "/sync/push"), device, member, push(op(UUID.randomUUID(), "SALE_CANCEL", sale, "{\"reason\":\"me equivoqué\"}")))
                .andExpect(jsonPath("$.results[0].code", is("UNDO_NOT_ALLOWED")));   // un cajero no puede cancelar una cobrada (salvo su última, en los primeros minutos)
    }

    @Test
    void pullReturnsChangesInOrderWithACursorAndPaging() throws Exception {
        String owner = login("synd");
        UUID b = createBusiness(owner, "Sync D");
        UUID member = createPinMember(owner, b, "Kevin", "CASHIER");
        String device = linkDevice(owner, b);
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        call(post("/api/b/" + b + "/sync/push"), bearer(owner), push(op(UUID.randomUUID(), "PRODUCT_UPSERT", p1, PRODUCT),
                op(UUID.randomUUID(), "PRODUCT_UPSERT", p2, "{\"name\":\"Crema\",\"priceMinor\":4500}"))).andExpect(status().isOk());

        // Primer llenado: negocio, miembros, caja y productos.
        String all = asDevice(get("/api/b/" + b + "/sync/pull?since=0&limit=200"), device, member, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.hasMore", is(false))).andExpect(jsonPath("$.changes[*].type", hasItem("business")))
                .andExpect(jsonPath("$.changes[*].type", hasItem("member"))).andExpect(jsonPath("$.changes[*].type", hasItem("cash_register")))
                .andExpect(jsonPath("$.changes[?(@.type=='product')]", hasSize(2))).andReturn().getResponse().getContentAsString();
        long cursor = ((Number) JsonPath.read(all, "$.cursor")).longValue();
        // Sin cambios nuevos: nada y el cursor no se mueve.
        asDevice(get("/api/b/" + b + "/sync/pull?since=" + cursor), device, member, null).andExpect(jsonPath("$.changes", hasSize(0)))
                .andExpect(jsonPath("$.cursor", is((int) cursor)));
        // Un cambio de precio aparece una sola vez, con el precio nuevo.
        call(post("/api/b/" + b + "/sync/push"), bearer(owner), push(op(UUID.randomUUID(), "PRODUCT_UPSERT", p1, "{\"name\":\"Cuajada\",\"priceMinor\":2800,\"isQuick\":true}")));
        asDevice(get("/api/b/" + b + "/sync/pull?since=" + cursor), device, member, null).andExpect(jsonPath("$.changes", hasSize(1)))
                .andExpect(jsonPath("$.changes[0].data.priceMinor", is(2800)));
        // Paginado: de dos en dos, sin saltos ni repetidos.
        String page1 = asDevice(get("/api/b/" + b + "/sync/pull?since=0&limit=2"), device, member, null).andExpect(jsonPath("$.changes", hasSize(2)))
                .andExpect(jsonPath("$.hasMore", is(true))).andReturn().getResponse().getContentAsString();
        long c1 = ((Number) JsonPath.read(page1, "$.cursor")).longValue();
        String page2 = asDevice(get("/api/b/" + b + "/sync/pull?since=" + c1 + "&limit=2"), device, member, null)
                .andExpect(jsonPath("$.changes", hasSize(2))).andReturn().getResponse().getContentAsString();
        long lastOfPage1 = ((Number) JsonPath.read(page1, "$.changes[1].rev")).longValue();
        long firstOfPage2 = ((Number) JsonPath.read(page2, "$.changes[0].rev")).longValue();
        org.junit.jupiter.api.Assertions.assertTrue(firstOfPage2 > lastOfPage1, "la segunda página sigue a la primera sin repetir");
    }

    @Test
    void pullHidesOtherCashiersSalesAndOpenTicketsButSharesParkedOnes() throws Exception {
        String owner = login("syne");
        UUID b = createBusiness(owner, "Sync E");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        UUID luisa = createPinMember(owner, b, "Luisa", "CASHIER");
        String device = linkDevice(owner, b);
        UUID hers = UUID.randomUUID();
        UUID parked = UUID.randomUUID();
        UUID open = UUID.randomUUID();
        String it = SaleTest.item("x", 1000, 1000);
        asDevice(post("/api/b/" + b + "/sync/push"), device, luisa, push(
                op(UUID.randomUUID(), "SALE_UPSERT", hers, completedSale(1000)),
                op(UUID.randomUUID(), "SALE_UPSERT", parked, SaleTest.sale("PARKED", it, "", "")),
                op(UUID.randomUUID(), "SALE_UPSERT", open, SaleTest.sale("OPEN", it, "", "")))).andExpect(status().isOk());

        asDevice(get("/api/b/" + b + "/sync/pull?since=0"), device, kevin, null)
                .andExpect(jsonPath("$.changes[?(@.type=='sale')].data.id", contains(parked.toString())));
        asDevice(get("/api/b/" + b + "/sync/pull?since=0"), device, luisa, null)
                .andExpect(jsonPath("$.changes[?(@.type=='sale')]", hasSize(3)));
        call(get("/api/b/" + b + "/sync/pull?since=0"), bearer(owner), null)
                .andExpect(jsonPath("$.changes[?(@.type=='sale')]", hasSize(2)));   // las abiertas de otros no salen
    }

    @Test
    void membersWithPinHashesOnlyReachDevicesThroughPull() throws Exception {
        String owner = login("synf");
        UUID b = createBusiness(owner, "Sync F");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        String device = linkDevice(owner, b);
        asDevice(get("/api/b/" + b + "/sync/pull?since=0"), device, kevin, null)
                .andExpect(jsonPath("$.changes[?(@.type=='member' && @.data.displayName=='Kevin')].data.pinHash[0]", notNullValue()));
        call(get("/api/b/" + b + "/sync/pull?since=0"), bearer(owner), null)
                .andExpect(jsonPath("$.changes[?(@.type=='member' && @.data.displayName=='Kevin')].data.pinHash[0]").doesNotExist());
    }

    @Test
    void syncIsIsolatedBetweenBusinesses() throws Exception {
        String ownerA = login("syng");
        String ownerB = login("synh");
        UUID a = createBusiness(ownerA, "Sync G");
        UUID b = createBusiness(ownerB, "Sync H");
        call(get("/api/b/" + a + "/sync/pull?since=0"), bearer(ownerB), null).andExpect(status().isNotFound());
        call(post("/api/b/" + a + "/sync/push"), bearer(ownerB), push()).andExpect(status().isNotFound());
        // El id de operación es global: reusar el de otro negocio no aplica nada ni filtra su resultado.
        UUID opId = UUID.randomUUID();
        call(post("/api/b/" + a + "/sync/push"), bearer(ownerA), push(op(opId, "PRODUCT_UPSERT", UUID.randomUUID(), PRODUCT))).andExpect(jsonPath("$.results[0].status", is("APPLIED")));
        call(post("/api/b/" + b + "/sync/push"), bearer(ownerB), push(op(opId, "PRODUCT_UPSERT", UUID.randomUUID(), PRODUCT)))
                .andExpect(jsonPath("$.results[0].code", is("OP_ID_TAKEN")));
        call(get("/api/b/" + b + "/products"), bearer(ownerB), null).andExpect(jsonPath("$.total", is(0)));
    }

    @Test
    void aTransientRejectionIsNotRememberedSoTheSameOperationCanSucceedLater() throws Exception {
        String owner = login("synl");
        UUID b = createBusiness(owner, "Sync L");
        UUID member = createPinMember(owner, b, "Kevin", "CASHIER");
        String phoneA = linkDevice(owner, b);
        String phoneB = linkDevice(owner, b);
        UUID sale = UUID.randomUUID();
        String parked = SaleTest.sale("PARKED", SaleTest.item("x", 1000, 1000), "", "");
        asDevice(post("/api/b/" + b + "/sync/push"), phoneA, member, push(op(UUID.randomUUID(), "SALE_UPSERT", sale, parked))).andExpect(status().isOk());
        asDevice(post("/api/b/" + b + "/sales/" + sale + "/lock"), phoneA, member, null).andExpect(status().isOk());

        UUID opId = UUID.randomUUID();
        String complete = op(opId, "SALE_UPSERT", sale, completedSale(1000));
        asDevice(post("/api/b/" + b + "/sync/push"), phoneB, member, push(complete))
                .andExpect(jsonPath("$.results[0].status", is("REJECTED"))).andExpect(jsonPath("$.results[0].code", is("SALE_LOCKED")));
        // El que la tenía la suelta; la MISMA operación (mismo opId) ahora se aplica en vez de devolver el rechazo viejo.
        asDevice(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/b/" + b + "/sales/" + sale + "/lock"), phoneA, member, null)
                .andExpect(status().isNoContent());
        asDevice(post("/api/b/" + b + "/sync/push"), phoneB, member, push(complete)).andExpect(jsonPath("$.results[0].status", is("APPLIED")));
        call(get("/api/b/" + b + "/sales/" + sale), bearer(owner), null).andExpect(jsonPath("$.status", is("COMPLETED")));
    }

    @Test
    void theLedgerSyncsBothWaysWithoutDuplicatingPayments() throws Exception {
        String owner = login("synm");
        UUID b = createBusiness(owner, "Sync M");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        String device = linkDevice(owner, b);
        UUID customer = UUID.randomUUID();
        UUID credit = UUID.randomUUID();
        UUID payment = UUID.randomUUID();
        UUID payOp = UUID.randomUUID();
        String batch = push(
                op(UUID.randomUUID(), "CUSTOMER_UPSERT", customer, "{\"name\":\"Marta\",\"phone\":\"8812 4455\"}"),
                op(UUID.randomUUID(), "CREDIT_UPSERT", credit, "{\"debtorLabel\":\"Marta\",\"customerId\":\"" + customer + "\",\"amountMinor\":5000}"),
                op(payOp, "CREDIT_PAYMENT", payment, "{\"creditId\":\"" + credit + "\",\"amountMinor\":2000,\"method\":\"CASH\"}"));
        asDevice(post("/api/b/" + b + "/sync/push"), device, kevin, batch).andExpect(jsonPath("$.results[0].status", is("APPLIED")))
                .andExpect(jsonPath("$.results[1].status", is("APPLIED"))).andExpect(jsonPath("$.results[2].status", is("APPLIED")));
        // El teléfono reenvía el lote entero (timeout tras guardar): el abono no se descuenta dos veces.
        asDevice(post("/api/b/" + b + "/sync/push"), device, kevin, batch).andExpect(jsonPath("$.results[2].status", is("DUPLICATE")));
        call(get("/api/b/" + b + "/credits/" + credit), bearer(owner), null).andExpect(jsonPath("$.balanceMinor", is(3000)));

        // El teléfono baja el estado completo: cliente con su saldo, fiado y abono.
        asDevice(get("/api/b/" + b + "/sync/pull?since=0"), device, kevin, null)
                .andExpect(jsonPath("$.changes[?(@.type=='customer')].data.balanceMinor", hasItem(3000)))
                .andExpect(jsonPath("$.changes[?(@.type=='credit')].data.balanceMinor", hasItem(3000)))
                .andExpect(jsonPath("$.changes[?(@.type=='credit_payment')].data.amountMinor", hasItem(2000)));
        // Un cajero no puede condonar ni siquiera por la cola de sincronización.
        asDevice(post("/api/b/" + b + "/sync/push"), device, kevin, push(op(UUID.randomUUID(), "CREDIT_WRITE_OFF", credit, "{\"reason\":\"x\"}")))
                .andExpect(jsonPath("$.results[0].status", is("REJECTED"))).andExpect(jsonPath("$.results[0].code", is("FORBIDDEN")));
        // Anular el abono desde el dueño se refleja en la siguiente descarga.
        long cursor = ((Number) JsonPath.read(asDevice(get("/api/b/" + b + "/sync/pull?since=0"), device, kevin, null).andReturn().getResponse().getContentAsString(), "$.cursor")).longValue();
        call(post("/api/b/" + b + "/sync/push"), bearer(owner), push(op(UUID.randomUUID(), "CREDIT_PAYMENT_VOID", payment, "{\"reason\":\"prueba\"}"))).andExpect(jsonPath("$.results[0].status", is("APPLIED")));
        asDevice(get("/api/b/" + b + "/sync/pull?since=" + cursor), device, kevin, null)
                .andExpect(jsonPath("$.changes[?(@.type=='credit')].data.balanceMinor", hasItem(5000)))
                .andExpect(jsonPath("$.changes[?(@.type=='credit_payment')].data.voided", hasItem(true)));
    }

    @Test
    void cashOperationsSyncAndAClosingBlockedByAnotherPhoneIsRetriedNotRejectedForever() throws Exception {
        String owner = login("synn");
        UUID b = createBusiness(owner, "Sync N");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        String phoneA = linkDevice(owner, b);
        String phoneB = linkDevice(owner, b);
        UUID shift = UUID.randomUUID();
        UUID expense = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        asDevice(post("/api/b/" + b + "/sync/push"), phoneA, kevin, push(
                op(UUID.randomUUID(), "SHIFT_OPEN", shift, "{\"openingFloatMinor\":100000}"),
                op(UUID.randomUUID(), "EXPENSE_UPSERT", expense, "{\"amountMinor\":15000,\"source\":\"CASH_DRAWER\",\"description\":\"Hielo\"}"),
                op(UUID.randomUUID(), "CASH_MOVEMENT_UPSERT", UUID.randomUUID(), "{\"kind\":\"DEPOSIT\",\"amountMinor\":50000}")))
                .andExpect(jsonPath("$.results[0].status", is("APPLIED"))).andExpect(jsonPath("$.results[1].status", is("APPLIED"))).andExpect(jsonPath("$.results[2].status", is("APPLIED")));

        // El otro teléfono también abrió la misma caja sin conexión: su turno se descarta como viejo (STALE), no como error.
        asDevice(post("/api/b/" + b + "/sync/push"), phoneB, kevin, push(op(UUID.randomUUID(), "SHIFT_OPEN", UUID.randomUUID(), "{\"openingFloatMinor\":5}")))
                .andExpect(jsonPath("$.results[0].status", is("STALE")));
        // Un cajero no puede sacar dinero ni por la cola de sincronización.
        asDevice(post("/api/b/" + b + "/sync/push"), phoneA, kevin, push(op(UUID.randomUUID(), "CASH_MOVEMENT_UPSERT", UUID.randomUUID(), "{\"kind\":\"WITHDRAWAL\",\"amountMinor\":1000}")))
                .andExpect(jsonPath("$.results[0].code", is("FORBIDDEN")));
        call(post("/api/b/" + b + "/sync/push"), bearer(owner), push(op(UUID.randomUUID(), "EXPENSE_CATEGORY_UPSERT", category, "{\"name\":\"Publicidad\"}"))).andExpect(jsonPath("$.results[0].status", is("APPLIED")));

        // Cerrar mientras el otro teléfono tiene operaciones sin enviar: rechazo PASAJERO. La misma operación (mismo opId) sale bien después.
        jdbc().sql("UPDATE device SET pending_ops = 2 WHERE id = (SELECT id FROM device WHERE business_id = :b ORDER BY linked_at DESC LIMIT 1)").param("b", b).update();
        UUID closeOp = UUID.randomUUID();
        String close = push(op(closeOp, "SHIFT_CLOSE", shift, "{\"countedMinor\":165000}"));
        asDevice(post("/api/b/" + b + "/sync/push"), phoneA, kevin, close).andExpect(jsonPath("$.results[0].status", is("REJECTED"))).andExpect(jsonPath("$.results[0].code", is("DEVICES_PENDING")));
        jdbc().sql("UPDATE device SET pending_ops = 0 WHERE business_id = :b").param("b", b).update();
        asDevice(post("/api/b/" + b + "/sync/push"), phoneA, kevin, close).andExpect(jsonPath("$.results[0].status", is("APPLIED")));
        call(get("/api/b/" + b + "/shifts/" + shift), bearer(owner), null).andExpect(jsonPath("$.status", is("CLOSED"))).andExpect(jsonPath("$.expectedAtCloseMinor", is(135000)))
                .andExpect(jsonPath("$.differenceMinor", is(30000)));   // fondo 1,000 − gasto 150 + entrada 500 = 1,350 esperado; se contó 1,650

        // Lo que baja el teléfono: turno, gasto, entrada y categorías; el cajero no ve el resultado del cierre de otro.
        asDevice(get("/api/b/" + b + "/sync/pull?since=0"), phoneB, kevin, null)
                .andExpect(jsonPath("$.changes[?(@.type=='shift')]", hasSize(1))).andExpect(jsonPath("$.changes[?(@.type=='expense')]", hasSize(1)))
                .andExpect(jsonPath("$.changes[?(@.type=='cash_movement')]", hasSize(1))).andExpect(jsonPath("$.changes[?(@.type=='expense_category')]", hasSize(9)));
    }

    private org.springframework.jdbc.core.simple.JdbcClient jdbc() { return jdbc; }

    @org.springframework.beans.factory.annotation.Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;
}
