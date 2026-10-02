package com.cuadra.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Un negocio NO puede leer ni escribir datos de otro por ninguna vía (ADR 0014). El barrido de `AuthzSweepTest` prueba cada ruta contra la RUTA de otro negocio;
 * esta prueba va más lejos, con datos reales: quien es de A (persona con Google o teléfono vinculado) usa SU ruta pero con los ids de las filas de B
 * (confusión de ids), manda operaciones de sincronización sobre ellas, pide reportes y exportaciones, y al final lo de B sigue idéntico y nada de B apareció en A.
 */
class CrossTenantTest extends ApiTestBase {
    private static final String SECRET = "SECRETO-DE-B";
    private static final List<String> TABLES = List.of("product", "sale", "sale_item", "sale_payment", "customer", "credit", "credit_payment", "expense", "cash_movement",
            "member", "device", "stock_movement", "supplier", "purchase", "notification", "category", "business_settings", "message_template");

    private record Tenant(String owner, UUID business, UUID member, String device, UUID product, UUID sale, UUID customer, UUID credit, UUID expense) {}

    private Tenant tenant(String who, String name, boolean secret) throws Exception {
        String owner = login(who);
        UUID b = createBusiness(owner, name);
        UUID member = memberIdOf(owner, b);
        String device = linkDevice(owner, b);
        String base = "/api/b/" + b;
        UUID product = UUID.randomUUID();
        UUID sale = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID credit = UUID.randomUUID();
        UUID expense = UUID.randomUUID();
        call(put(base + "/products/" + product), bearer(owner), "{\"name\":\"" + (secret ? SECRET : "De A") + "\",\"priceMinor\":2500,\"isQuick\":true}").andExpect(status().isCreated());
        call(put(base + "/sales/" + sale), bearer(owner), SaleTest.sale("COMPLETED", SaleTest.item(secret ? SECRET : "Venta A", 12345, 1000), SaleTest.pay("CASH", 12345, ""), "")).andExpect(status().isCreated());
        call(put(base + "/customers/" + customer), bearer(owner), "{\"name\":\"" + (secret ? SECRET : "Cliente A") + "\"}").andExpect(status().isCreated());
        call(put(base + "/credits/" + credit), bearer(owner), "{\"debtorLabel\":\"Deudor\",\"amountMinor\":5000,\"customerId\":\"" + customer + "\"}").andExpect(status().isCreated());
        call(put(base + "/expenses/" + expense), bearer(owner), "{\"amountMinor\":777,\"source\":\"CASH_DRAWER\",\"description\":\"" + (secret ? SECRET : "Gasto A") + "\"}").andExpect(status().isCreated());
        return new Tenant(owner, b, member, device, product, sale, customer, credit, expense);
    }

    /** Huella de TODO lo de un negocio (todas las columnas de todas sus filas): si algo cambia, cambia la huella. */
    private Map<String, String> fingerprint(UUID business) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String t : TABLES) {
            out.put(t, baseJdbc.sql("SELECT coalesce(md5(string_agg(x::text, '|' ORDER BY x::text)), '') FROM " + t + " x WHERE business_id = :b").param("b", business).query(String.class).single());
        }
        out.put("business", baseJdbc.sql("SELECT md5(x::text) FROM business x WHERE id = :b").param("b", business).query(String.class).single());
        return out;
    }

    private long rowsWithId(String table, UUID id) {
        return baseJdbc.sql("SELECT count(*) FROM " + table + " WHERE id = :id").param("id", id).query(Long.class).single();
    }

    private record Caller(String label, String auth, UUID member) {
        MockHttpServletRequestBuilder sign(MockHttpServletRequestBuilder r) {
            r.header("Authorization", auth);
            if (member != null) r.header("X-Member-Id", member.toString());
            return r;
        }
    }

    private int send(Caller c, HttpMethod method, String path, String body) throws Exception {
        MockHttpServletRequestBuilder r = MockMvcRequestBuilders.request(method, path);
        c.sign(r);
        if (body != null) r.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(r).andReturn().getResponse().getStatus();
    }

    private static String op(String kind, UUID entity, String payload) {
        return "{\"opId\":\"" + UUID.randomUUID() + "\",\"kind\":\"" + kind + "\",\"entityId\":\"" + entity + "\",\"payload\":" + payload + "}";
    }

    @Test
    void aBusinessUsingItsOwnRoutesWithAnotherBusinessesIdsReadsAndChangesNothing() throws Exception {
        Tenant a = tenant("ct-a", "Cruce A", false);
        Tenant b = tenant("ct-b", "Cruce B", true);
        Map<String, String> before = fingerprint(b.business());
        long aRowsBefore = baseJdbc.sql("SELECT count(*) FROM product WHERE business_id = :b").param("b", a.business()).query(Long.class).single();

        List<Caller> callers = List.of(new Caller("persona con Google de A", bearer(a.owner()), null), new Caller("teléfono de A", "Device " + a.device(), a.member()));
        String base = "/api/b/" + a.business();
        List<String> leaks = new ArrayList<>();
        for (Caller c : callers) {
            // Lecturas con ids de B en la ruta de A: nunca 2xx.
            for (String path : List.of("/products/" + b.product(), "/products/" + b.product() + "/history", "/sales/" + b.sale(), "/customers/" + b.customer(),
                    "/customers/" + b.customer() + "/statement", "/credits/" + b.credit(), "/products/barcode/" + UUID.randomUUID())) {
                int st = send(c, HttpMethod.GET, base + path, null);
                if (st < 400 || st >= 500) leaks.add(c.label() + " GET " + path + " → " + st);
            }
            // Escrituras con ids de B en la ruta de A: nunca 5xx ni 2xx sobre la fila ajena.
            String robbed = "{\"name\":\"robado\",\"priceMinor\":1}";
            Map<String, String> writes = new LinkedHashMap<>();
            writes.put("PUT /products/" + b.product(), robbed);
            writes.put("PUT /sales/" + b.sale(), SaleTest.sale("COMPLETED", SaleTest.item("robado", 1, 1000), SaleTest.pay("CASH", 1, ""), ""));
            writes.put("PUT /customers/" + b.customer(), "{\"name\":\"robado\"}");
            writes.put("PUT /credits/" + b.credit(), "{\"debtorLabel\":\"robado\",\"amountMinor\":1}");
            writes.put("PUT /expenses/" + b.expense(), "{\"amountMinor\":1,\"source\":\"CASH_DRAWER\"}");
            writes.put("POST /sales/" + b.sale() + "/cancel", "{\"reason\":\"robado\"}");
            writes.put("POST /credits/" + b.credit() + "/write-off", "{\"reason\":\"robado\"}");
            writes.put("POST /credits/" + b.credit() + "/link-customer", "{\"customerId\":\"" + b.customer() + "\"}");
            writes.put("POST /expenses/" + b.expense() + "/void", "{\"reason\":\"robado\"}");
            writes.put("DELETE /products/" + b.product(), null);
            writes.put("DELETE /devices/" + UUID.randomUUID(), null);
            for (var w : writes.entrySet()) {
                String[] parts = w.getKey().split(" ", 2);
                int st = send(c, HttpMethod.valueOf(parts[0]), base + parts[1], w.getValue());
                if (st >= 500) leaks.add(c.label() + " " + w.getKey() + " → " + st);
            }
            // Una deuda NUEVA de A que apunta a un cliente de B (referencia cruzada) no puede tocar a ese cliente.
            int st = send(c, HttpMethod.PUT, base + "/credits/" + UUID.randomUUID(), "{\"debtorLabel\":\"x\",\"amountMinor\":999,\"customerId\":\"" + b.customer() + "\"}");
            if (st >= 500) leaks.add(c.label() + " crédito con cliente ajeno → " + st);

            // Sincronización: operaciones de A sobre ids de B.
            String push = "{\"ops\":[" + String.join(",",
                    op("PRODUCT_UPSERT", b.product(), robbed),
                    op("SALE_UPSERT", b.sale(), SaleTest.sale("CANCELLED", SaleTest.item("robado", 1, 1000), "", "")),
                    op("SALE_CANCEL", b.sale(), "{\"reason\":\"robado\"}"),
                    op("CUSTOMER_UPSERT", b.customer(), "{\"name\":\"robado\"}"),
                    op("CREDIT_UPSERT", b.credit(), "{\"debtorLabel\":\"robado\",\"amountMinor\":1}"),
                    op("EXPENSE_UPSERT", b.expense(), "{\"amountMinor\":1,\"source\":\"CASH_DRAWER\"}")) + "]}";
            int pst = send(c, HttpMethod.POST, base + "/sync/push", push);
            if (pst >= 500) leaks.add(c.label() + " sync/push → " + pst);
        }
        assertEquals(List.of(), leaks);

        // Lo de B sigue idéntico, byte a byte.
        assertEquals(before, fingerprint(b.business()), "las filas de B cambiaron por acciones de A");
        // Y ninguna fila de B apareció dentro de A (mismo id en otro negocio): cada id sigue siendo de UN solo negocio.
        for (String t : List.of("product", "sale", "customer", "credit", "expense")) {
            UUID id = switch (t) { case "product" -> b.product(); case "sale" -> b.sale(); case "customer" -> b.customer(); case "credit" -> b.credit(); default -> b.expense(); };
            assertEquals(1, rowsWithId(t, id), t + " de B duplicado o movido");
            assertEquals(b.business(), baseJdbc.sql("SELECT business_id FROM " + t + " WHERE id = :id").param("id", id).query(UUID.class).single());
        }
        assertFalse(baseJdbc.sql("SELECT count(*) FROM product WHERE business_id = :b AND name = 'robado'").param("b", b.business()).query(Long.class).single() > 0);
        assertTrue(aRowsBefore >= 1);
    }

    @Test
    void readsOfABusinessNeverContainAnotherBusinessesData() throws Exception {
        Tenant a = tenant("ct-c", "Lectura A", false);
        Tenant b = tenant("ct-d", "Lectura B", true);
        List<Caller> callers = List.of(new Caller("persona de A", bearer(a.owner()), null), new Caller("teléfono de A", "Device " + a.device(), a.member()));
        String base = "/api/b/" + a.business();
        List<String> paths = List.of("/products", "/sales", "/sales/summary", "/customers", "/credits", "/credits/summary", "/expenses", "/expenses/summary", "/cash-movements", "/members",
                "/devices", "/notifications", "/categories", "/suppliers", "/purchases", "/activity", "/plan", "", "/message-templates", "/sync/pull?since=0&limit=500",
                "/reports/overview", "/reports/sales", "/reports/products", "/reports/receivables", "/reports/expenses", "/reports/inventory", "/reports/sales.csv", "/reports/sale-items.csv",
                "/reports/products.csv", "/reports/receivables.csv", "/reports/expenses.csv", "/reports/inventory.csv", "/reports/closings.csv");
        List<String> leaks = new ArrayList<>();
        for (Caller c : callers) {
            for (String p : paths) {
                MockHttpServletRequestBuilder r = MockMvcRequestBuilders.get(base + p);
                c.sign(r);
                var res = mvc.perform(r).andReturn().getResponse();
                String body = res.getContentAsString();
                if (res.getStatus() >= 500) leaks.add(c.label() + " GET " + p + " → " + res.getStatus());
                for (String foreign : List.of(SECRET, b.product().toString(), b.sale().toString(), b.customer().toString(), b.credit().toString(), b.expense().toString(), b.business().toString(), b.member().toString())) {
                    if (body.contains(foreign)) leaks.add(c.label() + " GET " + p + " contiene " + foreign);
                }
            }
        }
        assertEquals(List.of(), leaks);
    }

    @Test
    void theOtherBusinessesPhoneAndPeopleCannotUseTheIdsThroughTheirOwnRouteEither() throws Exception {
        Tenant a = tenant("ct-e", "Teléfono A", false);
        Tenant b = tenant("ct-f", "Teléfono B", true);
        // El teléfono de A con la cabecera de una persona de B, en la ruta de A o de B: nunca entra.
        Caller phoneWithForeignMember = new Caller("teléfono de A con persona de B", "Device " + a.device(), b.member());
        assertTrue(send(phoneWithForeignMember, HttpMethod.GET, "/api/b/" + a.business() + "/sales", null) >= 400);
        assertTrue(send(phoneWithForeignMember, HttpMethod.GET, "/api/b/" + b.business() + "/sales/" + b.sale(), null) >= 400);
        Caller phone = new Caller("teléfono de A", "Device " + a.device(), a.member());
        assertEquals(404, send(phone, HttpMethod.GET, "/api/b/" + b.business() + "/sales/" + b.sale(), null));
        assertEquals(404, send(phone, HttpMethod.GET, "/api/b/" + b.business() + "/sync/pull?since=0", null));
        // Un teléfono revocado o inventado no entra a nada.
        assertEquals(401, send(new Caller("inventado", "Device not-a-real-token", a.member()), HttpMethod.GET, "/api/b/" + a.business() + "/sales", null));
        // Soporte: pedir un ticket «de» otro negocio con la sesión de A.
        call(post("/api/support/tickets"), bearer(a.owner()), "{\"category\":\"QUESTION\",\"message\":\"mensaje de prueba largo\",\"businessId\":\"" + b.business() + "\"}").andExpect(status().isNotFound());
        assertEquals(0, baseJdbc.sql("SELECT count(*) FROM support_ticket WHERE business_id = :b").param("b", b.business()).query(Long.class).single());
        // Quitar el teléfono de B desde A.
        UUID deviceOfB = baseJdbc.sql("SELECT id FROM device WHERE business_id = :b LIMIT 1").param("b", b.business()).query(UUID.class).single();
        call(delete("/api/b/" + a.business() + "/devices/" + deviceOfB), bearer(a.owner()), null).andExpect(status().isNotFound());
        assertEquals(1, baseJdbc.sql("SELECT count(*) FROM device WHERE id = :d AND revoked_at IS NULL").param("d", deviceOfB).query(Long.class).single());
        // /api/me solo lista sus negocios.
        String me = call(get("/api/me"), bearer(a.owner()), null).andReturn().getResponse().getContentAsString();
        assertFalse(me.contains(b.business().toString()));
        assertTrue(me.contains(a.business().toString()));
    }
}
