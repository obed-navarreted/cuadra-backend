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
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class CreditTest extends ApiTestBase {
    @Autowired JdbcClient jdbc;

    // ---------- helpers ----------

    private String base(UUID b) { return "/api/b/" + b; }

    private static String manual(String label, long amount, String extra) {
        return "{\"debtorLabel\":\"" + label + "\",\"amountMinor\":" + amount + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    /** Venta cobrada: parte en efectivo y el resto fiado a nombre de `debtor`. Devuelve el id del fiado creado. */
    private UUID saleOnCredit(String owner, UUID b, long total, long credit, String debtor, String extraPayment) throws Exception {
        UUID sale = UUID.randomUUID();
        String payments = (total > credit ? SaleTest.pay("CASH", total - credit, "") + "," : "")
                + SaleTest.pay("CREDIT", credit, "\"debtorLabel\":\"" + debtor + "\"" + (extraPayment.isEmpty() ? "" : "," + extraPayment));
        call(put(base(b) + "/sales/" + sale), bearer(owner), SaleTest.sale("COMPLETED", SaleTest.item("Compra", total, 1000), payments, "")).andExpect(status().isCreated());
        return creditOfSale(sale);
    }

    private UUID creditOfSale(UUID sale) {
        return jdbc.sql("SELECT id FROM credit WHERE sale_id = :s").param("s", sale).query(UUID.class).single();
    }

    private UUID customer(String token, UUID b, String name, String phone) throws Exception {
        UUID id = UUID.randomUUID();
        call(put(base(b) + "/customers/" + id), bearer(token), "{\"name\":\"" + name + "\"" + (phone == null ? "" : ",\"phone\":\"" + phone + "\"") + "}").andExpect(status().isCreated());
        return id;
    }

    private void pay(String token, UUID b, UUID paymentId, String target, long amount) throws Exception {
        call(put(base(b) + "/credit-payments/" + paymentId), bearer(token), "{" + target + ",\"amountMinor\":" + amount + ",\"method\":\"CASH\"}").andExpect(status().is2xxSuccessful());
    }

    // ---------- fiado desde una venta ----------

    @Test
    void aSaleOnCreditCreatesACreditWithJustAName() throws Exception {
        String owner = login("crda");
        UUID b = createBusiness(owner, "Fiado A");
        UUID credit = saleOnCredit(owner, b, 15502, 5502, "Dona Karla Chavez", "\"debtorPhone\":\"8855 1234\"");

        call(get(base(b) + "/credits/" + credit), bearer(owner), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.debtorLabel", is("Dona Karla Chavez"))).andExpect(jsonPath("$.debtorPhone", is("50588551234")))
                .andExpect(jsonPath("$.amountMinor", is(5502))).andExpect(jsonPath("$.balanceMinor", is(5502))).andExpect(jsonPath("$.status", is("OPEN")))
                .andExpect(jsonPath("$.customerId", nullValue())).andExpect(jsonPath("$.saleId", notNullValue()));
        call(get(base(b) + "/credits/summary"), bearer(owner), null).andExpect(jsonPath("$.openCount", is(1))).andExpect(jsonPath("$.openTotalMinor", is(5502)));
    }

    @Test
    void aCreditNeedsWhoOwesItAndAValidPhone() throws Exception {
        String owner = login("crdb");
        UUID b = createBusiness(owner, "Fiado B");
        String item = SaleTest.item("x", 1000, 1000);
        call(put(base(b) + "/sales/" + UUID.randomUUID()), bearer(owner), SaleTest.sale("COMPLETED", item, SaleTest.pay("CREDIT", 1000, ""), ""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("DEBTOR_REQUIRED")));
        call(put(base(b) + "/sales/" + UUID.randomUUID()), bearer(owner), SaleTest.sale("COMPLETED", item, SaleTest.pay("CREDIT", 1000, "\"debtorLabel\":\"Ana\",\"debtorPhone\":\"abc\""), ""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("INVALID_PHONE")));
        // Este negocio decide exigir cliente en todo fiado.
        call(put("/api/b/" + b), bearer(owner), "{\"creditRequiresCustomer\":true}").andExpect(status().isOk());
        call(put(base(b) + "/sales/" + UUID.randomUUID()), bearer(owner), SaleTest.sale("COMPLETED", item, SaleTest.pay("CREDIT", 1000, "\"debtorLabel\":\"Ana\""), ""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("CUSTOMER_REQUIRED")));
        UUID ana = customer(owner, b, "Ana", null);
        call(put(base(b) + "/sales/" + UUID.randomUUID()), bearer(owner), SaleTest.sale("COMPLETED", item, SaleTest.pay("CREDIT", 1000, "\"customerId\":\"" + ana + "\""), ""))
                .andExpect(status().isCreated());
    }

    @Test
    void aNoteOnlyCreditCanBeLinkedToACustomerLaterWithoutChangingAmountsOrDates() throws Exception {
        String owner = login("crdc");
        UUID b = createBusiness(owner, "Fiado C");
        UUID credit = saleOnCredit(owner, b, 5000, 5000, "doña Karla", "");
        String before = call(get(base(b) + "/credits/" + credit), bearer(owner), null).andReturn().getResponse().getContentAsString();
        UUID karla = customer(owner, b, "Karla Chávez", "8855 1234");

        call(post(base(b) + "/credits/" + credit + "/link-customer"), bearer(owner), "{\"customerId\":\"" + karla + "\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId", is(karla.toString()))).andExpect(jsonPath("$.customerName", is("Karla Chávez")))
                .andExpect(jsonPath("$.debtorLabel", is("doña Karla"))).andExpect(jsonPath("$.debtorPhone", is("50588551234")))
                .andExpect(jsonPath("$.amountMinor", is(JsonPath.<Integer>read(before, "$.amountMinor")))).andExpect(jsonPath("$.createdAt", is(JsonPath.<String>read(before, "$.createdAt"))));
        call(get(base(b) + "/customers/" + karla), bearer(owner), null).andExpect(jsonPath("$.balanceMinor", is(5000)));
        call(get(base(b) + "/credits?linked=WITH"), bearer(owner), null).andExpect(jsonPath("$.items", hasSize(1)));
        call(get(base(b) + "/credits?linked=WITHOUT"), bearer(owner), null).andExpect(jsonPath("$.items", hasSize(0)));
    }

    // ---------- abonos ----------

    @Test
    void paymentsReduceTheBalanceAndAreIdempotent() throws Exception {
        String owner = login("crdd");
        UUID b = createBusiness(owner, "Fiado D");
        UUID credit = saleOnCredit(owner, b, 5000, 5000, "Ana", "");
        UUID p1 = UUID.randomUUID();
        String target = "\"creditId\":\"" + credit + "\"";

        pay(owner, b, p1, target, 2000);
        call(get(base(b) + "/credits/" + credit), bearer(owner), null).andExpect(jsonPath("$.balanceMinor", is(3000))).andExpect(jsonPath("$.paidMinor", is(2000))).andExpect(jsonPath("$.status", is("OPEN")));
        // Repetir el mismo abono (timeout tras guardar) no descuenta dos veces.
        call(put(base(b) + "/credit-payments/" + p1), bearer(owner), "{" + target + ",\"amountMinor\":2000,\"method\":\"CASH\"}").andExpect(status().isOk());
        call(get(base(b) + "/credits/" + credit), bearer(owner), null).andExpect(jsonPath("$.balanceMinor", is(3000)));

        pay(owner, b, UUID.randomUUID(), target, 3000);
        call(get(base(b) + "/credits/" + credit), bearer(owner), null).andExpect(jsonPath("$.balanceMinor", is(0))).andExpect(jsonPath("$.status", is("PAID")));
        call(get(base(b) + "/credits/summary"), bearer(owner), null).andExpect(jsonPath("$.openCount", is(0)));
        call(get(base(b) + "/credits?status=PAID"), bearer(owner), null).andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    void anOverpaymentIsAppliedNeverLostAndLeavesAReviewNotice() throws Exception {
        String owner = login("crde");
        UUID b = createBusiness(owner, "Fiado E");
        UUID credit = saleOnCredit(owner, b, 1000, 1000, "Ana", "");
        pay(owner, b, UUID.randomUUID(), "\"creditId\":\"" + credit + "\"", 1500);
        call(get(base(b) + "/credits/" + credit), bearer(owner), null).andExpect(jsonPath("$.balanceMinor", is(0))).andExpect(jsonPath("$.paidMinor", is(1500))).andExpect(jsonPath("$.status", is("PAID")));
        int events = jdbc.sql("SELECT count(*) FROM credit_event WHERE credit_id = :c AND kind = 'OVERPAYMENT_REVIEW'").param("c", credit).query(Integer.class).single();
        org.junit.jupiter.api.Assertions.assertEquals(1, events);
    }

    @Test
    void aCustomerPaymentIsSplitOldestFirstAndCanBeVoidedAsAWhole() throws Exception {
        String owner = login("crdf");
        UUID b = createBusiness(owner, "Fiado F");
        UUID cust = customer(owner, b, "Marta", "8812 4455");
        UUID c1 = UUID.randomUUID(), c2 = UUID.randomUUID(), c3 = UUID.randomUUID();
        for (Object[] r : new Object[][] {{c1, 1000, "2026-08-01T12:00:00Z"}, {c2, 2000, "2026-08-10T12:00:00Z"}, {c3, 3000, "2026-08-20T12:00:00Z"}}) {
            call(put(base(b) + "/credits/" + r[0]), bearer(owner), manual("Marta", (Integer) r[1], "\"customerId\":\"" + cust + "\",\"createdAt\":\"" + r[2] + "\"")).andExpect(status().isCreated());
        }
        call(get(base(b) + "/customers/" + cust), bearer(owner), null).andExpect(jsonPath("$.balanceMinor", is(6000)));

        UUID group = UUID.randomUUID();
        String result = call(put(base(b) + "/credit-payments/" + group), bearer(owner), "{\"customerId\":\"" + cust + "\",\"amountMinor\":2500,\"method\":\"CASH\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.payments", hasSize(2))).andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertEquals(List.of(1000, 1500), JsonPath.read(result, "$.payments[*].amountMinor"));
        call(get(base(b) + "/credits/" + c1), bearer(owner), null).andExpect(jsonPath("$.balanceMinor", is(0))).andExpect(jsonPath("$.status", is("PAID")));
        call(get(base(b) + "/credits/" + c2), bearer(owner), null).andExpect(jsonPath("$.balanceMinor", is(500)));
        call(get(base(b) + "/credits/" + c3), bearer(owner), null).andExpect(jsonPath("$.balanceMinor", is(3000)));
        call(get(base(b) + "/customers/" + cust), bearer(owner), null).andExpect(jsonPath("$.balanceMinor", is(3500)));

        call(post(base(b) + "/credit-payments/" + group + "/void"), bearer(owner), "{\"reason\":\"se equivocó\"}").andExpect(status().isOk());
        call(get(base(b) + "/credits/" + c1), bearer(owner), null).andExpect(jsonPath("$.balanceMinor", is(1000))).andExpect(jsonPath("$.status", is("OPEN")));
        call(get(base(b) + "/customers/" + cust), bearer(owner), null).andExpect(jsonPath("$.balanceMinor", is(6000)));
    }

    @Test
    void payingMoreThanACustomerOwesStillLeavesEverythingPaid() throws Exception {
        String owner = login("crdg");
        UUID b = createBusiness(owner, "Fiado G");
        UUID cust = customer(owner, b, "Marta", null);
        call(put(base(b) + "/credits/" + UUID.randomUUID()), bearer(owner), manual("Marta", 1000, "\"customerId\":\"" + cust + "\"")).andExpect(status().isCreated());
        call(put(base(b) + "/credits/" + UUID.randomUUID()), bearer(owner), manual("Marta", 2000, "\"customerId\":\"" + cust + "\"")).andExpect(status().isCreated());
        pay(owner, b, UUID.randomUUID(), "\"customerId\":\"" + cust + "\"", 10000);
        call(get(base(b) + "/customers/" + cust), bearer(owner), null).andExpect(jsonPath("$.balanceMinor", is(0)));
        call(put(base(b) + "/credit-payments/" + UUID.randomUUID()), bearer(owner), "{\"customerId\":\"" + cust + "\",\"amountMinor\":100}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("NO_OPEN_CREDITS")));
    }

    // ---------- condonar y anular ----------

    @Test
    void onlyManagersForgiveDebtsAndTheyMustSayWhy() throws Exception {
        String owner = login("crdh");
        UUID b = createBusiness(owner, "Fiado H");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        String device = linkDevice(owner, b);
        UUID cust = customer(owner, b, "Marta", null);
        UUID credit = UUID.randomUUID();
        call(put(base(b) + "/credits/" + credit), bearer(owner), manual("Marta", 4000, "\"customerId\":\"" + cust + "\"")).andExpect(status().isCreated());

        asDevice(post(base(b) + "/credits/" + credit + "/write-off"), device, kevin, "{\"reason\":\"x\"}").andExpect(status().isForbidden());
        call(post(base(b) + "/credits/" + credit + "/write-off"), bearer(owner), "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("REASON_REQUIRED")));
        call(post(base(b) + "/credits/" + credit + "/write-off"), bearer(owner), "{\"reason\":\"familiar del dueño\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("WRITTEN_OFF"))).andExpect(jsonPath("$.balanceMinor", is(0)));
        call(get(base(b) + "/customers/" + cust), bearer(owner), null).andExpect(jsonPath("$.balanceMinor", is(0)));
        call(get(base(b) + "/credits/summary"), bearer(owner), null).andExpect(jsonPath("$.openCount", is(0)));
        call(get(base(b) + "/customers/" + cust + "/statement"), bearer(owner), null).andExpect(jsonPath("$.movements[?(@.kind=='WRITE_OFF')]", hasSize(1)));
        // Un fiado condonado ya no admite abonos.
        call(put(base(b) + "/credit-payments/" + UUID.randomUUID()), bearer(owner), "{\"creditId\":\"" + credit + "\",\"amountMinor\":100}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("CREDIT_CLOSED")));
    }

    @Test
    void aCashierCanTakePaymentsButCannotVoidThem() throws Exception {
        String owner = login("crdi");
        UUID b = createBusiness(owner, "Fiado I");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        String device = linkDevice(owner, b);
        UUID credit = saleOnCredit(owner, b, 3000, 3000, "Ana", "");
        UUID payment = UUID.randomUUID();
        asDevice(put(base(b) + "/credit-payments/" + payment), device, kevin, "{\"creditId\":\"" + credit + "\",\"amountMinor\":1000,\"method\":\"TRANSFER\",\"reference\":\"BAC 123\"}").andExpect(status().isCreated());
        asDevice(post(base(b) + "/credit-payments/" + payment + "/void"), device, kevin, "{}").andExpect(status().isForbidden());
        call(post(base(b) + "/credit-payments/" + payment + "/void"), bearer(owner), "{\"reason\":\"no llegó la transferencia\"}").andExpect(status().isOk());
        call(get(base(b) + "/credits/" + credit), bearer(owner), null).andExpect(jsonPath("$.balanceMinor", is(3000)));
    }

    // ---------- la venta y su fiado se mueven juntos ----------

    @Test
    void editingOrDeletingTheSaleKeepsItsCreditConsistent() throws Exception {
        String owner = login("crdj");
        UUID b = createBusiness(owner, "Fiado J");
        UUID sale = UUID.randomUUID();
        UUID cashPay = UUID.randomUUID();
        UUID creditPay = UUID.randomUUID();
        String item = SaleTest.item("Compra", 10000, 1000);
        java.util.function.BiFunction<Long, Long, String> body = (cash, credit) -> SaleTest.sale("COMPLETED", item,
                "{\"id\":\"" + cashPay + "\",\"method\":\"CASH\",\"amountMinor\":" + cash + "},{\"id\":\"" + creditPay + "\",\"method\":\"CREDIT\",\"amountMinor\":" + credit + ",\"debtorLabel\":\"Ana\"}", "");
        call(put(base(b) + "/sales/" + sale), bearer(owner), body.apply(4000L, 6000L)).andExpect(status().isCreated());
        UUID credit = creditOfSale(sale);
        pay(owner, b, UUID.randomUUID(), "\"creditId\":\"" + credit + "\"", 2000);

        // Bajar el fiado por debajo de lo ya abonado no se permite; subir el efectivo sí, mientras alcance.
        call(put(base(b) + "/sales/" + sale), bearer(owner), body.apply(9000L, 1000L)).andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("CREDIT_PAID_EXCEEDS")));
        call(put(base(b) + "/sales/" + sale), bearer(owner), body.apply(6000L, 4000L)).andExpect(status().isOk());
        call(get(base(b) + "/credits/" + credit), bearer(owner), null).andExpect(jsonPath("$.amountMinor", is(4000))).andExpect(jsonPath("$.balanceMinor", is(2000)));

        // Eliminar la venta con abonos vigentes exige anularlos primero.
        call(post(base(b) + "/sales/" + sale + "/cancel"), bearer(owner), "{\"reason\":\"error de cobro\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("CREDIT_HAS_PAYMENTS")));
        String paymentId = jdbc.sql("SELECT id FROM credit_payment WHERE credit_id = :c").param("c", credit).query(UUID.class).single().toString();
        call(post(base(b) + "/credit-payments/" + paymentId + "/void"), bearer(owner), "{\"reason\":\"venta anulada\"}").andExpect(status().isOk());
        call(post(base(b) + "/sales/" + sale + "/cancel"), bearer(owner), "{\"reason\":\"error de cobro\"}").andExpect(status().isOk());
        call(get(base(b) + "/credits/" + credit), bearer(owner), null).andExpect(jsonPath("$.status", is("CANCELLED"))).andExpect(jsonPath("$.balanceMinor", is(0)));
        call(get(base(b) + "/credits/summary"), bearer(owner), null).andExpect(jsonPath("$.openCount", is(0)));
    }

    // ---------- lectura ----------

    @Test
    void theListFiltersBySearchAgeAndLinkAndTheSummarySeparatesOverdue() throws Exception {
        String owner = login("crdk");
        UUID b = createBusiness(owner, "Fiado K");
        call(put(base(b) + "/credits/" + UUID.randomUUID()), bearer(owner), manual("Don José Pérez", 61000, "\"debtorPhone\":\"8812 4455\",\"createdAt\":\"2026-08-01T12:00:00Z\"")).andExpect(status().isCreated());
        call(put(base(b) + "/credits/" + UUID.randomUUID()), bearer(owner), manual("El muchacho del taller", 8500, "")).andExpect(status().isCreated());

        call(get(base(b) + "/credits?q=pérez"), bearer(owner), null).andExpect(jsonPath("$.items", hasSize(1)));
        call(get(base(b) + "/credits?q=8812"), bearer(owner), null).andExpect(jsonPath("$.items", hasSize(1)));
        call(get(base(b) + "/credits?minDays=30"), bearer(owner), null).andExpect(jsonPath("$.items", hasSize(1))).andExpect(jsonPath("$.items[0].debtorLabel", is("Don José Pérez")));
        call(get(base(b) + "/credits?sort=AMOUNT"), bearer(owner), null).andExpect(jsonPath("$.items[0].balanceMinor", is(61000)));
        call(get(base(b) + "/credits/summary"), bearer(owner), null).andExpect(jsonPath("$.openCount", is(2))).andExpect(jsonPath("$.openTotalMinor", is(69500)))
                .andExpect(jsonPath("$.overdueCount", is(1))).andExpect(jsonPath("$.overdueMinor", is(61000)));
    }

    @Test
    void remindersAreRecordedOnTheCreditAndTheCustomer() throws Exception {
        String owner = login("crdl");
        UUID b = createBusiness(owner, "Fiado L");
        UUID cust = customer(owner, b, "Marta", "8812 4455");
        UUID credit = UUID.randomUUID();
        call(put(base(b) + "/credits/" + credit), bearer(owner), manual("Marta", 1000, "\"customerId\":\"" + cust + "\"")).andExpect(status().isCreated());
        call(get(base(b) + "/customers/" + cust), bearer(owner), null).andExpect(jsonPath("$.lastReminderAt", nullValue()));
        call(post(base(b) + "/credit-events"), bearer(owner), "{\"creditId\":\"" + credit + "\",\"kind\":\"REMINDER_OPENED\",\"format\":\"IMAGE\"}").andExpect(status().isNoContent());
        call(get(base(b) + "/customers/" + cust), bearer(owner), null).andExpect(jsonPath("$.lastReminderAt", notNullValue()));
        call(get(base(b) + "/credits/" + credit), bearer(owner), null).andExpect(jsonPath("$.lastReminderAt", notNullValue()));
        call(post(base(b) + "/credit-events"), bearer(owner), "{\"creditId\":\"" + credit + "\",\"kind\":\"BOGUS\"}").andExpect(status().isBadRequest());
    }

    @Test
    void messageTemplatesAreEditedByManagersAndCanBeReset() throws Exception {
        String owner = login("crdm");
        UUID b = createBusiness(owner, "Fiado M");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        String device = linkDevice(owner, b);
        String url = base(b) + "/message-templates/REMINDER/es";
        asDevice(put(url), device, kevin, "{\"body\":\"x\"}").andExpect(status().isForbidden());
        call(put(url), bearer(owner), "{\"body\":\"Hola {cliente}, debe {saldo} en {negocio}.\"}").andExpect(status().isOk()).andExpect(jsonPath("$.locale", is("es")));
        call(put(base(b) + "/message-templates/NOPE/es"), bearer(owner), "{\"body\":\"x\"}").andExpect(status().isBadRequest());
        call(put(base(b) + "/message-templates/REMINDER/fr"), bearer(owner), "{\"body\":\"x\"}").andExpect(status().isBadRequest());
        asDevice(get(base(b) + "/message-templates"), device, kevin, null).andExpect(jsonPath("$", hasSize(1)));
        call(delete(url), bearer(owner), null).andExpect(status().isNoContent());
        call(get(base(b) + "/message-templates"), bearer(owner), null).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void otherBusinessesSeeNothingOfYourLedger() throws Exception {
        String ownerA = login("crdn");
        String ownerB = login("crdo");
        UUID a = createBusiness(ownerA, "Fiado N");
        UUID b = createBusiness(ownerB, "Fiado O");
        UUID credit = saleOnCredit(ownerA, a, 1000, 1000, "Ana", "");
        UUID cust = customer(ownerA, a, "Marta", null);
        call(get(base(a) + "/credits"), bearer(ownerB), null).andExpect(status().isNotFound());
        call(get(base(b) + "/credits/" + credit), bearer(ownerB), null).andExpect(status().isNotFound());
        call(get(base(b) + "/customers/" + cust), bearer(ownerB), null).andExpect(status().isNotFound());
        call(put(base(b) + "/credit-payments/" + UUID.randomUUID()), bearer(ownerB), "{\"creditId\":\"" + credit + "\",\"amountMinor\":100}").andExpect(status().isNotFound());
        call(post(base(b) + "/credits/" + credit + "/write-off"), bearer(ownerB), "{\"reason\":\"x\"}").andExpect(status().isNotFound());
        call(put(base(b) + "/customers/" + cust), bearer(ownerB), "{\"name\":\"Robada\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("ID_TAKEN")));
    }

    // ---------- invariante ----------

    /**
     * Después de cualquier secuencia de operaciones, las cuentas cuadran: saldo del fiado = monto − abonos vigentes (0 si condonado/cancelado),
     * y saldo del cliente = suma de los saldos de sus fiados. Secuencia pseudoaleatoria con semilla fija (reproducible).
     */
    @Test
    void balancesAlwaysAddUpAfterARandomSequenceOfOperations() throws Exception {
        String owner = login("crdp");
        UUID b = createBusiness(owner, "Fiado P");
        Random rnd = new Random(20260928);
        List<UUID> customers = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) customers.add(customer(owner, b, "Cliente " + i, null));
        List<UUID> credits = new java.util.ArrayList<>();
        List<UUID> payments = new java.util.ArrayList<>();

        for (int step = 0; step < 60; step++) {
            int action = rnd.nextInt(10);
            if (action < 3 || credits.isEmpty()) {
                UUID id = UUID.randomUUID();
                String link = rnd.nextInt(3) == 0 ? "" : "\"customerId\":\"" + customers.get(rnd.nextInt(customers.size())) + "\"";
                call(put(base(b) + "/credits/" + id), bearer(owner), manual("Deudor " + step, 500 + rnd.nextInt(5000), link)).andExpect(status().isCreated());
                credits.add(id);
            } else if (action < 6) {
                UUID id = UUID.randomUUID();
                UUID credit = credits.get(rnd.nextInt(credits.size()));
                int code = call(put(base(b) + "/credit-payments/" + id), bearer(owner), "{\"creditId\":\"" + credit + "\",\"amountMinor\":" + (100 + rnd.nextInt(4000)) + ",\"method\":\"CASH\"}")
                        .andReturn().getResponse().getStatus();
                if (code == 201) payments.add(id);
            } else if (action < 7) {
                UUID id = UUID.randomUUID();
                UUID cust = customers.get(rnd.nextInt(customers.size()));
                int code = call(put(base(b) + "/credit-payments/" + id), bearer(owner), "{\"customerId\":\"" + cust + "\",\"amountMinor\":" + (100 + rnd.nextInt(6000)) + "}").andReturn().getResponse().getStatus();
                if (code == 201) payments.add(id);
            } else if (action < 8 && !payments.isEmpty()) {
                call(post(base(b) + "/credit-payments/" + payments.get(rnd.nextInt(payments.size())) + "/void"), bearer(owner), "{\"reason\":\"prueba\"}");
            } else if (action < 9) {
                call(post(base(b) + "/credits/" + credits.get(rnd.nextInt(credits.size())) + "/write-off"), bearer(owner), "{\"reason\":\"prueba\"}");
            } else {
                UUID credit = credits.get(rnd.nextInt(credits.size()));
                call(post(base(b) + "/credits/" + credit + "/link-customer"), bearer(owner), "{\"customerId\":\"" + customers.get(rnd.nextInt(customers.size())) + "\"}");
            }
            assertLedgerBalances(b);
        }
    }

    private void assertLedgerBalances(UUID business) {
        long badCredits = jdbc.sql("""
                        SELECT count(*) FROM credit c WHERE c.business_id = :b AND c.balance_minor <>
                               CASE WHEN c.status IN ('WRITTEN_OFF', 'CANCELLED') THEN 0
                                    ELSE greatest(0, c.amount_minor - coalesce((SELECT sum(amount_minor) FROM credit_payment p WHERE p.credit_id = c.id AND p.voided_at IS NULL), 0)) END
                        """)
                .param("b", business).query(Long.class).single();
        long badCustomers = jdbc.sql("""
                        SELECT count(*) FROM customer cu WHERE cu.business_id = :b AND cu.balance_minor <> coalesce((SELECT sum(balance_minor) FROM credit WHERE customer_id = cu.id), 0)
                        """)
                .param("b", business).query(Long.class).single();
        long badStatus = jdbc.sql("SELECT count(*) FROM credit WHERE business_id = :b AND ((status = 'PAID' AND balance_minor <> 0) OR (status = 'OPEN' AND balance_minor = 0))")
                .param("b", business).query(Long.class).single();
        org.junit.jupiter.api.Assertions.assertEquals(0, badCredits, "saldo de un fiado descuadrado");
        org.junit.jupiter.api.Assertions.assertEquals(0, badCustomers, "saldo de un cliente descuadrado");
        org.junit.jupiter.api.Assertions.assertEquals(0, badStatus, "estado que no corresponde al saldo");
    }
}
