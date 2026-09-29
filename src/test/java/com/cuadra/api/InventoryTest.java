package com.cuadra.api;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cuadra.api.stock.PurchaseService;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class InventoryTest extends ApiTestBase {
    @Autowired JdbcClient jdbc;

    private String base(UUID b) { return "/api/b/" + b; }

    private UUID product(String owner, UUID b, String name, boolean track, Long minMilli) throws Exception {
        UUID id = UUID.randomUUID();
        call(put(base(b) + "/products/" + id), bearer(owner), "{\"name\":\"" + name + "\",\"priceMinor\":10000,\"trackStock\":" + track + (minMilli == null ? "" : ",\"minStockMilli\":" + minMilli) + "}")
                .andExpect(status().isCreated());
        return id;
    }

    private void count(String token, UUID b, UUID product, String kind, long counted) throws Exception {
        call(put(base(b) + "/stock-movements/" + UUID.randomUUID()), bearer(token), "{\"productId\":\"" + product + "\",\"kind\":\"" + kind + "\",\"countedMilli\":" + counted + "}").andExpect(status().is2xxSuccessful());
    }

    private long stock(UUID product) {
        return jdbc.sql("SELECT stock_milli FROM product WHERE id = :p").param("p", product).query(Long.class).single();
    }

    private long movementsSum(UUID product) {
        return jdbc.sql("SELECT coalesce(sum(quantity_milli), 0) FROM stock_movement WHERE product_id = :p").param("p", product).query(Long.class).single();
    }

    private static String line(UUID product, long qtyMilli, long cost) {
        return "{\"id\":\"" + UUID.randomUUID() + "\",\"productId\":\"" + product + "\",\"quantityMilli\":" + qtyMilli + ",\"unitCostMinor\":" + cost + "}";
    }

    private static String purchase(String lines, String extra) {
        return "{\"lines\":[" + lines + "]" + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    private static String saleWith(UUID product, long qtyMilli, String status) {
        long total = 10000 * qtyMilli / 1000;
        String item = "{\"id\":\"" + UUID.randomUUID() + "\",\"productId\":\"" + product + "\",\"name\":\"x\",\"unitPriceMinor\":10000,\"quantityMilli\":" + qtyMilli + "}";
        return SaleTest.sale(status, item, SaleTest.pay("CASH", total, ""), "");
    }

    // ---------- existencias ----------

    @Test
    void aProductWithoutTrackingGeneratesNoMovementsAndCannotBeCounted() throws Exception {
        String owner = login("inva");
        UUID b = createBusiness(owner, "Inv A");
        UUID plain = product(owner, b, "Sin control", false, null);
        call(put(base(b) + "/sales/" + UUID.randomUUID()), bearer(owner), saleWith(plain, 2000, "COMPLETED")).andExpect(status().isCreated());
        assertEquals(0, jdbc.sql("SELECT count(*) FROM stock_movement WHERE product_id = :p").param("p", plain).query(Integer.class).single());
        call(put(base(b) + "/stock-movements/" + UUID.randomUUID()), bearer(owner), "{\"productId\":\"" + plain + "\",\"kind\":\"INITIAL\",\"countedMilli\":5000}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("STOCK_NOT_TRACKED")));
    }

    @Test
    void countingSalesEditsAndDeletionsKeepTheStockEqualToTheSumOfMovements() throws Exception {
        String owner = login("invb");
        UUID b = createBusiness(owner, "Inv B");
        UUID cuajada = product(owner, b, "Cuajada", true, 2000L);
        count(owner, b, cuajada, "INITIAL", 10000);
        assertEquals(10000, stock(cuajada));

        UUID sale = UUID.randomUUID();
        call(put(base(b) + "/sales/" + sale), bearer(owner), saleWith(cuajada, 3000, "COMPLETED")).andExpect(status().isCreated());
        assertEquals(7000, stock(cuajada));
        // Repetir la misma venta no vuelve a descontar.
        call(put(base(b) + "/sales/" + sale), bearer(owner), saleWith(cuajada, 3000, "COMPLETED"));
        // Editarla a 5: solo se descuenta la diferencia.
        call(put(base(b) + "/sales/" + sale), bearer(owner), saleWith(cuajada, 5000, "COMPLETED")).andExpect(status().isOk());
        assertEquals(5000, stock(cuajada));
        // Eliminarla devuelve todo.
        call(post(base(b) + "/sales/" + sale + "/cancel"), bearer(owner), "{\"reason\":\"error de cobro\"}").andExpect(status().isOk());
        assertEquals(10000, stock(cuajada));
        assertEquals(stock(cuajada), movementsSum(cuajada));
        // El historial conserva cada paso: nada se borra.
        call(get(base(b) + "/products/" + cuajada + "/stock-movements"), bearer(owner), null).andExpect(jsonPath("$.items[*].kind", hasItem("SALE_REVERSAL"))).andExpect(jsonPath("$.items[*].kind", hasItem("SALE")));
    }

    @Test
    void anAdjustmentSetsTheCountedValueAndDamageAndNegativeStockAreAllowed() throws Exception {
        String owner = login("invc");
        UUID b = createBusiness(owner, "Inv C");
        UUID p = product(owner, b, "Crema", true, 3000L);
        count(owner, b, p, "INITIAL", 4000);
        count(owner, b, p, "ADJUSTMENT", 3500);
        assertEquals(3500, stock(p));
        call(put(base(b) + "/stock-movements/" + UUID.randomUUID()), bearer(owner), "{\"productId\":\"" + p + "\",\"kind\":\"DAMAGE\",\"quantityMilli\":1000,\"note\":\"Se venció\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.quantityMilli", is(-1000)));
        assertEquals(2500, stock(p));
        // Vender más de lo que hay no se bloquea: queda negativo y aparece en Revisar.
        call(put(base(b) + "/sales/" + UUID.randomUUID()), bearer(owner), saleWith(p, 4000, "COMPLETED")).andExpect(status().isCreated());
        assertEquals(-1500, stock(p));
        call(get(base(b) + "/stock/review"), bearer(owner), null).andExpect(jsonPath("$[0].name", is("Crema"))).andExpect(jsonPath("$[0].negative", is(true)));
        // Un conteo dos veces con el mismo id no duplica.
        UUID same = UUID.randomUUID();
        for (int i = 0; i < 2; i++) call(put(base(b) + "/stock-movements/" + same), bearer(owner), "{\"productId\":\"" + p + "\",\"kind\":\"ADJUSTMENT\",\"countedMilli\":0}");
        assertEquals(0, stock(p));
        assertEquals(stock(p), movementsSum(p));
    }

    @Test
    void aSaleMadeBeforeTrackingStartedIsNotDeductedWhenItIsEditedLater() throws Exception {
        String owner = login("invd");
        UUID b = createBusiness(owner, "Inv D");
        UUID p = product(owner, b, "Queso", false, null);
        UUID old = UUID.randomUUID();
        call(put(base(b) + "/sales/" + old), bearer(owner), saleWith(p, 2000, "COMPLETED")).andExpect(status().isCreated());
        // Se enciende el control y se cuenta: esa venta anterior ya está reflejada en el conteo.
        call(put(base(b) + "/products/" + p), bearer(owner), "{\"name\":\"Queso\",\"priceMinor\":10000,\"trackStock\":true}").andExpect(status().isOk());
        jdbc.sql("UPDATE sale SET completed_at = now() - interval '1 hour' WHERE id = :s").param("s", old).update();
        jdbc.sql("UPDATE product SET stock_tracked_since = now() WHERE id = :p").param("p", p).update();
        count(owner, b, p, "INITIAL", 8000);
        call(put(base(b) + "/sales/" + old), bearer(owner), saleWith(p, 3000, "COMPLETED")).andExpect(status().isOk());
        assertEquals(8000, stock(p));
    }

    // ---------- compras, proveedores y cuentas por pagar ----------

    @Test
    void aPurchaseAddsStockUpdatesTheCostCreatesTheExpenseAndLeavesTheRestOwed() throws Exception {
        String owner = login("inve");
        UUID b = createBusiness(owner, "Inv E");
        UUID p = product(owner, b, "Leche", true, null);
        count(owner, b, p, "INITIAL", 0);
        UUID supplier = UUID.randomUUID();
        call(put(base(b) + "/suppliers/" + supplier), bearer(owner), "{\"name\":\"Lácteos del Norte\",\"phone\":\"88123456\"}").andExpect(status().isOk()).andExpect(jsonPath("$.phone", is("50588123456")));
        UUID id = UUID.randomUUID();
        call(put(base(b) + "/purchases/" + id), bearer(owner), purchase(line(p, 10000, 2500), "\"supplierId\":\"" + supplier + "\",\"paidMinor\":10000,\"paidSource\":\"BANK\""))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.totalMinor", is(25000))).andExpect(jsonPath("$.paidMinor", is(10000))).andExpect(jsonPath("$.balanceMinor", is(15000)))
                .andExpect(jsonPath("$.supplierName", is("Lácteos del Norte")));
        assertEquals(10000, stock(p));
        assertEquals(2500L, jdbc.sql("SELECT cost_minor FROM product WHERE id = :p").param("p", p).query(Long.class).single());
        call(get(base(b) + "/expenses"), bearer(owner), null).andExpect(jsonPath("$.items", hasSize(1))).andExpect(jsonPath("$.items[0].source", is("BANK"))).andExpect(jsonPath("$.items[0].amountMinor", is(10000)))
                .andExpect(jsonPath("$.items[0].categoryKey", is("goods")));
        call(get(base(b) + "/suppliers"), bearer(owner), null).andExpect(jsonPath("$[0].balanceMinor", is(15000)));

        // Pagar el resto; pasarse no se rechaza y el saldo no baja de cero.
        UUID pay = UUID.randomUUID();
        call(put(base(b) + "/supplier-payments/" + pay), bearer(owner), "{\"purchaseId\":\"" + id + "\",\"amountMinor\":16000,\"source\":\"CASH_DRAWER\"}").andExpect(status().isCreated());
        call(get(base(b) + "/suppliers"), bearer(owner), null).andExpect(jsonPath("$[0].balanceMinor", is(0)));
        // El gasto ligado a un pago no se anula suelto.
        call(post(base(b) + "/expenses/" + pay + "/void"), bearer(owner), "{}").andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("EXPENSE_LINKED")));
        call(post(base(b) + "/supplier-payments/" + pay + "/void"), bearer(owner), "{\"reason\":\"error\"}").andExpect(status().isOk()).andExpect(jsonPath("$.voided", is(true)));
        call(get(base(b) + "/suppliers"), bearer(owner), null).andExpect(jsonPath("$[0].balanceMinor", is(15000)));
        call(get(base(b) + "/expenses?includeVoided=true"), bearer(owner), null).andExpect(jsonPath("$.items[?(@.id=='" + pay + "')].voided", contains(true)));
    }

    @Test
    void aPurchaseWithNoSupplierOnlyNeedsANameAndVoidingItUndoesStockAndPayments() throws Exception {
        String owner = login("invf");
        UUID b = createBusiness(owner, "Inv F");
        UUID p = product(owner, b, "Arroz", true, null);
        count(owner, b, p, "INITIAL", 1000);
        UUID id = UUID.randomUUID();
        call(put(base(b) + "/purchases/" + id), bearer(owner), purchase(line(p, 5000, 1000) + ",{\"id\":\"" + UUID.randomUUID() + "\",\"name\":\"Bolsas\",\"quantityMilli\":1000,\"unitCostMinor\":300}",
                "\"supplierName\":\"Don Pedro\",\"paidMinor\":5300,\"paidSource\":\"CASH_DRAWER\"")).andExpect(status().isCreated()).andExpect(jsonPath("$.supplierName", is("Don Pedro")))
                .andExpect(jsonPath("$.balanceMinor", is(0))).andExpect(jsonPath("$.lines", hasSize(2)));
        assertEquals(6000, stock(p));
        // Repetir la compra no duplica nada; con otro contenido bajo el mismo id se rechaza: una compra no se edita.
        call(put(base(b) + "/purchases/" + id), bearer(owner), purchase(line(p, 5000, 1000) + ",{\"id\":\"" + UUID.randomUUID() + "\",\"name\":\"Bolsas\",\"quantityMilli\":1000,\"unitCostMinor\":300}", "\"supplierName\":\"Don Pedro\""))
                .andExpect(status().isOk());
        assertEquals(6000, stock(p));
        call(put(base(b) + "/purchases/" + id), bearer(owner), purchase(line(p, 9000, 1000), "")).andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("PURCHASE_IMMUTABLE")));
        // Pagar el resto de una compra SIN proveedor registrado (solo nombre) funciona igual.
        call(put(base(b) + "/supplier-payments/" + UUID.randomUUID()), bearer(owner), "{\"purchaseId\":\"" + id + "\",\"amountMinor\":1,\"source\":\"OTHER\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.supplierId", nullValue()));
        call(post(base(b) + "/purchases/" + id + "/void"), bearer(owner), "{\"reason\":\"duplicada\"}").andExpect(status().isOk()).andExpect(jsonPath("$.voided", is(true))).andExpect(jsonPath("$.balanceMinor", is(0)));
        assertEquals(1000, stock(p));
        assertEquals(stock(p), movementsSum(p));
        call(get(base(b) + "/expenses"), bearer(owner), null).andExpect(jsonPath("$.items", hasSize(0)));
        call(post(base(b) + "/purchases/" + id + "/void"), bearer(owner), "{}").andExpect(status().isOk());
        assertEquals(1000, stock(p));
    }

    @Test
    void aPurchaseValidatesItsLinesAndAmounts() throws Exception {
        String owner = login("invg");
        UUID b = createBusiness(owner, "Inv G");
        UUID p = product(owner, b, "Sal", true, null);
        call(put(base(b) + "/purchases/" + UUID.randomUUID()), bearer(owner), purchase("", "")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("INVALID_LINES")));
        call(put(base(b) + "/purchases/" + UUID.randomUUID()), bearer(owner), purchase(line(p, 0, 100), "")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("INVALID_QUANTITY")));
        call(put(base(b) + "/purchases/" + UUID.randomUUID()), bearer(owner), purchase(line(p, 1000, 100), "\"paidMinor\":999")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("INVALID_PAID")));
        call(put(base(b) + "/purchases/" + UUID.randomUUID()), bearer(owner), purchase(line(p, 1000, 100), "\"paidMinor\":100")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("INVALID_SOURCE")));
        call(put(base(b) + "/purchases/" + UUID.randomUUID()), bearer(owner), purchase(line(UUID.randomUUID(), 1000, 100), "")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("INVALID_PRODUCT")));
    }

    @Test
    void aCashierCannotTouchStockPurchasesOrSuppliersAndDoesNotReceiveThemOnPull() throws Exception {
        String owner = login("invh");
        UUID b = createBusiness(owner, "Inv H");
        UUID member = createPinMember(owner, b, "Kevin", "CASHIER");
        String device = linkDevice(owner, b);
        UUID p = product(owner, b, "Pan", true, null);
        count(owner, b, p, "INITIAL", 5000);
        UUID purchase = UUID.randomUUID();
        call(put(base(b) + "/purchases/" + purchase), bearer(owner), purchase(line(p, 1000, 100), "\"supplierName\":\"X\"")).andExpect(status().isCreated());

        asDevice(put(base(b) + "/stock-movements/" + UUID.randomUUID()), device, member, "{\"productId\":\"" + p + "\",\"kind\":\"DAMAGE\",\"quantityMilli\":1000}").andExpect(status().isForbidden());
        asDevice(put(base(b) + "/purchases/" + UUID.randomUUID()), device, member, purchase(line(p, 1000, 100), "")).andExpect(status().isForbidden());
        asDevice(put(base(b) + "/suppliers/" + UUID.randomUUID()), device, member, "{\"name\":\"Y\"}").andExpect(status().isForbidden());
        asDevice(get(base(b) + "/suppliers"), device, member, null).andExpect(status().isForbidden());

        String pull = asDevice(get(base(b) + "/sync/pull?since=0&limit=500"), device, member, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> types = JsonPath.read(pull, "$.changes[*].type");
        org.hamcrest.MatcherAssert.assertThat(types, hasItem("product"));
        org.hamcrest.MatcherAssert.assertThat(types, not(hasItem("stock_movement")));
        org.hamcrest.MatcherAssert.assertThat(types, not(hasItem("purchase")));
        // El producto sí llega con su existencia, para que el cajero vea cuánto queda.
        List<Integer> stocks = JsonPath.read(pull, "$.changes[?(@.type=='product')].data.stockMilli");
        assertEquals(6000, stocks.get(0));
    }

    @Test
    void purchasesAndStockOperationsSyncFromAManagersPhoneAndAreIdempotent() throws Exception {
        String owner = login("invi");
        UUID b = createBusiness(owner, "Inv I");
        UUID admin = createPinMember(owner, b, "Ana", "ADMIN");
        String device = linkDevice(owner, b);
        UUID p = product(owner, b, "Café", true, null);
        UUID purchase = UUID.randomUUID();
        String payload = purchase(line(p, 2000, 500), "\"supplierName\":\"Finca\",\"paidMinor\":500,\"paidSource\":\"CASH_DRAWER\"");
        UUID opId = UUID.randomUUID();
        String batch = "{\"ops\":[{\"opId\":\"" + opId + "\",\"kind\":\"PURCHASE_REGISTER\",\"entityId\":\"" + purchase + "\",\"payload\":" + payload + "}]}";
        for (int i = 0; i < 2; i++) asDevice(post(base(b) + "/sync/push"), device, admin, batch).andExpect(status().isOk());
        assertEquals(2000, stock(p));
        assertEquals(1, jdbc.sql("SELECT count(*) FROM purchase WHERE id = :p").param("p", purchase).query(Integer.class).single());
        // El teléfono calcula los mismos ids de movimiento y de pago que el servidor.
        assertEquals(1, jdbc.sql("SELECT count(*) FROM stock_movement WHERE id = :m").param("m", PurchaseService.movementId(purchase, UUID.fromString(JsonPath.read(payload, "$.lines[0].id")))).query(Integer.class).single());
        assertEquals(1, jdbc.sql("SELECT count(*) FROM expense WHERE id = :e AND source = 'CASH_DRAWER'").param("e", PurchaseService.firstPaymentId(purchase)).query(Integer.class).single());
        String pull = asDevice(get(base(b) + "/sync/pull?since=0&limit=500"), device, admin, null).andReturn().getResponse().getContentAsString();
        List<String> types = JsonPath.read(pull, "$.changes[*].type");
        org.hamcrest.MatcherAssert.assertThat(types, hasItem("purchase"));
        org.hamcrest.MatcherAssert.assertThat(types, hasItem("stock_movement"));
        org.hamcrest.MatcherAssert.assertThat(types, hasItem("supplier_payment"));
    }

    /** Estos tres valores también los fija la prueba de Android (`PurchaseIdsTest`): teléfono y servidor deben derivar los mismos ids. */
    @Test
    void derivedIdsMatchThePhoneContract() {
        UUID p = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID l = UUID.fromString("22222222-2222-2222-2222-222222222222");
        assertEquals("3e115f58-236c-3ab1-8eec-c14e0ee7a09c", PurchaseService.movementId(p, l).toString());
        assertEquals("89a568a0-0968-31d0-baea-11585fbb0236", PurchaseService.reversalId(p, l).toString());
        assertEquals("f2538269-ca64-3508-ad34-b1c225eb5b42", PurchaseService.firstPaymentId(p).toString());
    }

    // ---------- propiedad ----------

    @Test
    void stockAlwaysEqualsTheSumOfMovementsUnderRandomSalesEditsCancellationsAndPurchases() throws Exception {
        String owner = login("invj");
        UUID b = createBusiness(owner, "Inv J");
        List<UUID> products = new ArrayList<>();
        Map<UUID, Long> model = new HashMap<>();
        for (int i = 0; i < 3; i++) {
            UUID p = product(owner, b, "P" + i, true, null);
            count(owner, b, p, "INITIAL", 20000);
            products.add(p);
            model.put(p, 20000L);
        }
        Random rnd = new Random(42);
        // venta -> (producto, cantidad) mientras esté cobrada
        Map<UUID, Map.Entry<UUID, Long>> live = new HashMap<>();
        List<UUID> sales = new ArrayList<>();
        for (int step = 0; step < 80; step++) {
            UUID p = products.get(rnd.nextInt(products.size()));
            switch (rnd.nextInt(6)) {
                case 0, 1 -> {
                    UUID s = UUID.randomUUID();
                    long q = 1000L * (1 + rnd.nextInt(5));
                    call(put(base(b) + "/sales/" + s), bearer(owner), saleWith(p, q, "COMPLETED")).andExpect(status().isCreated());
                    live.put(s, Map.entry(p, q));
                    sales.add(s);
                    model.merge(p, -q, Long::sum);
                }
                case 2 -> {
                    if (sales.isEmpty()) break;
                    UUID s = sales.get(rnd.nextInt(sales.size()));
                    if (!live.containsKey(s)) break;
                    var old = live.get(s);
                    long q = 1000L * (1 + rnd.nextInt(5));
                    call(put(base(b) + "/sales/" + s), bearer(owner), saleWith(p, q, "COMPLETED")).andExpect(status().is2xxSuccessful());
                    model.merge(old.getKey(), old.getValue(), Long::sum);
                    model.merge(p, -q, Long::sum);
                    live.put(s, Map.entry(p, q));
                }
                case 3 -> {
                    if (sales.isEmpty()) break;
                    UUID s = sales.get(rnd.nextInt(sales.size()));
                    call(post(base(b) + "/sales/" + s + "/cancel"), bearer(owner), "{\"reason\":\"error de cobro\"}").andExpect(status().isOk());
                    var old = live.remove(s);
                    if (old != null) model.merge(old.getKey(), old.getValue(), Long::sum);
                }
                case 4 -> {
                    long q = 1000L * (1 + rnd.nextInt(8));
                    call(put(base(b) + "/purchases/" + UUID.randomUUID()), bearer(owner), purchase(line(p, q, 100), "\"supplierName\":\"S\"")).andExpect(status().isCreated());
                    model.merge(p, q, Long::sum);
                }
                default -> {
                    long counted = 1000L * rnd.nextInt(30);
                    count(owner, b, p, "ADJUSTMENT", counted);
                    model.put(p, counted);
                }
            }
            for (UUID each : products) {
                assertEquals(movementsSum(each), stock(each), "cache vs suma en el paso " + step);
                assertEquals(model.get(each), stock(each), "modelo en el paso " + step);
            }
        }
    }
}
