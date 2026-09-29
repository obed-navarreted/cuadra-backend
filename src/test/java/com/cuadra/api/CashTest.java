package com.cuadra.api;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class CashTest extends ApiTestBase {
    @Autowired JdbcClient jdbc;

    private String base(UUID b) { return "/api/b/" + b; }

    private static String expense(long amount, String source, String extra) {
        return "{\"amountMinor\":" + amount + ",\"source\":\"" + source + "\"" + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    private static String movement(String kind, long amount) {
        return "{\"kind\":\"" + kind + "\",\"amountMinor\":" + amount + ",\"reason\":\"prueba\"}";
    }

    private UUID openShift(String token, UUID b, long floatMinor) throws Exception {
        UUID id = UUID.randomUUID();
        call(put(base(b) + "/shifts/" + id), bearer(token), "{\"openingFloatMinor\":" + floatMinor + "}").andExpect(status().isCreated()).andExpect(jsonPath("$.status", is("OPEN")));
        return id;
    }

    private void cashSale(String token, UUID b, long amount) throws Exception {
        call(put(base(b) + "/sales/" + UUID.randomUUID()), bearer(token), SaleTest.sale("COMPLETED", SaleTest.item("x", amount, 1000), SaleTest.pay("CASH", amount, ""), "")).andExpect(status().isCreated());
    }

    // ---------- gastos ----------

    @Test
    void everyBusinessStartsWithTheFactoryCategories() throws Exception {
        String owner = login("csha");
        UUID b = createBusiness(owner, "Caja A");
        call(get(base(b) + "/expense-categories"), bearer(owner), null).andExpect(jsonPath("$", hasSize(8))).andExpect(jsonPath("$[*].key", hasItem("utilities")))
                .andExpect(jsonPath("$[*].key", hasItem("goods")));
        UUID custom = UUID.randomUUID();
        call(put(base(b) + "/expense-categories/" + custom), bearer(owner), "{\"name\":\"Publicidad\"}").andExpect(status().isOk()).andExpect(jsonPath("$.key", nullValue())).andExpect(jsonPath("$.name", is("Publicidad")));
        call(get(base(b) + "/expense-categories"), bearer(owner), null).andExpect(jsonPath("$", hasSize(9)));
    }

    @Test
    void aCashierRecordsWhatLeavesTheDrawerButOnlyManagersRecordOtherSources() throws Exception {
        String owner = login("cshb");
        UUID b = createBusiness(owner, "Caja B");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        String device = linkDevice(owner, b);
        asDevice(put(base(b) + "/expenses/" + UUID.randomUUID()), device, kevin, expense(15000, "CASH_DRAWER", "\"description\":\"Hielo y bolsas\"")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.source", is("CASH_DRAWER"))).andExpect(jsonPath("$.cashRegisterId", notNullValue())).andExpect(jsonPath("$.createdByName", is("Kevin")));
        asDevice(put(base(b) + "/expenses/" + UUID.randomUUID()), device, kevin, expense(320000, "BANK", "")).andExpect(status().isForbidden());
        call(put(base(b) + "/expenses/" + UUID.randomUUID()), bearer(owner), expense(320000, "BANK", "\"description\":\"Recibo de luz\"")).andExpect(status().isCreated());
        call(put(base(b) + "/expenses/" + UUID.randomUUID()), bearer(owner), expense(0, "CASH_DRAWER", "")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("INVALID_AMOUNT")));
        call(put(base(b) + "/expenses/" + UUID.randomUUID()), bearer(owner), expense(100, "PIGGYBANK", "")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("INVALID_SOURCE")));

        // Un cajero ve solo lo suyo; el dueño ve todo, separado por origen del dinero.
        asDevice(get(base(b) + "/expenses"), device, kevin, null).andExpect(jsonPath("$.total", is(1)));
        call(get(base(b) + "/expenses"), bearer(owner), null).andExpect(jsonPath("$.total", is(2)));
        call(get(base(b) + "/expenses/summary"), bearer(owner), null).andExpect(jsonPath("$.cashDrawerMinor", is(15000))).andExpect(jsonPath("$.otherMinor", is(320000))).andExpect(jsonPath("$.count", is(2)));
    }

    @Test
    void anExpenseIsNeverEditedOnlyVoidedAndVoidedOnesDoNotCount() throws Exception {
        String owner = login("cshc");
        UUID b = createBusiness(owner, "Caja C");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        String device = linkDevice(owner, b);
        UUID id = UUID.randomUUID();
        asDevice(put(base(b) + "/expenses/" + id), device, kevin, expense(5000, "CASH_DRAWER", "")).andExpect(status().isCreated());
        asDevice(put(base(b) + "/expenses/" + id), device, kevin, expense(5000, "CASH_DRAWER", "")).andExpect(status().isOk());       // repetir lo mismo: nada cambia
        asDevice(put(base(b) + "/expenses/" + id), device, kevin, expense(9000, "CASH_DRAWER", "")).andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("EXPENSE_IMMUTABLE")));
        asDevice(post(base(b) + "/expenses/" + id + "/void"), device, kevin, "{\"reason\":\"x\"}").andExpect(status().isForbidden());
        call(post(base(b) + "/expenses/" + id + "/void"), bearer(owner), "{\"reason\":\"me equivoqué\"}").andExpect(status().isOk()).andExpect(jsonPath("$.voided", is(true)));
        call(post(base(b) + "/expenses/" + id + "/void"), bearer(owner), "{}").andExpect(status().isOk());                            // anular dos veces es inofensivo
        call(get(base(b) + "/expenses/summary"), bearer(owner), null).andExpect(jsonPath("$.cashDrawerMinor", is(0))).andExpect(jsonPath("$.count", is(0)));
        call(get(base(b) + "/expenses"), bearer(owner), null).andExpect(jsonPath("$.total", is(0)));
        call(get(base(b) + "/expenses?includeVoided=true"), bearer(owner), null).andExpect(jsonPath("$.total", is(1)));
    }

    // ---------- retiros y entradas ----------

    @Test
    void anyoneAddsChangeButOnlyManagersWithdraw() throws Exception {
        String owner = login("cshd");
        UUID b = createBusiness(owner, "Caja D");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        String device = linkDevice(owner, b);
        UUID dep = UUID.randomUUID();
        asDevice(put(base(b) + "/cash-movements/" + dep), device, kevin, movement("DEPOSIT", 50000)).andExpect(status().isCreated()).andExpect(jsonPath("$.kind", is("DEPOSIT")));
        asDevice(put(base(b) + "/cash-movements/" + UUID.randomUUID()), device, kevin, movement("WITHDRAWAL", 100000)).andExpect(status().isForbidden());
        UUID wd = UUID.randomUUID();
        call(put(base(b) + "/cash-movements/" + wd), bearer(owner), movement("WITHDRAWAL", 100000)).andExpect(status().isCreated());
        call(put(base(b) + "/cash-movements/" + wd), bearer(owner), movement("WITHDRAWAL", 100000)).andExpect(status().isOk());
        call(put(base(b) + "/cash-movements/" + wd), bearer(owner), movement("WITHDRAWAL", 999)).andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("MOVEMENT_IMMUTABLE")));
        call(put(base(b) + "/cash-movements/" + UUID.randomUUID()), bearer(owner), movement("STEAL", 1)).andExpect(status().isBadRequest());
        asDevice(get(base(b) + "/cash-movements"), device, kevin, null).andExpect(jsonPath("$.total", is(1)));        // el cajero no ve el retiro del dueño
        call(get(base(b) + "/cash-movements"), bearer(owner), null).andExpect(jsonPath("$.total", is(2)));
        call(post(base(b) + "/cash-movements/" + wd + "/void"), bearer(owner), "{\"reason\":\"prueba\"}").andExpect(status().isOk()).andExpect(jsonPath("$.voided", is(true)));
    }

    // ---------- turnos ----------

    @Test
    void theClosingMathMatchesTheWorkedExampleOfThePlan() throws Exception {
        // Fondo 1,000 + ventas en efectivo 8,420 + abonos en efectivo 1,150 − gastos del cajón 1,850 − retiros 1,000 = 7,720; contado 7,690 → faltante de 30.
        String owner = login("cshe");
        UUID b = createBusiness(owner, "Caja E");
        UUID shift = openShift(owner, b, 100000);
        cashSale(owner, b, 500000);
        cashSale(owner, b, 342000);
        // Fuera del cajón: una venta por transferencia y una a fiado; no cuentan para lo esperado.
        call(put(base(b) + "/sales/" + UUID.randomUUID()), bearer(owner), SaleTest.sale("COMPLETED", SaleTest.item("y", 90000, 1000), SaleTest.pay("TRANSFER", 90000, ""), "")).andExpect(status().isCreated());
        UUID credit = UUID.randomUUID();
        call(put(base(b) + "/credits/" + credit), bearer(owner), "{\"debtorLabel\":\"Ana\",\"amountMinor\":200000}").andExpect(status().isCreated());
        call(put(base(b) + "/credit-payments/" + UUID.randomUUID()), bearer(owner), "{\"creditId\":\"" + credit + "\",\"amountMinor\":115000,\"method\":\"CASH\"}").andExpect(status().isCreated());
        call(put(base(b) + "/credit-payments/" + UUID.randomUUID()), bearer(owner), "{\"creditId\":\"" + credit + "\",\"amountMinor\":10000,\"method\":\"TRANSFER\"}").andExpect(status().isCreated());
        call(put(base(b) + "/expenses/" + UUID.randomUUID()), bearer(owner), expense(185000, "CASH_DRAWER", "")).andExpect(status().isCreated());
        call(put(base(b) + "/expenses/" + UUID.randomUUID()), bearer(owner), expense(320000, "BANK", "")).andExpect(status().isCreated());   // no toca el cajón
        call(put(base(b) + "/cash-movements/" + UUID.randomUUID()), bearer(owner), movement("WITHDRAWAL", 100000)).andExpect(status().isCreated());

        call(get(base(b) + "/shifts/current"), bearer(owner), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.breakdown.cashSalesMinor", is(842000))).andExpect(jsonPath("$.breakdown.cashSalesCount", is(2)))
                .andExpect(jsonPath("$.breakdown.creditPaymentsCashMinor", is(115000))).andExpect(jsonPath("$.breakdown.expensesCashMinor", is(185000)))
                .andExpect(jsonPath("$.breakdown.withdrawalsMinor", is(100000))).andExpect(jsonPath("$.breakdown.transferMinor", is(90000)))
                .andExpect(jsonPath("$.breakdown.expectedNowMinor", is(772000)));
        call(post(base(b) + "/shifts/" + shift + "/close"), bearer(owner), "{\"countedMinor\":769000}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CLOSED"))).andExpect(jsonPath("$.expectedAtCloseMinor", is(772000))).andExpect(jsonPath("$.countedMinor", is(769000)))
                .andExpect(jsonPath("$.differenceMinor", is(-3000))).andExpect(jsonPath("$.closedBy.name", is("csheowner".isEmpty() ? "" : "cshe")));
        call(get(base(b) + "/shifts/current"), bearer(owner), null).andExpect(status().isNoContent());
    }

    @Test
    void onlyOneShiftIsOpenPerRegisterAndOpeningIsIdempotent() throws Exception {
        String owner = login("cshf");
        UUID b = createBusiness(owner, "Caja F");
        UUID first = openShift(owner, b, 50000);
        // Repetir la misma apertura no crea nada.
        call(put(base(b) + "/shifts/" + first), bearer(owner), "{\"openingFloatMinor\":50000}").andExpect(status().isOk()).andExpect(jsonPath("$.id", is(first.toString())));
        // Otro teléfono (o persona) intenta abrir la misma caja: recibe el turno que ya existe, no un segundo.
        call(put(base(b) + "/shifts/" + UUID.randomUUID()), bearer(owner), "{\"openingFloatMinor\":1}").andExpect(status().isOk()).andExpect(jsonPath("$.id", is(first.toString())))
                .andExpect(jsonPath("$.openingFloatMinor", is(50000)));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.sql("SELECT count(*) FROM shift WHERE business_id = :b").param("b", b).query(Integer.class).single());
    }

    @Test
    void aCashierClosesOnlyTheirOwnShiftAndDoesNotSeeOthersResults() throws Exception {
        String owner = login("cshg");
        UUID b = createBusiness(owner, "Caja G");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        UUID luisa = createPinMember(owner, b, "Luisa", "CASHIER");
        String device = linkDevice(owner, b);
        UUID shift = UUID.randomUUID();
        asDevice(put(base(b) + "/shifts/" + shift), device, kevin, "{\"openingFloatMinor\":100000}").andExpect(status().isCreated());
        asDevice(post(base(b) + "/shifts/" + shift + "/close"), device, luisa, "{\"countedMinor\":100000}").andExpect(status().isForbidden());
        asDevice(post(base(b) + "/shifts/" + shift + "/close"), device, kevin, "{\"countedMinor\":98000}").andExpect(status().isOk()).andExpect(jsonPath("$.differenceMinor", is(-2000)));
        // Luisa no ve cómo le fue a Kevin; el dueño sí.
        asDevice(get(base(b) + "/shifts/" + shift), device, luisa, null).andExpect(status().isOk()).andExpect(jsonPath("$.status", is("CLOSED"))).andExpect(jsonPath("$.differenceMinor", nullValue()))
                .andExpect(jsonPath("$.countedMinor", nullValue())).andExpect(jsonPath("$.breakdown", nullValue()));
        call(get(base(b) + "/shifts/" + shift), bearer(owner), null).andExpect(jsonPath("$.differenceMinor", is(-2000)));
        // Cerrar dos veces con lo mismo es inofensivo; con otro monto no.
        asDevice(post(base(b) + "/shifts/" + shift + "/close"), device, kevin, "{\"countedMinor\":98000}").andExpect(status().isOk());
        asDevice(post(base(b) + "/shifts/" + shift + "/close"), device, kevin, "{\"countedMinor\":1}").andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("SHIFT_CLOSED")));
    }

    @Test
    void closingWaitsForOtherPhonesOfTheRegisterUnlessAManagerForcesIt() throws Exception {
        String owner = login("cshh");
        UUID b = createBusiness(owner, "Caja H");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        String phoneA = linkDevice(owner, b);
        String phoneB = linkDevice(owner, b);
        UUID shift = UUID.randomUUID();
        asDevice(put(base(b) + "/shifts/" + shift), phoneA, kevin, "{\"openingFloatMinor\":100000}").andExpect(status().isCreated());
        // El teléfono B (misma caja) reportó 3 operaciones sin enviar.
        jdbc.sql("UPDATE device SET pending_ops = 3 WHERE id = (SELECT id FROM device WHERE business_id = :b ORDER BY linked_at DESC LIMIT 1)").param("b", b).update();

        asDevice(post(base(b) + "/shifts/" + shift + "/close"), phoneA, kevin, "{\"countedMinor\":100000}").andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("DEVICES_PENDING")))
                .andExpect(jsonPath("$.devices", hasSize(1))).andExpect(jsonPath("$.devices[0].pendingOps", is(3)));
        asDevice(post(base(b) + "/shifts/" + shift + "/close"), phoneA, kevin, "{\"countedMinor\":100000,\"force\":true,\"forcedReason\":\"x\"}").andExpect(status().isForbidden());
        call(post(base(b) + "/shifts/" + shift + "/close"), bearer(owner), "{\"countedMinor\":100000,\"force\":true}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("REASON_REQUIRED")));
        call(post(base(b) + "/shifts/" + shift + "/close"), bearer(owner), "{\"countedMinor\":100000,\"force\":true,\"forcedReason\":\"el otro teléfono está apagado\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.forcedReason", is("el otro teléfono está apagado"))).andExpect(jsonPath("$.status", is("CLOSED")));
    }

    @Test
    void theClosingNoteIsRequiredOnlyAboveTheBusinessThreshold() throws Exception {
        String owner = login("cshi");
        UUID b = createBusiness(owner, "Caja I");
        call(put("/api/b/" + b), bearer(owner), "{\"shiftNoteThresholdMinor\":1000}").andExpect(status().isOk()).andExpect(jsonPath("$.shiftNoteThresholdMinor", is(1000)));
        UUID shift = openShift(owner, b, 100000);
        call(post(base(b) + "/shifts/" + shift + "/close"), bearer(owner), "{\"countedMinor\":95000}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("NOTE_REQUIRED")));
        call(post(base(b) + "/shifts/" + shift + "/close"), bearer(owner), "{\"countedMinor\":95000,\"note\":\"se cayó un billete\"}").andExpect(status().isOk()).andExpect(jsonPath("$.note", is("se cayó un billete")));
        UUID small = openShift(owner, b, 100000);
        call(post(base(b) + "/shifts/" + small + "/close"), bearer(owner), "{\"countedMinor\":99500}").andExpect(status().isOk());       // 500 < 1,000: sin nota
    }

    @Test
    void anOperationArrivingAfterTheClosingLandsInItsShiftAndIsMarkedLate() throws Exception {
        String owner = login("cshj");
        UUID b = createBusiness(owner, "Caja J");
        UUID shift = openShift(owner, b, 100000);
        cashSale(owner, b, 50000);
        String closed = call(post(base(b) + "/shifts/" + shift + "/close"), bearer(owner), "{\"countedMinor\":150000}").andExpect(status().isOk()).andExpect(jsonPath("$.differenceMinor", is(0)))
                .andExpect(jsonPath("$.lateOps", is(0))).andReturn().getResponse().getContentAsString();
        Instant openedAt = Instant.parse(JsonPath.read(closed, "$.openedAt"));
        Instant closedAt = Instant.parse(JsonPath.read(closed, "$.closedAt"));
        Instant inside = openedAt.plus(java.time.Duration.between(openedAt, closedAt).dividedBy(2));

        // Un teléfono sin conexión envía ahora una venta que hizo DURANTE el turno.
        call(put(base(b) + "/sales/" + UUID.randomUUID()), bearer(owner), SaleTest.sale("COMPLETED", SaleTest.item("late", 20000, 1000), SaleTest.pay("CASH", 20000, ""), "\"completedAt\":\"" + inside + "\""))
                .andExpect(status().isCreated());
        call(get(base(b) + "/shifts/" + shift), bearer(owner), null).andExpect(jsonPath("$.lateOps", is(1)))
                .andExpect(jsonPath("$.expectedAtCloseMinor", is(150000)))            // la foto del cierre no se reescribe
                .andExpect(jsonPath("$.differenceMinor", is(0)))
                .andExpect(jsonPath("$.breakdown.expectedNowMinor", is(170000)));      // pero el esperado real ya cuenta la venta tardía
        // Una venta de OTRA hora (después del cierre) no pertenece a este turno.
        cashSale(owner, b, 7000);
        call(get(base(b) + "/shifts/" + shift), bearer(owner), null).andExpect(jsonPath("$.breakdown.expectedNowMinor", is(170000))).andExpect(jsonPath("$.lateOps", is(1)));
    }

    @Test
    void onlyTheOwnerReopensAShiftAndOnlyIfNoneIsOpen() throws Exception {
        String owner = login("cshk");
        UUID b = createBusiness(owner, "Caja K");
        String admin = joinAs(owner, b, "cshkadm", "ADMIN");
        UUID shift = openShift(owner, b, 100000);
        call(post(base(b) + "/shifts/" + shift + "/close"), bearer(owner), "{\"countedMinor\":100000}").andExpect(status().isOk());
        call(post(base(b) + "/shifts/" + shift + "/reopen"), bearer(admin), "{\"reason\":\"x\"}").andExpect(status().isForbidden());
        call(post(base(b) + "/shifts/" + shift + "/reopen"), bearer(owner), "{}").andExpect(status().isBadRequest());
        UUID other = openShift(owner, b, 1);
        call(post(base(b) + "/shifts/" + shift + "/reopen"), bearer(owner), "{\"reason\":\"me faltó un gasto\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("SHIFT_ALREADY_OPEN")));
        call(post(base(b) + "/shifts/" + other + "/close"), bearer(owner), "{\"countedMinor\":1}").andExpect(status().isOk());
        call(post(base(b) + "/shifts/" + shift + "/reopen"), bearer(owner), "{\"reason\":\"me faltó un gasto\"}").andExpect(status().isOk()).andExpect(jsonPath("$.status", is("OPEN")))
                .andExpect(jsonPath("$.reopenedCount", is(1))).andExpect(jsonPath("$.countedMinor", nullValue()));
    }

    @Test
    void otherBusinessesSeeNothingOfYourCash() throws Exception {
        String ownerA = login("cshl");
        String ownerB = login("cshm");
        UUID a = createBusiness(ownerA, "Caja L");
        UUID b = createBusiness(ownerB, "Caja M");
        UUID shift = openShift(ownerA, a, 100000);
        UUID expense = UUID.randomUUID();
        call(put(base(a) + "/expenses/" + expense), bearer(ownerA), expense(1000, "CASH_DRAWER", "")).andExpect(status().isCreated());
        call(get(base(a) + "/shifts"), bearer(ownerB), null).andExpect(status().isNotFound());
        call(get(base(b) + "/shifts/" + shift), bearer(ownerB), null).andExpect(status().isNotFound());
        call(post(base(b) + "/shifts/" + shift + "/close"), bearer(ownerB), "{\"countedMinor\":1}").andExpect(status().isNotFound());
        call(post(base(b) + "/expenses/" + expense + "/void"), bearer(ownerB), "{}").andExpect(status().isNotFound());
        call(put(base(b) + "/expenses/" + expense), bearer(ownerB), expense(1, "CASH_DRAWER", "")).andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("ID_TAKEN")));
        call(put(base(b) + "/shifts/" + shift), bearer(ownerB), "{\"openingFloatMinor\":1}").andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("ID_TAKEN")));
    }
}
