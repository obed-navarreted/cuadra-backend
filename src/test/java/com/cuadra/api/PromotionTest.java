package com.cuadra.api;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cuadra.api.push.FcmTransport;
import com.cuadra.api.push.PushDispatcher;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/**
 * Promociones por cantidad (PENDIENTES.md): CRUD con permisos y aislamiento, sincronización, ventas que guardan lo aplicado sin que el servidor recalcule,
 * reportes («Descuentos por promociones»), devoluciones proporcionales y los avisos de Firebase («sincroniza ya», juntado; tokens dados de baja).
 */
@Import(PromotionTest.FakeFcm.class)
@TestPropertySource(properties = "cuadra.push.sync-debounce-ms=150")
class PromotionTest extends ApiTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired PushDispatcher dispatcher;
    @Autowired RecordingFcm fcm;

    /** Firebase de mentira: anota cada envío; un token que empieza con «dead» responde UNREGISTERED. */
    static class RecordingFcm implements FcmTransport {
        final List<Map<String, String>> sent = new CopyOnWriteArrayList<>();
        final List<String> tokens = new CopyOnWriteArrayList<>();

        @Override public boolean enabled() { return true; }

        @Override public Outcome send(String token, Map<String, String> data) {
            if (token.startsWith("dead")) return Outcome.UNREGISTERED;
            tokens.add(token);
            sent.add(data);
            return Outcome.SENT;
        }
    }

    @TestConfiguration
    static class FakeFcm {
        @Bean @Primary RecordingFcm recordingFcm() { return new RecordingFcm(); }
    }

    @BeforeEach
    void clear() { fcm.sent.clear(); fcm.tokens.clear(); }

    private static String base(UUID b) { return "/api/b/" + b; }

    private UUID beer(String owner, UUID b, String name, long price) throws Exception {
        UUID p = UUID.randomUUID();
        call(put(base(b) + "/products/" + p), bearer(owner), "{\"name\":\"" + name + "\",\"priceMinor\":" + price + "}").andExpect(status().isCreated());
        return p;
    }

    private static String promo(String name, int qty, long price, List<UUID> products, String extra) {
        StringBuilder ids = new StringBuilder();
        for (UUID p : products) ids.append(ids.isEmpty() ? "" : ",").append('"').append(p).append('"');
        return "{\"name\":\"" + name + "\",\"quantity\":" + qty + ",\"priceMinor\":" + price + ",\"productIds\":[" + ids + "]" + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    @Test
    void ownersAndAdminsManagePromotionsCashiersOnlyReadThem() throws Exception {
        String owner = login("promo-a");
        UUID b = createBusiness(owner, "Bar Promo");
        UUID toña = beer(owner, b, "Toña", 4500);
        UUID victoria = beer(owner, b, "Victoria", 4500);
        UUID cashier = createPinMember(owner, b, "Kevin", "CASHIER");
        String device = linkDevice(owner, b);
        UUID id = UUID.randomUUID();

        call(put(base(b) + "/promotions/" + id), bearer(owner), promo("Cerveza 3 por C$ 100", 3, 10000, List.of(toña, victoria), ""))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.state", is("ACTIVE"))).andExpect(jsonPath("$.productIds", hasSize(2)))
                .andExpect(jsonPath("$.quantity", is(3))).andExpect(jsonPath("$.priceMinor", is(10000)));
        // Repetir lo mismo no cambia nada (la revisión no sube).
        String a = call(put(base(b) + "/promotions/" + id), bearer(owner), promo("Cerveza 3 por C$ 100", 3, 10000, List.of(victoria, toña), "")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String c = call(get(base(b) + "/promotions/" + id), bearer(owner), null).andReturn().getResponse().getContentAsString();
        assertEquals((Integer) JsonPath.read(a, "$.rev"), (Integer) JsonPath.read(c, "$.rev"));

        // El cajero la ve (la caja la aplica) pero no la crea, cambia, pausa ni borra.
        asDevice(get(base(b) + "/promotions"), device, cashier, null).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
        asDevice(put(base(b) + "/promotions/" + UUID.randomUUID()), device, cashier, promo("X", 2, 100, List.of(toña), "")).andExpect(status().isForbidden());
        asDevice(post(base(b) + "/promotions/" + id + "/active"), device, cashier, "{\"active\":false}").andExpect(status().isForbidden());
        asDevice(delete(base(b) + "/promotions/" + id), device, cashier, null).andExpect(status().isForbidden());
        // Ni por la cola de sincronización.
        String op = "{\"opId\":\"" + UUID.randomUUID() + "\",\"kind\":\"PROMOTION_UPSERT\",\"entityId\":\"" + UUID.randomUUID() + "\",\"payload\":" + promo("Y", 2, 100, List.of(toña), "") + "}";
        asDevice(post(base(b) + "/sync/push"), device, cashier, "{\"ops\":[" + op + "]}").andExpect(jsonPath("$.results[0].status", is("REJECTED")));

        // Un admin sí (con su propia cuenta).
        String admin = joinAs(owner, b, "promo-admin", "ADMIN");
        call(post(base(b) + "/promotions/" + id + "/active"), bearer(admin), "{\"active\":false}").andExpect(status().isOk()).andExpect(jsonPath("$.state", is("PAUSED")))
                .andExpect(jsonPath("$.active", is(false)));
        call(post(base(b) + "/promotions/" + id + "/active"), bearer(admin), "{\"active\":true}").andExpect(jsonPath("$.state", is("ACTIVE")));

        // Queda en la actividad: alta, pausa, reanudación.
        List<String> actions = jdbc.sql("SELECT action FROM audit_log WHERE business_id = :b AND entity = 'promotion' ORDER BY id").param("b", b).query(String.class).list();
        assertEquals(List.of("promotion.create", "promotion.pause", "promotion.resume"), actions);

        // Borrada: sale de la lista, la sincronización la manda marcada como borrada.
        call(delete(base(b) + "/promotions/" + id), bearer(owner), null).andExpect(status().isNoContent());
        call(get(base(b) + "/promotions"), bearer(owner), null).andExpect(jsonPath("$", hasSize(0)));
        call(get(base(b) + "/promotions/" + id), bearer(owner), null).andExpect(status().isNotFound());
        String pull = asDevice(get(base(b) + "/sync/pull?since=0&limit=500"), device, cashier, null).andReturn().getResponse().getContentAsString();
        List<Boolean> deleted = JsonPath.read(pull, "$.changes[?(@.type=='promotion')].data.deleted");
        assertEquals(List.of(true), deleted);
    }

    @Test
    void validatesTheShapeOfAPromotion() throws Exception {
        String owner = login("promo-v");
        UUID b = createBusiness(owner, "Validaciones");
        UUID p = beer(owner, b, "Toña", 4500);
        UUID weighed = UUID.randomUUID();
        call(put(base(b) + "/products/" + weighed), bearer(owner), "{\"name\":\"Queso\",\"priceMinor\":9000,\"pricing\":\"BY_WEIGHT\",\"unit\":\"LB\"}").andExpect(status().isCreated());
        UUID open = UUID.randomUUID();
        call(put(base(b) + "/products/" + open), bearer(owner), "{\"name\":\"Servicio\",\"pricing\":\"OPEN\"}").andExpect(status().isCreated());
        String url = base(b) + "/promotions/" + UUID.randomUUID();
        assertCode(call(put(url), bearer(owner), promo("Uno", 1, 100, List.of(p), "")).andExpect(status().isBadRequest()), "INVALID_QUANTITY");
        assertCode(call(put(url), bearer(owner), promo("Cero", 3, 0, List.of(p), "")).andExpect(status().isBadRequest()), "INVALID_PRICE");
        assertCode(call(put(url), bearer(owner), promo("", 3, 100, List.of(p), "")).andExpect(status().isBadRequest()), "INVALID_NAME");
        assertCode(call(put(url), bearer(owner), promo("Nada", 3, 100, List.of(), "")).andExpect(status().isBadRequest()), "PRODUCTS_REQUIRED");
        assertCode(call(put(url), bearer(owner), promo("Peso", 3, 100, List.of(p, weighed), "")).andExpect(status().isBadRequest()), "PRODUCT_NOT_ELIGIBLE");
        assertCode(call(put(url), bearer(owner), promo("Abierto", 3, 100, List.of(open), "")).andExpect(status().isBadRequest()), "PRODUCT_NOT_ELIGIBLE");
        assertCode(call(put(url), bearer(owner), promo("Fechas", 3, 100, List.of(p), "\"startsOn\":\"2026-10-10\",\"endsOn\":\"2026-10-01\"")).andExpect(status().isBadRequest()), "INVALID_DATES");
        assertCode(call(put(url), bearer(owner), promo("Ajeno", 3, 100, List.of(UUID.randomUUID()), "")).andExpect(status().isBadRequest()), "INVALID_PRODUCT");
        // Con fechas en el futuro: programada.
        call(put(url), bearer(owner), promo("Navidad", 3, 100, List.of(p), "\"startsOn\":\"2099-12-01\",\"endsOn\":\"2099-12-31\"")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.state", is("SCHEDULED"))).andExpect(jsonPath("$.startsOn", is("2099-12-01")));
    }

    @Test
    void anotherBusinessCannotSeeOrTouchAPromotion() throws Exception {
        String ownerA = login("promo-ra");
        UUID a = createBusiness(ownerA, "Negocio A");
        UUID pa = beer(ownerA, a, "Toña", 4500);
        UUID id = UUID.randomUUID();
        call(put(base(a) + "/promotions/" + id), bearer(ownerA), promo("Solo A", 3, 10000, List.of(pa), "")).andExpect(status().isCreated());
        String ownerB = login("promo-rb");
        UUID bb = createBusiness(ownerB, "Negocio B");
        UUID pb = beer(ownerB, bb, "Toña", 4500);
        call(get(base(bb) + "/promotions"), bearer(ownerB), null).andExpect(jsonPath("$", hasSize(0)));
        call(get(base(bb) + "/promotions/" + id), bearer(ownerB), null).andExpect(status().isNotFound());
        assertCode(call(put(base(bb) + "/promotions/" + id), bearer(ownerB), promo("Robada", 2, 1, List.of(pb), "")).andExpect(status().isConflict()), "ID_TAKEN");
        call(delete(base(bb) + "/promotions/" + id), bearer(ownerB), null).andExpect(status().isNotFound());
        // Ni con un producto del otro negocio.
        assertCode(call(put(base(bb) + "/promotions/" + UUID.randomUUID()), bearer(ownerB), promo("Mixta", 2, 1, List.of(pa), "")).andExpect(status().isBadRequest()), "INVALID_PRODUCT");
        // Ni a la fuerza por la cuenta de A desde B.
        call(get(base(a) + "/promotions"), bearer(ownerB), null).andExpect(status().isNotFound());
        // La base lo garantiza aunque se olvide un filtro: con el contexto de B, la fila de A no existe.
        Integer seen = com.cuadra.api.tenancy.TenantContext.call(bb, () -> baseJdbc.sql("SELECT count(*) FROM promotion WHERE id = :id").param("id", id).query(Integer.class).single());
        assertEquals(0, seen);
    }

    private static String item(UUID id, UUID product, String name, long price, long qtyMilli, long discount) {
        return "{\"id\":\"" + id + "\",\"productId\":\"" + product + "\",\"name\":\"" + name + "\",\"unitPriceMinor\":" + price + ",\"quantityMilli\":" + qtyMilli
                + ",\"discountMinor\":" + discount + "}";
    }

    private static String applied(UUID promo, String name, int qty, long price, int units, long discount) {
        return "{\"promotionId\":\"" + promo + "\",\"name\":\"" + name + "\",\"quantity\":" + qty + ",\"priceMinor\":" + price + ",\"units\":" + units + ",\"discountMinor\":" + discount + "}";
    }

    @Test
    void aSaleKeepsWhatWasChargedAndReportsAndReturnsUseTheDiscountedPrice() throws Exception {
        String owner = login("promo-s");
        UUID b = createBusiness(owner, "Bar Ventas");
        UUID toña = beer(owner, b, "Toña", 4500);
        UUID promo = UUID.randomUUID();
        call(put(base(b) + "/promotions/" + promo), bearer(owner), promo("Cerveza 3 por C$ 100", 3, 10000, List.of(toña), "")).andExpect(status().isCreated());
        // La promoción ya está pausada en el servidor: una venta hecha sin conexión con la promoción vieja se acepta tal como se cobró.
        call(post(base(b) + "/promotions/" + promo + "/active"), bearer(owner), "{\"active\":false}").andExpect(status().isOk());

        // 7 × C$ 45 con «3 por C$ 100» = 2 paquetes (C$ 200) + 1 suelta (C$ 45) = C$ 245; el descuento (C$ 70) va en la línea.
        UUID sale = UUID.randomUUID();
        UUID line = UUID.randomUUID();
        String body = "{\"status\":\"COMPLETED\",\"items\":[" + item(line, toña, "Toña", 4500, 7000, 7000) + "],\"payments\":[{\"id\":\"" + UUID.randomUUID()
                + "\",\"method\":\"CASH\",\"amountMinor\":24500,\"tenderedMinor\":30000}],\"promotions\":[" + applied(promo, "Cerveza 3 por C$ 100", 3, 10000, 6, 7000) + "]}";
        call(put(base(b) + "/sales/" + sale), bearer(owner), body).andExpect(status().isCreated()).andExpect(jsonPath("$.totalMinor", is(24500)))
                .andExpect(jsonPath("$.promotions", hasSize(1))).andExpect(jsonPath("$.promotions[0].units", is(6))).andExpect(jsonPath("$.promotions[0].discountMinor", is(7000)))
                .andExpect(jsonPath("$.promotionDiscountMinor", is(7000))).andExpect(jsonPath("$.items[0].lineTotalMinor", is(24500)));
        // Repetirla no cambia nada.
        call(put(base(b) + "/sales/" + sale), bearer(owner), body).andExpect(status().isOk());

        // Las sumas tienen que cuadrar: una promoción no puede descontar más de lo que descuentan las líneas, ni tener paquetes a medias.
        String tooMuch = "{\"status\":\"COMPLETED\",\"items\":[" + item(UUID.randomUUID(), toña, "Toña", 4500, 3000, 0) + "],\"payments\":[{\"id\":\"" + UUID.randomUUID()
                + "\",\"method\":\"CASH\",\"amountMinor\":13500}],\"promotions\":[" + applied(promo, "Cerveza", 3, 10000, 3, 3500) + "]}";
        assertCode(call(put(base(b) + "/sales/" + UUID.randomUUID()), bearer(owner), tooMuch).andExpect(status().isBadRequest()), "PROMOTION_MISMATCH");
        String halfPack = "{\"status\":\"COMPLETED\",\"items\":[" + item(UUID.randomUUID(), toña, "Toña", 4500, 4000, 3500) + "],\"payments\":[{\"id\":\"" + UUID.randomUUID()
                + "\",\"method\":\"CASH\",\"amountMinor\":14500}],\"promotions\":[" + applied(promo, "Cerveza", 3, 10000, 4, 3500) + "]}";
        assertCode(call(put(base(b) + "/sales/" + UUID.randomUUID()), bearer(owner), halfPack).andExpect(status().isBadRequest()), "INVALID_PROMOTION");

        // «Descuentos por promociones» en el resumen y en el cierre del día.
        call(get(base(b) + "/reports/sales"), bearer(owner), null).andExpect(jsonPath("$.sales.promotionDiscountMinor", is(7000))).andExpect(jsonPath("$.sales.totalMinor", is(24500)));
        call(get(base(b) + "/reports/daily-close"), bearer(owner), null).andExpect(jsonPath("$.days[0].promotionDiscountMinor", is(7000)))
                .andExpect(jsonPath("$.days[0].salesMinor", is(24500)));

        // Devolver 1 de las 7: se reembolsa la parte proporcional del precio CON promoción (245 / 7 = 35), no los 45 de lista.
        call(put(base(b) + "/sales/" + sale + "/returns/" + UUID.randomUUID()), bearer(owner),
                "{\"saleId\":\"" + sale + "\",\"items\":[{\"saleItemId\":\"" + line + "\",\"quantityMilli\":1000}],\"reason\":\"Venía caliente\",\"refundMethod\":\"CASH\"}")
                .andExpect(status().is2xxSuccessful()).andExpect(jsonPath("$.totalMinor", is(3500)));
        // El resto: exactamente lo que queda (245 − 35 = 210).
        call(put(base(b) + "/sales/" + sale + "/returns/" + UUID.randomUUID()), bearer(owner),
                "{\"saleId\":\"" + sale + "\",\"items\":[{\"saleItemId\":\"" + line + "\",\"quantityMilli\":6000}],\"reason\":\"Cliente se arrepintió\",\"refundMethod\":\"CASH\"}")
                .andExpect(status().is2xxSuccessful()).andExpect(jsonPath("$.totalMinor", is(21000)));
        call(get(base(b) + "/sales/" + sale), bearer(owner), null).andExpect(jsonPath("$.returnedMinor", is(24500)));
    }

    @Test
    void changesAskEveryPhoneToSyncOnceAndDeadTokensAreDropped() throws Exception {
        String owner = login("promo-f");
        UUID b = createBusiness(owner, "Bar Firebase");
        UUID toña = beer(owner, b, "Toña", 4500);
        call(put(base(b) + "/push-tokens"), bearer(owner), "{\"fcmToken\":\"live-1\"}").andExpect(status().isNoContent());
        String device = linkDevice(owner, b);
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        asDevice(put(base(b) + "/push-tokens"), device, kevin, "{\"fcmToken\":\"dead-2\"}").andExpect(status().isNoContent());
        dispatcher.drainForTests();
        fcm.sent.clear();

        // Tres cambios seguidos: un solo «sincroniza ya» por teléfono.
        UUID id = UUID.randomUUID();
        call(put(base(b) + "/promotions/" + id), bearer(owner), promo("Cerveza", 3, 10000, List.of(toña), "")).andExpect(status().isCreated());
        call(post(base(b) + "/promotions/" + id + "/active"), bearer(owner), "{\"active\":false}").andExpect(status().isOk());
        call(put(base(b) + "/products/" + toña), bearer(owner), "{\"name\":\"Toña\",\"priceMinor\":5000}").andExpect(status().isOk());
        dispatcher.drainForTests();
        List<Map<String, String>> syncs = fcm.sent.stream().filter(m -> "SYNC".equals(m.get("type"))).toList();
        assertEquals(1, syncs.size(), "un solo aviso al teléfono vivo: " + fcm.sent);
        assertEquals(b.toString(), syncs.get(0).get("businessId"));
        // El token que Firebase dio por desinstalado se borró.
        assertEquals(0, jdbc.sql("SELECT count(*) FROM push_token WHERE fcm_token = 'dead-2'").query(Integer.class).single());
        assertEquals(1, jdbc.sql("SELECT count(*) FROM push_token WHERE fcm_token = 'live-1'").query(Integer.class).single());

        // Repetir lo mismo no cambia nada y no avisa.
        fcm.sent.clear();
        call(put(base(b) + "/products/" + toña), bearer(owner), "{\"name\":\"Toña\",\"priceMinor\":5000}").andExpect(status().isOk());
        dispatcher.drainForTests();
        assertTrue(fcm.sent.isEmpty());

        // Un aviso de la bandeja (cajero que cambia un precio → aviso al dueño) llega como NOTIFY al teléfono del dueño.
        asDevice(put(base(b) + "/push-tokens"), device, kevin, "{\"fcmToken\":\"kevin-phone\"}").andExpect(status().isNoContent());
        dispatcher.drainForTests();
        fcm.sent.clear();
        asDevice(put(base(b) + "/products/" + toña), device, kevin, "{\"name\":\"Toña\",\"priceMinor\":5500}").andExpect(status().isOk());
        dispatcher.drainForTests();
        org.hamcrest.MatcherAssert.assertThat(fcm.sent.stream().map(m -> m.get("type")).toList(), hasItem("NOTIFY"));
        Map<String, String> notify = fcm.sent.stream().filter(m -> "NOTIFY".equals(m.get("type"))).findFirst().orElseThrow();
        assertEquals("PRICE_CHANGED", notify.get("notificationType"));
        int idx = fcm.sent.indexOf(notify);
        assertEquals("live-1", fcm.tokens.get(idx));
    }
}
