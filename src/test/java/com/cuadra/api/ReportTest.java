package com.cuadra.api;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Los totales de los reportes se comprueban contra un escenario sembrado a mano (con los números calculados de antemano) Y contra consultas
 * SQL de control independientes: dos caminos que deben coincidir.
 */
class ReportTest extends ApiTestBase {
    @Autowired JdbcClient jdbc;

    private String base(UUID b) { return "/api/b/" + b; }

    private static final String RANGE = "from=2026-09-20&to=2026-09-21";

    private static String item(UUID product, String name, long price, Long cost, long qtyMilli) {
        return "{\"id\":\"" + UUID.randomUUID() + "\"" + (product == null ? "" : ",\"productId\":\"" + product + "\"") + ",\"name\":\"" + name.replace("\"", "\\\"") + "\",\"unitPriceMinor\":" + price
                + (cost == null ? "" : ",\"unitCostMinor\":" + cost) + ",\"quantityMilli\":" + qtyMilli + "}";
    }

    private UUID sale(String token, UUID b, String items, String payments, String at, long discount) throws Exception {
        UUID id = UUID.randomUUID();
        call(put(base(b) + "/sales/" + id), bearer(token), "{\"status\":\"COMPLETED\",\"items\":[" + items + "],\"payments\":[" + payments + "],\"completedAt\":\"" + at + "\"" + (discount > 0 ? ",\"discountMinor\":" + discount : "") + "}")
                .andExpect(status().isCreated());
        return id;
    }

    private UUID product(String token, UUID b, String name, long price, Long cost, boolean track) throws Exception {
        UUID id = UUID.randomUUID();
        call(put(base(b) + "/products/" + id), bearer(token), "{\"name\":\"" + name + "\",\"priceMinor\":" + price + (cost == null ? "" : ",\"costMinor\":" + cost) + ",\"trackStock\":" + track + ",\"minStockMilli\":1000}").andExpect(status().isCreated());
        return id;
    }

    private UUID category(String token, UUID b, String key) throws Exception {
        String json = call(get(base(b) + "/expense-categories"), bearer(token), null).andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(json, "$[?(@.key=='" + key + "')].id");
        return UUID.fromString(ids.get(0));
    }

    private UUID expense(String token, UUID b, long amount, String source, UUID category, String at) throws Exception {
        UUID id = UUID.randomUUID();
        call(put(base(b) + "/expenses/" + id), bearer(token), "{\"amountMinor\":" + amount + ",\"source\":\"" + source + "\",\"categoryId\":\"" + category + "\",\"occurredAt\":\"" + at + "\"}").andExpect(status().isCreated());
        return id;
    }

    /** El escenario: 3 ventas en 2 jornadas, una con línea sin costo, un descuento, tres métodos de pago, gastos (uno de mercadería y uno anulado). */
    private UUID seed(String owner) throws Exception {
        UUID b = createBusiness(owner, "Reportes");
        UUID a = product(owner, b, "Queso", 10000, 6000L, false);
        UUID p = product(owner, b, "Crema", 5000, 3000L, false);
        String d20 = "2026-09-20T18:00:00Z";   // 12:00 en Managua
        String d21 = "2026-09-21T18:00:00Z";
        sale(owner, b, item(a, "Queso", 10000, 6000L, 2000) + "," + item(p, "Crema", 5000, 3000L, 1000), SaleTest.pay("CASH", 25000, ""), d20, 0);
        sale(owner, b, item(a, "Queso", 10000, 6000L, 1000) + "," + item(null, "Varios", 4000, null, 1000), SaleTest.pay("CASH", 5000, "") + "," + SaleTest.pay("TRANSFER", 9000, ""), d20, 0);
        sale(owner, b, item(p, "Crema", 5000, 3000L, 3000), SaleTest.pay("CARD", 14000, ""), d21, 1000);
        // Una venta eliminada no cuenta.
        UUID gone = sale(owner, b, item(a, "Queso", 10000, 6000L, 1000), SaleTest.pay("CASH", 10000, ""), d21, 0);
        call(post(base(b) + "/sales/" + gone + "/cancel"), bearer(owner), "{}").andExpect(status().isOk());
        expense(owner, b, 2000, "CASH_DRAWER", category(owner, b, "utilities"), d20);
        expense(owner, b, 8000, "BANK", category(owner, b, "rent"), d21);
        expense(owner, b, 4000, "CASH_DRAWER", category(owner, b, "goods"), d21);
        UUID voided = expense(owner, b, 999, "OTHER", category(owner, b, "other"), d21);
        call(post(base(b) + "/expenses/" + voided + "/void"), bearer(owner), "{}").andExpect(status().isOk());
        return b;
    }

    @Test
    void theSalesAndProfitTotalsMatchTheSeededScenarioAndTheControlQueries() throws Exception {
        String owner = login("rpta");
        UUID b = seed(owner);
        call(get(base(b) + "/reports/sales?" + RANGE), bearer(owner), null).andExpect(status().isOk()).andExpect(jsonPath("$.sales.count", is(3))).andExpect(jsonPath("$.sales.totalMinor", is(53000)))
                .andExpect(jsonPath("$.sales.discountMinor", is(1000))).andExpect(jsonPath("$.sales.averageTicketMinor", is(17667)))
                .andExpect(jsonPath("$.byMethod[?(@.method=='CASH')].amountMinor", contains(30000))).andExpect(jsonPath("$.byMethod[?(@.method=='TRANSFER')].amountMinor", contains(9000)))
                .andExpect(jsonPath("$.byMethod[?(@.method=='CARD')].amountMinor", contains(14000)));
        // ganancia = 53000 − 30000 (costo) − 10000 (2000 luz + 8000 renta; la mercadería y lo anulado no cuentan) = 13000; cobertura de costo 50000/54000 = 93 %.
        call(get(base(b) + "/reports/profit?" + RANGE), bearer(owner), null).andExpect(jsonPath("$.salesMinor", is(53000))).andExpect(jsonPath("$.costOfGoodsMinor", is(30000)))
                .andExpect(jsonPath("$.operatingExpensesMinor", is(10000))).andExpect(jsonPath("$.purchasesExcludedMinor", is(4000))).andExpect(jsonPath("$.estimatedProfitMinor", is(13000)))
                .andExpect(jsonPath("$.costCoveragePercent", is(93)));
        // Consultas de control, escritas aparte y sin pasar por el servicio.
        assertEquals(53000L, jdbc.sql("SELECT sum(total_minor) FROM sale WHERE business_id = :b AND status = 'COMPLETED'").param("b", b).query(Long.class).single());
        assertEquals(30000L, jdbc.sql("SELECT sum(unit_cost_minor * quantity_milli / 1000) FROM sale_item i JOIN sale s ON s.id = i.sale_id WHERE s.business_id = :b AND s.status = 'COMPLETED'").param("b", b).query(Long.class).single());
        assertEquals(10000L, jdbc.sql("SELECT sum(amount_minor) FROM expense WHERE business_id = :b AND voided_at IS NULL AND category_id NOT IN (SELECT id FROM expense_category WHERE key = 'goods')").param("b", b).query(Long.class).single());
    }

    @Test
    void breakdownsByMemberRegisterMethodHourAndDay() throws Exception {
        String owner = login("rptb");
        UUID b = seed(owner);
        call(get(base(b) + "/reports/sales/breakdown?by=member&" + RANGE), bearer(owner), null).andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].count", is(3))).andExpect(jsonPath("$[0].totalMinor", is(53000)));
        call(get(base(b) + "/reports/sales/breakdown?by=register&" + RANGE), bearer(owner), null).andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].totalMinor", is(53000)));
        call(get(base(b) + "/reports/sales/breakdown?by=method&" + RANGE), bearer(owner), null).andExpect(jsonPath("$[0].label", is("CASH"))).andExpect(jsonPath("$[0].totalMinor", is(30000)));
        call(get(base(b) + "/reports/sales/breakdown?by=hour&" + RANGE), bearer(owner), null).andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].key", is("12"))).andExpect(jsonPath("$[0].count", is(3)));
        call(get(base(b) + "/reports/sales/breakdown?by=day&" + RANGE), bearer(owner), null).andExpect(jsonPath("$", hasSize(2))).andExpect(jsonPath("$[0].key", is("2026-09-20"))).andExpect(jsonPath("$[0].totalMinor", is(39000)))
                .andExpect(jsonPath("$[1].key", is("2026-09-21"))).andExpect(jsonPath("$[1].totalMinor", is(14000)));
        call(get(base(b) + "/reports/sales/breakdown?by=weather&" + RANGE), bearer(owner), null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("INVALID_GROUPING")));
    }

    @Test
    void topProductsBySalesQuantityAndProfitWithAFlagForLinesWithoutCost() throws Exception {
        String owner = login("rptc");
        UUID b = seed(owner);
        call(get(base(b) + "/reports/products?sort=revenue&" + RANGE), bearer(owner), null).andExpect(jsonPath("$[*].name", contains("Queso", "Crema", "Varios"))).andExpect(jsonPath("$[0].revenueMinor", is(30000)))
                .andExpect(jsonPath("$[0].costMinor", is(18000))).andExpect(jsonPath("$[0].profitMinor", is(12000))).andExpect(jsonPath("$[0].quantityMilli", is(3000))).andExpect(jsonPath("$[2].fullyCosted", is(false)));
        call(get(base(b) + "/reports/products?sort=quantity&" + RANGE), bearer(owner), null).andExpect(jsonPath("$[*].name", contains("Crema", "Queso", "Varios")));
        call(get(base(b) + "/reports/products?sort=profit&" + RANGE), bearer(owner), null).andExpect(jsonPath("$[*].name", contains("Queso", "Crema", "Varios"))).andExpect(jsonPath("$[1].profitMinor", is(8000)));
        call(get(base(b) + "/reports/products?sort=luck&" + RANGE), bearer(owner), null).andExpect(status().isBadRequest());
    }

    @Test
    void expensesByCategorySeparateWhatLeavesTheDrawerAndTheMerchandise() throws Exception {
        String owner = login("rptd");
        UUID b = seed(owner);
        call(get(base(b) + "/reports/expenses?" + RANGE), bearer(owner), null).andExpect(jsonPath("$.operatingMinor", is(10000))).andExpect(jsonPath("$.purchasesMinor", is(4000)))
                .andExpect(jsonPath("$.cashDrawerMinor", is(6000))).andExpect(jsonPath("$.otherMinor", is(8000)))
                .andExpect(jsonPath("$.byCategory[?(@.key=='rent')].amountMinor", contains(8000))).andExpect(jsonPath("$.byCategory[?(@.key=='utilities')].amountMinor", contains(2000)))
                .andExpect(jsonPath("$.byCategory[?(@.key=='other')]", hasSize(0)));
    }

    @Test
    void overviewGivesADailySeriesWithZeroDaysAndTheSameTotals() throws Exception {
        String owner = login("rpte");
        UUID b = seed(owner);
        call(get(base(b) + "/reports/overview?from=2026-09-19&to=2026-09-22"), bearer(owner), null).andExpect(jsonPath("$.series", hasSize(4))).andExpect(jsonPath("$.series[0].salesMinor", is(0)))
                .andExpect(jsonPath("$.series[1].salesMinor", is(39000))).andExpect(jsonPath("$.series[1].expensesMinor", is(2000))).andExpect(jsonPath("$.series[2].salesMinor", is(14000)))
                .andExpect(jsonPath("$.series[2].expensesMinor", is(8000))).andExpect(jsonPath("$.series[3].salesMinor", is(0))).andExpect(jsonPath("$.profit.estimatedProfitMinor", is(13000)))
                .andExpect(jsonPath("$.topProducts[0].name", is("Queso"))).andExpect(jsonPath("$.range.currency", is("NIO")));
    }

    @Test
    void receivablesAreAgedInFourBucketsAndTheWorstDebtorComesFirst() throws Exception {
        String owner = login("rptf");
        UUID b = createBusiness(owner, "Por cobrar");
        Instant now = Instant.now();
        long[][] credits = {{8, 5000}, {20, 7000}, {45, 3000}, {90, 2000}};
        String[] names = {"Marta", "Luis", "Marta", "Ana"};
        for (int i = 0; i < credits.length; i++) {
            String at = now.minus(credits[i][0], ChronoUnit.DAYS).toString();
            sale(owner, b, item(null, "Fiado", credits[i][1], null, 1000), "{\"id\":\"" + UUID.randomUUID() + "\",\"method\":\"CREDIT\",\"amountMinor\":" + credits[i][1] + ",\"debtorLabel\":\"" + names[i] + "\"}", at, 0);
        }
        call(get(base(b) + "/reports/receivables"), bearer(owner), null).andExpect(jsonPath("$.totalMinor", is(17000))).andExpect(jsonPath("$.openCount", is(4)))
                .andExpect(jsonPath("$.buckets[0].amountMinor", is(5000))).andExpect(jsonPath("$.buckets[1].amountMinor", is(7000))).andExpect(jsonPath("$.buckets[2].amountMinor", is(3000)))
                .andExpect(jsonPath("$.buckets[3].amountMinor", is(2000))).andExpect(jsonPath("$.buckets[3].key", is("60+")))
                // Marta debe 5000 + 3000 = 8000 (dos fiados): es la de más deuda; su fiado más viejo tiene 45 días.
                .andExpect(jsonPath("$.worst[0].name", is("Marta"))).andExpect(jsonPath("$.worst[0].balanceMinor", is(8000))).andExpect(jsonPath("$.worst[0].oldestDays", is(45)));
        assertEquals(17000L, jdbc.sql("SELECT sum(balance_minor) FROM credit WHERE business_id = :b AND status = 'OPEN'").param("b", b).query(Long.class).single());
    }

    @Test
    void closingsShowTheDifferencePerPersonAndInventoryValuesStockAtCost() throws Exception {
        String owner = login("rptg");
        UUID b = createBusiness(owner, "Cierres");
        String cashier = joinAs(owner, b, "rptg2", "CASHIER");
        UUID shift = UUID.randomUUID();
        call(put(base(b) + "/shifts/" + shift), bearer(cashier), "{\"openingFloatMinor\":100000}").andExpect(status().isCreated());
        call(post(base(b) + "/shifts/" + shift + "/close"), bearer(cashier), "{\"countedMinor\":99000}").andExpect(status().isOk());
        call(get(base(b) + "/reports/closings"), bearer(owner), null).andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].shifts", is(1))).andExpect(jsonPath("$[0].differenceMinor", is(-1000)))
                .andExpect(jsonPath("$[0].absoluteDifferenceMinor", is(1000))).andExpect(jsonPath("$[0].lines[0].expectedMinor", is(100000)));

        UUID withCost = product(owner, b, "Leche", 5000, 3000L, true);
        UUID noCost = product(owner, b, "Pan", 1000, null, true);
        for (UUID p : List.of(withCost, noCost)) call(put(base(b) + "/stock-movements/" + UUID.randomUUID()), bearer(owner), "{\"productId\":\"" + p + "\",\"kind\":\"INITIAL\",\"countedMilli\":" + (p.equals(withCost) ? 4000 : 500) + "}").andExpect(status().isCreated());
        // Leche: 4 × 30.00 = 120.00; Pan queda sin costo. Pan está en su mínimo (0.5 ≤ 1): stock bajo. Ninguno se vendió en 30 días.
        call(get(base(b) + "/reports/inventory"), bearer(owner), null).andExpect(jsonPath("$.valueAtCostMinor", is(12000))).andExpect(jsonPath("$.trackedCount", is(2))).andExpect(jsonPath("$.trackedWithoutCost", is(1)))
                .andExpect(jsonPath("$.low[*].name", contains("Pan"))).andExpect(jsonPath("$.noMovement", hasSize(2)));
    }

    @Test
    void csvExportsQuoteCellsNeutralizeFormulasAndUseTheRequestedLanguage() throws Exception {
        String owner = login("rpth");
        UUID b = createBusiness(owner, "Csv");
        sale(owner, b, item(null, "Queso, \"fresco\"", 10000, null, 1000) + "," + item(null, "=HYPERLINK(1)", 500, null, 1000), SaleTest.pay("CASH", 10500, ""), "2026-09-20T18:00:00Z", 0);
        String csv = call(get(base(b) + "/reports/sale-items.csv?lang=en&" + RANGE), bearer(owner), null).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andReturn().getResponse().getContentAsString();
        org.hamcrest.MatcherAssert.assertThat(csv, startsWith("﻿sale,date,product,quantity,price,cost,discount\r\n"));
        org.hamcrest.MatcherAssert.assertThat(csv, containsString("\"Queso, \"\"fresco\"\"\""));
        org.hamcrest.MatcherAssert.assertThat(csv, containsString("'=HYPERLINK(1)"));
        org.hamcrest.MatcherAssert.assertThat(csv, containsString(",1,100.00,,0.00"));
        String es = call(get(base(b) + "/reports/sales.csv?" + RANGE), bearer(owner), null).andReturn().getResponse().getContentAsString();
        org.hamcrest.MatcherAssert.assertThat(es, startsWith("﻿id,fecha,cajero,caja,subtotal,descuento,total,pagos\r\n"));
        org.hamcrest.MatcherAssert.assertThat(es, containsString("2026-09-20 12:00:00"));
        org.hamcrest.MatcherAssert.assertThat(es, containsString("105.00"));
        call(get(base(b) + "/reports/sales/breakdown.csv?by=day&" + RANGE), bearer(owner), null).andExpect(status().isOk());
        String profit = call(get(base(b) + "/reports/profit.csv?lang=en&" + RANGE), bearer(owner), null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        org.hamcrest.MatcherAssert.assertThat(profit, startsWith("\uFEFFitem,amount\r\nsales,105.00\r\n"));
        call(get(base(b) + "/reports/inventory.csv"), bearer(owner), null).andExpect(status().isOk());
    }

    @Test
    void onlyOwnersAndAdminsSeeReportsAndAnotherBusinessSeesNothing() throws Exception {
        String owner = login("rpti");
        UUID b = seed(owner);
        String admin = joinAs(owner, b, "rpti2", "ADMIN");
        String cashier = joinAs(owner, b, "rpti3", "CASHIER");
        call(get(base(b) + "/reports/overview?" + RANGE), bearer(admin), null).andExpect(status().isOk());
        call(get(base(b) + "/reports/overview?" + RANGE), bearer(cashier), null).andExpect(status().isForbidden());
        call(get(base(b) + "/reports/sales.csv?" + RANGE), bearer(cashier), null).andExpect(status().isForbidden());
        String other = login("rpti4");
        UUID b2 = createBusiness(other, "Otro");
        call(get(base(b2) + "/reports/sales?" + RANGE), bearer(other), null).andExpect(jsonPath("$.sales.count", is(0))).andExpect(jsonPath("$.sales.totalMinor", is(0)));
        call(get(base(b) + "/reports/sales?" + RANGE), bearer(other), null).andExpect(status().isNotFound());
    }

    @Test
    void rangesAreValidated() throws Exception {
        String owner = login("rptj");
        UUID b = createBusiness(owner, "Rangos");
        call(get(base(b) + "/reports/sales?from=2026-09-21&to=2026-09-20"), bearer(owner), null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("INVALID_RANGE")));
        call(get(base(b) + "/reports/sales?from=2024-01-01&to=2026-09-20"), bearer(owner), null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("RANGE_TOO_LONG")));
        call(get(base(b) + "/reports/sales"), bearer(owner), null).andExpect(status().isOk()).andExpect(jsonPath("$.range.from", notNullString()));
    }

    private static org.hamcrest.Matcher<Object> notNullString() { return org.hamcrest.Matchers.notNullValue(); }
}
