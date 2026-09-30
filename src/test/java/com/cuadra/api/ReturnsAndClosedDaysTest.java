package com.cuadra.api;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cuadra.api.business.BusinessPurgeService;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Devoluciones, "anular mi última venta", días cerrados que no cambian, lo que llega después de dar de baja a alguien, relojes imposibles, moneda fija
 * con actividad y borrado definitivo de negocios (docs/adr/0013).
 */
class ReturnsAndClosedDaysTest extends ApiTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired BusinessPurgeService purge;

    private static String base(UUID b) { return "/api/b/" + b; }

    private static String item(UUID id, UUID product, String name, long price, long qtyMilli) {
        return "{\"id\":\"" + id + "\"" + (product == null ? "" : ",\"productId\":\"" + product + "\"") + ",\"name\":\"" + name + "\",\"unitPriceMinor\":" + price
                + ",\"quantityMilli\":" + qtyMilli + ",\"unitCostMinor\":" + (price / 2) + "}";
    }

    private static String sale(String items, String payments, Instant at, long discount) {
        return "{\"status\":\"COMPLETED\",\"items\":[" + items + "],\"payments\":[" + payments + "]" + (at == null ? "" : ",\"completedAt\":\"" + at + "\"")
                + (discount > 0 ? ",\"discountMinor\":" + discount : "") + "}";
    }

    private UUID saleAsOwner(String owner, UUID b, String items, String payments, Instant at) throws Exception {
        UUID id = UUID.randomUUID();
        call(put(base(b) + "/sales/" + id), bearer(owner), sale(items, payments, at, 0)).andExpect(status().isCreated());
        return id;
    }

    private static String ret(UUID saleItem, long qty, String reason, String method, Instant at) {
        return "{\"items\":[{\"saleItemId\":\"" + saleItem + "\",\"quantityMilli\":" + qty + "}],\"reason\":\"" + reason + "\",\"refundMethod\":\"" + method + "\""
                + (at == null ? "" : ",\"occurredAt\":\"" + at + "\"") + "}";
    }

    private static String op(UUID opId, String kind, UUID entity, String payload, UUID member, Instant createdAt) {
        return "{\"opId\":\"" + opId + "\",\"kind\":\"" + kind + "\",\"entityId\":\"" + entity + "\",\"payload\":" + payload
                + (member == null ? "" : ",\"memberId\":\"" + member + "\"") + (createdAt == null ? "" : ",\"createdAt\":\"" + createdAt + "\"") + "}";
    }

    private static String push(String... ops) { return "{\"ops\":[" + String.join(",", ops) + "]}"; }

    private UUID trackedProduct(String owner, UUID b, long stockMilli) throws Exception {
        UUID p = UUID.randomUUID();
        call(put(base(b) + "/products/" + p), bearer(owner), "{\"name\":\"Queso\",\"priceMinor\":10000,\"trackStock\":true}").andExpect(status().isCreated());
        call(put(base(b) + "/stock-movements/" + UUID.randomUUID()), bearer(owner), "{\"productId\":\"" + p + "\",\"kind\":\"INITIAL\",\"countedMilli\":" + stockMilli + "}")
                .andExpect(status().is2xxSuccessful());
        return p;
    }

    private long stockOf(UUID product) {
        return jdbc.sql("SELECT stock_milli FROM product WHERE id = :p").param("p", product).query(Long.class).single();
    }

    // ---------- devoluciones ----------

    @Test
    void aPartialReturnRefundsCashGivesStockBackAndCannotExceedWhatWasSold() throws Exception {
        String owner = login("ret-a");
        UUID b = createBusiness(owner, "Devoluciones A");
        UUID queso = trackedProduct(owner, b, 10_000);
        UUID lineQueso = UUID.randomUUID();
        UUID lineCrema = UUID.randomUUID();
        // 3 quesos a C$100 y 1 crema a C$50 = C$350, con C$35 de descuento de la cuenta (10 %): cobrado C$315 en efectivo.
        UUID s = UUID.randomUUID();
        call(put(base(b) + "/sales/" + s), bearer(owner), sale(item(lineQueso, queso, "Queso", 10000, 3000) + "," + item(lineCrema, null, "Crema", 5000, 1000),
                SaleTest.pay("CASH", 31500, ""), null, 3500)).andExpect(status().isCreated());
        assertEquals(7000, stockOf(queso));

        UUID r1 = UUID.randomUUID();
        // Sin motivo, no; con más de lo vendido, no.
        assertCode(call(put(base(b) + "/sales/" + s + "/returns/" + r1), bearer(owner), ret(lineQueso, 1000, "no", "CASH", null)).andExpect(status().isBadRequest()), "REASON_REQUIRED");
        assertCode(call(put(base(b) + "/sales/" + s + "/returns/" + r1), bearer(owner), ret(lineQueso, 4000, "venía roto", "CASH", null)).andExpect(status().isConflict()), "RETURN_EXCEEDS_SOLD");
        // Devolver 1 queso: su neto es C$100 − C$10 del descuento repartido = C$90, en efectivo.
        call(put(base(b) + "/sales/" + s + "/returns/" + r1), bearer(owner), ret(lineQueso, 1000, "venía roto", "CASH", null)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalMinor", is(9000))).andExpect(jsonPath("$.refunds[0].method", is("CASH"))).andExpect(jsonPath("$.refunds[0].amountMinor", is(9000)));
        assertEquals(8000, stockOf(queso), "lo devuelto vuelve a la existencia");
        // Repetir la MISMA devolución (mismo id, p. ej. reintento del teléfono) no duplica nada.
        call(put(base(b) + "/sales/" + s + "/returns/" + r1), bearer(owner), ret(lineQueso, 1000, "venía roto", "CASH", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMinor", is(9000)));
        assertEquals(8000, stockOf(queso));
        // Quedan 2 quesos: devolver 3 más ya no se puede (doble devolución); los 2 que quedan sí, por su neto exacto (C$180, sin residuos de redondeo).
        assertCode(call(put(base(b) + "/sales/" + s + "/returns/" + UUID.randomUUID()), bearer(owner), ret(lineQueso, 3000, "otra vez todo", "CASH", null))
                .andExpect(status().isConflict()), "RETURN_EXCEEDS_SOLD");
        call(put(base(b) + "/sales/" + s + "/returns/" + UUID.randomUUID()), bearer(owner), ret(lineQueso, 2000, "no le gustó", "SAME", null)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalMinor", is(18000)));
        // La venta muestra lo devuelto por línea y en total, y ya no se puede editar ni eliminar entera.
        call(get(base(b) + "/sales/" + s), bearer(owner), null).andExpect(jsonPath("$.returnedMinor", is(27000))).andExpect(jsonPath("$.returns", hasSize(2)))
                .andExpect(jsonPath("$.items[0].returnedMilli", is(3000))).andExpect(jsonPath("$.items[1].returnedMilli", is(0)));
        assertCode(call(post(base(b) + "/sales/" + s + "/cancel"), bearer(owner), "{\"reason\":\"borrar todo\"}").andExpect(status().isConflict()), "SALE_HAS_RETURNS");
        // Queda en la actividad del dueño.
        call(get(base(b) + "/activity"), bearer(owner), null).andExpect(jsonPath("$.items[?(@.action=='sale.return')]", hasSize(2)));
    }

    @Test
    void aCashierReturnsOnlyOwnSalesOfTheSameDayAndTheOwnerIsNotified() throws Exception {
        String owner = login("ret-b");
        UUID b = createBusiness(owner, "Devoluciones B");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        UUID lucia = createPinMember(owner, b, "Lucia", "CASHIER");
        String device = linkDevice(owner, b);
        UUID line = UUID.randomUUID();
        UUID today = UUID.randomUUID();
        asDevice(put(base(b) + "/sales/" + today), device, kevin, sale(item(line, null, "Pan", 1000, 2000), SaleTest.pay("CASH", 2000, ""), null, 0)).andExpect(status().isCreated());
        // Lucía no devuelve una venta de Kevin.
        assertCode(asDevice(put(base(b) + "/sales/" + today + "/returns/" + UUID.randomUUID()), device, lucia, ret(line, 1000, "estaba duro", "CASH", null))
                .andExpect(status().isForbidden()), "RETURN_NOT_ALLOWED");
        asDevice(put(base(b) + "/sales/" + today + "/returns/" + UUID.randomUUID()), device, kevin, ret(line, 1000, "estaba duro", "CASH", null)).andExpect(status().isCreated());
        // Una venta suya de hace una semana: pedir al dueño.
        UUID old = UUID.randomUUID();
        UUID oldLine = UUID.randomUUID();
        call(put(base(b) + "/sales/" + old), bearer(owner), sale(item(oldLine, null, "Pan", 1000, 1000), SaleTest.pay("CASH", 1000, ""), Instant.now().minus(7, ChronoUnit.DAYS), 0))
                .andExpect(status().isCreated());
        jdbc.sql("UPDATE sale SET completed_by_member_id = :k WHERE id = :s").param("k", kevin).param("s", old).update();
        assertCode(asDevice(put(base(b) + "/sales/" + old + "/returns/" + UUID.randomUUID()), device, kevin, ret(oldLine, 1000, "estaba duro", "CASH", null))
                .andExpect(status().isForbidden()), "RETURN_NOT_ALLOWED");
        call(put(base(b) + "/sales/" + old + "/returns/" + UUID.randomUUID()), bearer(owner), ret(oldLine, 1000, "estaba duro", "CASH", null)).andExpect(status().isCreated());
        // El dueño recibe el aviso de la devolución de Kevin (no de la suya propia).
        call(get(base(b) + "/notifications"), bearer(owner), null).andExpect(jsonPath("$.items[?(@.type=='SALE_RETURNED')]", hasSize(1)))
                .andExpect(jsonPath("$.items[?(@.type=='SALE_RETURNED')].args.memberName", contains("Kevin")));
    }

    @Test
    void aReturnOfACreditSaleLowersTheDebtAndNeverBelowWhatWasPaid() throws Exception {
        String owner = login("ret-c");
        UUID b = createBusiness(owner, "Devoluciones C");
        UUID customer = UUID.randomUUID();
        call(put(base(b) + "/customers/" + customer), bearer(owner), "{\"name\":\"Doña Karla\"}").andExpect(status().is2xxSuccessful());
        UUID line = UUID.randomUUID();
        UUID s = saleAsOwner(owner, b, item(line, null, "Arroz", 5000, 4000), SaleTest.pay("CREDIT", 20000, "\"customerId\":\"" + customer + "\""), Instant.now().minusSeconds(30));
        String credit = JsonPath.read(call(get(base(b) + "/credits?customerId=" + customer), bearer(owner), null).andReturn().getResponse().getContentAsString(), "$.items[0].id");
        call(put(base(b) + "/credit-payments/" + UUID.randomUUID()), bearer(owner), "{\"creditId\":\"" + credit + "\",\"amountMinor\":5000,\"method\":\"CASH\"}")
                .andExpect(status().is2xxSuccessful());
        // Debe C$150 (C$200 − C$50 abonado). Devolver 2 bolsas (C$100) con nota de crédito: debe C$50.
        call(put(base(b) + "/sales/" + s + "/returns/" + UUID.randomUUID()), bearer(owner), ret(line, 2000, "arroz con gorgojo", "CREDIT_NOTE", null)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.refunds[0].method", is("CREDIT"))).andExpect(jsonPath("$.refunds[0].amountMinor", is(10000)));
        call(get(base(b) + "/credits/" + credit), bearer(owner), null).andExpect(jsonPath("$.balanceMinor", is(5000))).andExpect(jsonPath("$.amountMinor", is(10000)));
        // Las otras 2 bolsas (C$100) ya no caben en la deuda (C$50): con nota de crédito se rechaza; "por el mismo medio" baja la deuda y el resto es efectivo.
        assertCode(call(put(base(b) + "/sales/" + s + "/returns/" + UUID.randomUUID()), bearer(owner), ret(line, 2000, "arroz con gorgojo", "CREDIT_NOTE", null))
                .andExpect(status().isConflict()), "CREDIT_NOTE_EXCEEDS");
        call(put(base(b) + "/sales/" + s + "/returns/" + UUID.randomUUID()), bearer(owner), ret(line, 2000, "arroz con gorgojo", "SAME", null)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.refunds[?(@.method=='CASH')].amountMinor", contains(5000))).andExpect(jsonPath("$.refunds[?(@.method=='CREDIT')].amountMinor", contains(5000)));
        call(get(base(b) + "/credits/" + credit), bearer(owner), null).andExpect(jsonPath("$.balanceMinor", is(0))).andExpect(jsonPath("$.status", is("PAID")));
        call(get(base(b) + "/customers/" + customer), bearer(owner), null).andExpect(jsonPath("$.balanceMinor", is(0)));
    }

    @Test
    void aReturnMadeOfflineTravelsThroughTheQueueOnceAndCountsOnTheDayItWasMade() throws Exception {
        String owner = login("ret-d");
        UUID b = createBusiness(owner, "Devoluciones D");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        String device = linkDevice(owner, b);
        UUID line = UUID.randomUUID();
        UUID s = UUID.randomUUID();
        Instant soldAt = Instant.now().minus(20, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        asDevice(put(base(b) + "/sales/" + s), device, kevin, sale(item(line, null, "Leche", 3000, 3000), SaleTest.pay("CASH", 9000, ""), soldAt, 0)).andExpect(status().isCreated());
        UUID returnId = UUID.randomUUID();
        UUID opId = UUID.randomUUID();
        Instant madeAt = soldAt.plus(5, ChronoUnit.MINUTES);
        String payload = "{\"saleId\":\"" + s + "\",\"items\":[{\"saleItemId\":\"" + line + "\",\"quantityMilli\":1000}],\"reason\":\"venía vencida\",\"refundMethod\":\"CASH\"}";
        String url = base(b) + "/sync/push";
        asDevice(post(url), device, kevin, push(op(opId, "SALE_RETURN", returnId, payload, kevin, madeAt))).andExpect(jsonPath("$.results[0].status", is("APPLIED")));
        // El mismo envío repetido: DUPLICATE; otra operación con la misma devolución: tampoco duplica.
        asDevice(post(url), device, kevin, push(op(opId, "SALE_RETURN", returnId, payload, kevin, madeAt))).andExpect(jsonPath("$.results[0].status", is("DUPLICATE")));
        asDevice(post(url), device, kevin, push(op(UUID.randomUUID(), "SALE_RETURN", returnId, payload, kevin, madeAt))).andExpect(jsonPath("$.results[0].status", is("APPLIED")));
        call(get(base(b) + "/sales/" + s), bearer(owner), null).andExpect(jsonPath("$.returns", hasSize(1))).andExpect(jsonPath("$.returnedMinor", is(3000)))
                .andExpect(jsonPath("$.returns[0].occurredAt", is(madeAt.toString())));
        // El teléfono la recibe dentro de la venta al bajar.
        asDevice(get(base(b) + "/sync/pull?since=0"), device, kevin, null).andExpect(jsonPath("$.changes[?(@.type=='sale')].data.returnedMinor", hasItem(3000)));
    }

    // ---------- los días cerrados no cambian ----------

    @Test
    void theDailyCloseOfTheReturnDayShowsReturnsAndOlderDaysDoNotChange() throws Exception {
        String owner = login("ret-e");
        UUID b = createBusiness(owner, "Cierre con devoluciones");
        // Managua = UTC−6, corte 02:00. 20 sep 18:00Z = mediodía del 20; 22 sep 07:00Z = 01:00 del 22 = aún jornada del 21.
        Instant d20 = Instant.parse("2026-09-20T18:00:00Z");
        UUID line = UUID.randomUUID();
        UUID s = saleAsOwner(owner, b, item(line, null, "Queso", 10000, 3000), SaleTest.pay("CASH", 20000, "") + "," + SaleTest.pay("CARD", 10000, ""), d20);
        UUID sameDay = saleAsOwner(owner, b, item(UUID.randomUUID(), null, "Pan", 1000, 1000), SaleTest.pay("CASH", 1000, ""), d20);
        UUID later = saleAsOwner(owner, b, item(UUID.randomUUID(), null, "Crema", 4000, 1000), SaleTest.pay("CASH", 4000, ""), d20);
        UUID cat = UUID.fromString(JsonPath.<List<String>>read(call(get(base(b) + "/expense-categories"), bearer(owner), null).andReturn().getResponse().getContentAsString(),
                "$[?(@.key=='other')].id").get(0));
        UUID expense = UUID.randomUUID();
        call(put(base(b) + "/expenses/" + expense), bearer(owner), "{\"amountMinor\":1500,\"source\":\"CASH_DRAWER\",\"categoryId\":\"" + cat + "\",\"occurredAt\":\"" + d20 + "\"}")
                .andExpect(status().isCreated());

        // Devolución de 1 queso (C$100) en efectivo a la 01:00 del 22 (jornada del 21).
        call(put(base(b) + "/sales/" + s + "/returns/" + UUID.randomUUID()), bearer(owner), ret(line, 1000, "estaba agrio", "CASH", Instant.parse("2026-09-22T07:00:00Z")))
                .andExpect(status().isCreated());
        // El pan se anula el mismo día: simplemente no cuenta. La crema se anula al día siguiente (por la cola, con la hora del teléfono): sigue en el 20
        // y resta en el 21.
        call(post(base(b) + "/sync/push"), bearer(owner), push(op(UUID.randomUUID(), "SALE_CANCEL", sameDay, "{\"reason\":\"cobré de más\"}", null, Instant.parse("2026-09-20T20:00:00Z"))))
                .andExpect(jsonPath("$.results[0].status", is("APPLIED")));
        call(post(base(b) + "/sync/push"), bearer(owner), push(op(UUID.randomUUID(), "SALE_CANCEL", later, "{\"reason\":\"no se la llevó\"}", null, Instant.parse("2026-09-21T18:00:00Z"))))
                .andExpect(jsonPath("$.results[0].status", is("APPLIED")));
        // El gasto del 20 se anula el 21: sigue en el 20 y el 21 lo devuelve al esperado.
        call(post(base(b) + "/expenses/" + expense + "/void"), bearer(owner), "{\"reason\":\"era de otro negocio\"}").andExpect(status().isOk());
        jdbc.sql("UPDATE expense SET voided_at = :t WHERE id = :e").param("t", Timestamp.from(Instant.parse("2026-09-21T18:00:00Z"))).param("e", expense).update();

        call(get(base(b) + "/reports/daily-close?from=2026-09-20&to=2026-09-21"), bearer(owner), null).andExpect(status().isOk())
                // Jornada del 20: el queso y la crema (anulada al día siguiente) siguen; el pan anulado el mismo día no.
                .andExpect(jsonPath("$.days[0].salesCount", is(2))).andExpect(jsonPath("$.days[0].salesMinor", is(34000)))
                .andExpect(jsonPath("$.days[0].cancelledCount", is(1))).andExpect(jsonPath("$.days[0].cancelledMinor", is(1000)))
                .andExpect(jsonPath("$.days[0].drawerExpensesMinor", is(1500)))
                .andExpect(jsonPath("$.days[0].expectedCashMinor", is(20000 + 4000 - 1500)))
                .andExpect(jsonPath("$.days[0].returnsMinor", is(0))).andExpect(jsonPath("$.days[0].netSalesMinor", is(34000)))
                // Jornada del 21: sin ventas propias; la devolución, la crema de ayer anulada y el gasto de ayer anulado.
                .andExpect(jsonPath("$.days[1].salesCount", is(0)))
                .andExpect(jsonPath("$.days[1].returnsCount", is(1))).andExpect(jsonPath("$.days[1].returnsMinor", is(10000))).andExpect(jsonPath("$.days[1].cashRefundsMinor", is(10000)))
                .andExpect(jsonPath("$.days[1].priorCancelledCount", is(1))).andExpect(jsonPath("$.days[1].priorCancelledMinor", is(4000))).andExpect(jsonPath("$.days[1].priorCancelledCashMinor", is(4000)))
                .andExpect(jsonPath("$.days[1].laterVoids[0].kind", is("EXPENSE_DRAWER"))).andExpect(jsonPath("$.days[1].laterVoids[0].cashEffectMinor", is(1500)))
                .andExpect(jsonPath("$.days[1].netSalesMinor", is(-14000)))
                .andExpect(jsonPath("$.days[1].expectedCashMinor", is(-10000 - 4000 + 1500)));
        // Los reportes del periodo separan lo devuelto y lo anulado después.
        call(get(base(b) + "/reports/sales?from=2026-09-20&to=2026-09-21"), bearer(owner), null)
                .andExpect(jsonPath("$.sales.totalMinor", is(34000))).andExpect(jsonPath("$.sales.returnsMinor", is(10000)))
                .andExpect(jsonPath("$.sales.priorCancelledMinor", is(4000))).andExpect(jsonPath("$.sales.netMinor", is(20000)));
        // Solo el 20: nada de lo que pasó el 21 lo cambia.
        call(get(base(b) + "/reports/sales?from=2026-09-20&to=2026-09-20"), bearer(owner), null).andExpect(jsonPath("$.sales.netMinor", is(34000)));
    }

    @Test
    void theDailyCloseWarnsAboutPhonesWithOperationsNotYetSent() throws Exception {
        String owner = login("ret-f");
        UUID b = createBusiness(owner, "Cierre con pendientes");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        String device = linkDevice(owner, b);
        asDevice(post(base(b) + "/sync/push"), device, kevin, "{\"ops\":[],\"pendingOps\":3}").andExpect(status().isOk());
        call(get(base(b) + "/reports/daily-close"), bearer(owner), null).andExpect(jsonPath("$.syncWarnings[0].pendingOps", is(3)))
                .andExpect(jsonPath("$.syncWarnings[0].name", is("Caja 1")));
        // El teléfono informa al bajar que ya no tiene pendientes: el aviso desaparece.
        asDevice(get(base(b) + "/sync/pull?since=0&pendingOps=0"), device, kevin, null).andExpect(status().isOk());
        call(get(base(b) + "/reports/daily-close"), bearer(owner), null).andExpect(jsonPath("$.syncWarnings", hasSize(0)));
        // Un envío descuenta lo que quedó resuelto en él.
        asDevice(post(base(b) + "/sync/push"), device, kevin, "{\"ops\":[" + op(UUID.randomUUID(), "SALE_UPSERT", UUID.randomUUID(),
                sale(item(UUID.randomUUID(), null, "Pan", 100, 1000), SaleTest.pay("CASH", 100, ""), null, 0), kevin, Instant.now()) + "],\"pendingOps\":1}").andExpect(status().isOk());
        assertEquals(0, jdbc.sql("SELECT pending_ops FROM device WHERE business_id = :b").param("b", b).query(Integer.class).single());
    }

    // ---------- anular mi última venta ----------

    @Test
    void aCashierUndoesOnlyTheirLastSaleWithinFiveMinutes() throws Exception {
        String owner = login("undo-a");
        UUID b = createBusiness(owner, "Anular A");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        String device = linkDevice(owner, b);
        String other = linkDevice(owner, b);
        String saleUrl = base(b) + "/sales/";
        // Recién cobrada (hace 1 min): la anula él mismo, con motivo; el dueño recibe el aviso.
        UUID fresh = UUID.randomUUID();
        asDevice(put(saleUrl + fresh), device, kevin, sale(item(UUID.randomUUID(), null, "Pan", 1000, 1000), SaleTest.pay("CASH", 1000, ""), Instant.now().minusSeconds(60), 0))
                .andExpect(status().isCreated());
        assertCode(asDevice(post(saleUrl + fresh + "/cancel"), device, kevin, "{\"reason\":\"no\"}").andExpect(status().isBadRequest()), "REASON_REQUIRED");
        asDevice(post(saleUrl + fresh + "/cancel"), device, kevin, "{\"reason\":\"cobré otra cosa\"}").andExpect(status().isOk()).andExpect(jsonPath("$.status", is("CANCELLED")));
        call(get(base(b) + "/notifications"), bearer(owner), null).andExpect(jsonPath("$.items[?(@.type=='SALE_UNDONE')].args.reason", contains("cobré otra cosa")));
        call(get(base(b) + "/activity"), bearer(owner), null).andExpect(jsonPath("$.items[?(@.action=='sale.undo')]", hasSize(1)));

        // Cobrada hace 10 minutos: ya no; solo dueño o admin.
        UUID old = UUID.randomUUID();
        asDevice(put(saleUrl + old), device, kevin, sale(item(UUID.randomUUID(), null, "Pan", 1000, 1000), SaleTest.pay("CASH", 1000, ""), Instant.now().minus(10, ChronoUnit.MINUTES), 0))
                .andExpect(status().isCreated());
        asDevice(post(saleUrl + old + "/cancel"), device, kevin, "{\"reason\":\"cobré otra cosa\"}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("UNDO_NOT_ALLOWED"))).andExpect(jsonPath("$.reason", is("TOO_LATE")));
        // No es su última: tiene una más nueva.
        UUID a = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        asDevice(put(saleUrl + a), device, kevin, sale(item(UUID.randomUUID(), null, "Pan", 1000, 1000), SaleTest.pay("CASH", 1000, ""), Instant.now().minusSeconds(90), 0)).andExpect(status().isCreated());
        asDevice(put(saleUrl + c), device, kevin, sale(item(UUID.randomUUID(), null, "Pan", 1000, 1000), SaleTest.pay("CASH", 1000, ""), Instant.now().minusSeconds(30), 0)).andExpect(status().isCreated());
        asDevice(post(saleUrl + a + "/cancel"), device, kevin, "{\"reason\":\"cobré otra cosa\"}").andExpect(status().isForbidden()).andExpect(jsonPath("$.reason", is("NOT_LATEST")));

        // Sin conexión: cobró hace 3 h y la anuló a los 2 min (reloj del MISMO teléfono); llega ahora por la cola: vale.
        UUID offline = UUID.randomUUID();
        Instant soldAt = Instant.now().minus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        asDevice(put(saleUrl + offline), device, kevin, sale(item(UUID.randomUUID(), null, "Pan", 1000, 1000), SaleTest.pay("CASH", 1000, ""), soldAt, 0)).andExpect(status().isCreated());
        jdbc.sql("UPDATE sale SET completed_at = completed_at - interval '1 day' WHERE id IN (:ids)").param("ids", List.of(a, c, old)).update();
        asDevice(post(base(b) + "/sync/push"), device, kevin, push(op(UUID.randomUUID(), "SALE_CANCEL", offline, "{\"reason\":\"se arrepintió\"}", kevin, soldAt.plus(2, ChronoUnit.MINUTES))))
                .andExpect(jsonPath("$.results[0].status", is("APPLIED")));
        call(get(saleUrl + offline), bearer(owner), null).andExpect(jsonPath("$.status", is("CANCELLED"))).andExpect(jsonPath("$.cancelledAt", is(soldAt.plus(2, ChronoUnit.MINUTES).toString())));
        // La misma anulación desde OTRO teléfono (otro reloj): se mide con la hora del servidor, y ya pasaron más de 5 min.
        UUID offline2 = UUID.randomUUID();
        asDevice(put(saleUrl + offline2), device, kevin, sale(item(UUID.randomUUID(), null, "Pan", 1000, 1000), SaleTest.pay("CASH", 1000, ""), soldAt.plusSeconds(1), 0)).andExpect(status().isCreated());
        asDevice(post(base(b) + "/sync/push"), other, kevin, push(op(UUID.randomUUID(), "SALE_CANCEL", offline2, "{\"reason\":\"se arrepintió\"}", kevin, soldAt.plus(2, ChronoUnit.MINUTES))))
                .andExpect(jsonPath("$.results[0].code", is("UNDO_NOT_ALLOWED")));
        // El dueño sí puede eliminarla (flujo de siempre).
        call(post(saleUrl + offline2 + "/cancel"), bearer(owner), "{\"reason\":\"lo pidió Kevin\"}").andExpect(status().isOk());
    }

    // ---------- baja de una persona: lo que llega después ----------

    @Test
    void afterDeactivationOnlyPlausibleOfflineOperationsAreAcceptedAndTheyAreFlagged() throws Exception {
        String owner = login("late-a");
        UUID b = createBusiness(owner, "Baja tardía");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        String clean = linkDevice(owner, b);   // su último contacto informó 0 pendientes
        String busy = linkDevice(owner, b);    // su último contacto informó 2 pendientes
        List<UUID> phones = jdbc.sql("SELECT id FROM device WHERE business_id = :b ORDER BY linked_at").param("b", b).query(UUID.class).list();
        jdbc.sql("UPDATE device SET linked_at = now() - interval '30 days' WHERE id = :d").param("d", phones.get(0)).update();
        String url = base(b) + "/sync/push";
        asDevice(post(url), clean, kevin, "{\"ops\":[],\"pendingOps\":0}").andExpect(status().isOk());
        asDevice(post(url), busy, kevin, "{\"ops\":[],\"pendingOps\":2}").andExpect(status().isOk());
        call(put(base(b) + "/members/" + kevin), bearer(owner), "{\"status\":\"DISABLED\"}").andExpect(status().isOk());
        UUID lucia = createPinMember(owner, b, "Lucia", "CASHIER");
        Instant now = Instant.now();
        String pan = sale(item(UUID.randomUUID(), null, "Pan", 1000, 1000), SaleTest.pay("CASH", 1000, ""), null, 0);

        // Más de 48 h antes de la baja: no.
        asDevice(post(url), busy, lucia, push(op(UUID.randomUUID(), "SALE_UPSERT", UUID.randomUUID(), pan, kevin, now.minus(3, ChronoUnit.DAYS))))
                .andExpect(jsonPath("$.results[0].code", is("LATE_OP_REJECTED"))).andExpect(jsonPath("$.results[0].detail.reason", is("TOO_OLD")));
        // Antes de que ese teléfono se vinculara (hace segundos): imposible.
        asDevice(post(url), busy, lucia, push(op(UUID.randomUUID(), "SALE_UPSERT", UUID.randomUUID(), pan, kevin, now.minus(1, ChronoUnit.HOURS))))
                .andExpect(jsonPath("$.results[0].detail.reason", is("BEFORE_LINK")));
        // El teléfono "limpio" ya había enviado todo antes de la baja: algo hecho antes de ese contacto es inventado.
        asDevice(post(url), clean, lucia, push(op(UUID.randomUUID(), "SALE_UPSERT", UUID.randomUUID(), pan, kevin, now.minus(1, ChronoUnit.HOURS))))
                .andExpect(jsonPath("$.results[0].detail.reason", is("ALREADY_SYNCED")));
        // Plausible (el teléfono tenía pendientes y es de justo antes de la baja): se acepta, marcado, y el dueño recibe UN aviso con cuántas y cuánto.
        UUID ok1 = UUID.randomUUID();
        UUID ok2 = UUID.randomUUID();
        asDevice(post(url), busy, lucia, push(op(UUID.randomUUID(), "SALE_UPSERT", ok1, pan, kevin, now.minusSeconds(20)), op(UUID.randomUUID(), "SALE_UPSERT", ok2, pan, kevin, now.minusSeconds(10))))
                .andExpect(jsonPath("$.results[0].status", is("APPLIED"))).andExpect(jsonPath("$.results[1].status", is("APPLIED")));
        call(get(base(b) + "/sales/" + ok1), bearer(owner), null).andExpect(jsonPath("$.reviewFlag", is("LATE_AFTER_DISABLE")));
        call(get(base(b) + "/notifications"), bearer(owner), null).andExpect(jsonPath("$.items[?(@.type=='LATE_AFTER_DISABLE')]", hasSize(1)))
                .andExpect(jsonPath("$.items[?(@.type=='LATE_AFTER_DISABLE')].args.count", contains(2))).andExpect(jsonPath("$.items[?(@.type=='LATE_AFTER_DISABLE')].args.amountMinor", contains(2000)));
        call(get(base(b) + "/activity"), bearer(owner), null).andExpect(jsonPath("$.items[?(@.action=='sync.late_after_disable')]", hasSize(2)));
    }

    @Test
    void aPhoneWithAnImpossibleClockGetsTheServerTimeAndTheSaleIsFlagged() throws Exception {
        String owner = login("clock-a");
        UUID b = createBusiness(owner, "Reloj");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        String device = linkDevice(owner, b);
        UUID year2000 = UUID.randomUUID();
        Instant bad = Instant.parse("2000-01-01T12:00:00Z");
        asDevice(post(base(b) + "/sync/push"), device, kevin, push(op(UUID.randomUUID(), "SALE_UPSERT", year2000,
                sale(item(UUID.randomUUID(), null, "Pan", 1000, 1000), SaleTest.pay("CASH", 1000, ""), bad, 0), kevin, bad))).andExpect(jsonPath("$.results[0].status", is("APPLIED")));
        String json = call(get(base(b) + "/sales/" + year2000), bearer(owner), null).andExpect(jsonPath("$.reviewFlag", is("CLOCK_ADJUSTED"))).andReturn().getResponse().getContentAsString();
        Instant completed = Instant.parse(JsonPath.read(json, "$.completedAt"));
        assertTrue(Duration.between(completed, Instant.now()).abs().toMinutes() < 5, "se registra con la hora del servidor, no en el año 2000");
    }

    // ---------- moneda y país ----------

    @Test
    void theCurrencyCanChangeOnlyBeforeAnyActivityAndCountriesSuggestDefaults() throws Exception {
        mvc.perform(get("/api/config/countries")).andExpect(status().isOk()).andExpect(jsonPath("$[?(@.code=='HN')].currency", contains("HNL")))
                .andExpect(jsonPath("$[?(@.code=='HN')].timezone", contains("America/Tegucigalpa")));
        mvc.perform(get("/api/config")).andExpect(jsonPath("$.panelUrl", notNullValue()));
        String owner = login("cur-a");
        UUID b = createBusiness(owner, "Moneda");
        call(get(base(b)), bearer(owner), null).andExpect(jsonPath("$.currencyLocked", is(false)));
        call(put(base(b)), bearer(owner), "{\"currency\":\"HNL\",\"country\":\"HN\"}").andExpect(status().isOk()).andExpect(jsonPath("$.currency", is("HNL")))
                .andExpect(jsonPath("$.country", is("HN")));
        saleAsOwner(owner, b, item(UUID.randomUUID(), null, "Pan", 1000, 1000), SaleTest.pay("CASH", 1000, ""), null);
        call(get(base(b)), bearer(owner), null).andExpect(jsonPath("$.currencyLocked", is(true)));
        assertCode(call(put(base(b)), bearer(owner), "{\"currency\":\"USD\"}").andExpect(status().isConflict()), "CURRENCY_LOCKED");
        // Repetir la misma moneda (un formulario que manda todo) no molesta.
        call(put(base(b)), bearer(owner), "{\"currency\":\"HNL\",\"name\":\"Moneda 2\"}").andExpect(status().isOk());
    }

    // ---------- borrado definitivo ----------

    @Test
    void aBusinessDeletedMoreThanThirtyDaysAgoIsPurgedWithAllItsRows() throws Exception {
        String owner = login("purge-a");
        UUID b = createBusiness(owner, "Para borrar");
        UUID keep = createBusiness(owner, "Se queda");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        String device = linkDevice(owner, b);
        UUID p = trackedProduct(owner, b, 5000);
        UUID line = UUID.randomUUID();
        UUID s = UUID.randomUUID();
        asDevice(put(base(b) + "/sales/" + s), device, kevin, sale(item(line, p, "Queso", 10000, 1000), SaleTest.pay("CASH", 10000, ""), null, 0)).andExpect(status().isCreated());
        call(put(base(b) + "/sales/" + s + "/returns/" + UUID.randomUUID()), bearer(owner), ret(line, 1000, "venía roto", "CASH", null)).andExpect(status().isCreated());
        saleAsOwner(owner, keep, item(UUID.randomUUID(), null, "Pan", 1000, 1000), SaleTest.pay("CASH", 1000, ""), null);

        call(delete(base(b)), bearer(owner), null).andExpect(status().isAccepted());
        // Mientras tanto, los teléfonos del equipo ya no operan.
        asDevice(get(base(b) + "/sync/pull?since=0"), device, kevin, null).andExpect(status().isNotFound());
        Instant requested = jdbc.sql("SELECT deletion_requested_at FROM business WHERE id = :b").param("b", b).query((rs, n) -> rs.getTimestamp(1).toInstant()).single();
        // A los 29 días todavía no; a los 31, sí (reloj falso: se le pasa la hora).
        assertFalse(purge.purgeDue(requested.plus(29, ChronoUnit.DAYS)).contains(b));
        assertEquals(1, jdbc.sql("SELECT count(*) FROM business WHERE id = :b").param("b", b).query(Integer.class).single());
        assertTrue(purge.purgeDue(requested.plus(31, ChronoUnit.DAYS)).contains(b));
        for (String t : List.of("business", "sale", "sale_item", "sale_return", "sale_return_item", "stock_movement", "product", "member", "device", "audit_log", "cash_register")) {
            String col = t.equals("business") ? "id" : "business_id";
            assertEquals(0, jdbc.sql("SELECT count(*) FROM " + t + " WHERE " + col + " = :b").param("b", b).query(Integer.class).single(), "quedaron filas en " + t);
        }
        assertEquals(1, jdbc.sql("SELECT count(*) FROM platform_audit_log WHERE action = 'business.purged' AND target = :t").param("t", b.toString()).query(Integer.class).single());
        // El otro negocio del mismo dueño y su cuenta siguen.
        call(get(base(keep) + "/sales"), bearer(owner), null).andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(1)));
    }
}
