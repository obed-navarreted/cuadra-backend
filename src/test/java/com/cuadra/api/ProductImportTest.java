package com.cuadra.api;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class ProductImportTest extends ApiTestBase {
    @Autowired JdbcClient jdbc;

    private String url(UUID b, boolean dry) { return "/api/b/" + b + "/products/import?dryRun=" + dry; }

    private static String row(int line, String name, String barcode, String price, String cost, String unit, String category, String track, String stock, String min) {
        return "{\"line\":" + line + ",\"name\":" + q(name) + ",\"barcode\":" + q(barcode) + ",\"price\":" + q(price) + ",\"cost\":" + q(cost) + ",\"unit\":" + q(unit) + ",\"category\":" + q(category)
                + ",\"trackStock\":" + q(track) + ",\"stock\":" + q(stock) + ",\"minStock\":" + q(min) + "}";
    }

    private static String q(String s) { return s == null ? "null" : "\"" + s + "\""; }

    private static String rows(String... rows) { return "{\"rows\":[" + String.join(",", rows) + "]}"; }

    private int products(UUID b) { return jdbc.sql("SELECT count(*) FROM product WHERE business_id = :b").param("b", b).query(Integer.class).single(); }

    @Test
    void thePreviewShowsWhatWouldHappenAndSavesNothing() throws Exception {
        String owner = login("impa");
        UUID b = createBusiness(owner, "Imp A");
        String body = rows(row(2, "Queso seco", "7501", "90", "60", "LB", "Lácteos", "si", "12", "3"), row(3, "Cuajada", null, "25,50", null, null, null, null, null, null));
        call(post(url(b, true)), bearer(owner), body).andExpect(status().isOk()).andExpect(jsonPath("$.dryRun", is(true))).andExpect(jsonPath("$.summary.total", is(2))).andExpect(jsonPath("$.summary.created", is(2)))
                .andExpect(jsonPath("$.rows[0].status", is("CREATE"))).andExpect(jsonPath("$.rows[1].status", is("CREATE")));
        assertEquals(0, products(b));
        assertEquals(0, jdbc.sql("SELECT count(*) FROM category WHERE business_id = :b").param("b", b).query(Integer.class).single());
        assertEquals(0, jdbc.sql("SELECT count(*) FROM stock_movement WHERE business_id = :b").param("b", b).query(Integer.class).single());
    }

    @Test
    void applyingCreatesProductsCategoriesCostsAndTheInitialCount() throws Exception {
        String owner = login("impb");
        UUID b = createBusiness(owner, "Imp B");
        String body = rows(row(2, "Queso seco", "7501", "90", "60", "LB", "Lácteos", "si", "12", "3"), row(3, "Cuajada", null, "25,50", null, null, "lácteos", null, null, null));
        call(post(url(b, false)), bearer(owner), body).andExpect(status().isOk()).andExpect(jsonPath("$.summary.created", is(2))).andExpect(jsonPath("$.summary.failed", is(0)));
        assertEquals(2, products(b));
        // La misma categoría (sin importar mayúsculas) se crea una sola vez.
        assertEquals(1, jdbc.sql("SELECT count(*) FROM category WHERE business_id = :b").param("b", b).query(Integer.class).single());
        var p = jdbc.sql("SELECT price_minor, cost_minor, unit, track_stock, stock_milli, min_stock_milli FROM product WHERE barcode = '7501' AND business_id = :b").param("b", b).query((rs, n) -> new Object[] {rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getBoolean(4), rs.getLong(5), rs.getLong(6)}).single();
        assertEquals(9000L, p[0]);
        assertEquals(6000L, p[1]);
        assertEquals("LB", p[2]);
        assertEquals(true, p[3]);
        assertEquals(12000L, p[4]);
        assertEquals(3000L, p[5]);
        assertEquals("INITIAL", jdbc.sql("SELECT kind FROM stock_movement WHERE business_id = :b").param("b", b).query(String.class).single());
        assertEquals(2550L, jdbc.sql("SELECT price_minor FROM product WHERE name = 'Cuajada' AND business_id = :b").param("b", b).query(Long.class).single());
    }

    @Test
    void reimportingUpdatesInsteadOfDuplicatingAndAStockColumnIsACountNotAnAddition() throws Exception {
        String owner = login("impc");
        UUID b = createBusiness(owner, "Imp C");
        String first = rows(row(2, "Queso seco", "7501", "90", "60", "LB", null, "si", "12", null));
        call(post(url(b, false)), bearer(owner), first).andExpect(status().isOk());
        String second = rows(row(2, "Queso seco", "7501", "95", null, null, null, null, "10", null));
        call(post(url(b, false)), bearer(owner), second).andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].status", is("UPDATE"))).andExpect(jsonPath("$.summary.updated", is(1)));
        assertEquals(1, products(b));
        // Precio nuevo; el costo vacío conserva el anterior; la existencia queda en lo contado (10), no 12 + 10.
        var p = jdbc.sql("SELECT price_minor, cost_minor, stock_milli FROM product WHERE barcode = '7501' AND business_id = :b").param("b", b).query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)}).single();
        assertEquals(9500L, p[0]);
        assertEquals(6000L, p[1]);
        assertEquals(10000L, p[2]);
        assertEquals(jdbc.sql("SELECT coalesce(sum(quantity_milli), 0) FROM stock_movement WHERE business_id = :b").param("b", b).query(Long.class).single(), p[2]);
        // También se reconoce por nombre cuando el archivo no trae código.
        call(post(url(b, false)), bearer(owner), rows(row(2, "queso SECO", null, "99", null, null, null, null, null, null))).andExpect(jsonPath("$.rows[0].status", is("UPDATE")));
        assertEquals(1, products(b));
    }

    @Test
    void aBadRowIsReportedAndSkippedWhileTheOthersAreApplied() throws Exception {
        String owner = login("impd");
        UUID b = createBusiness(owner, "Imp D");
        UUID taken = UUID.randomUUID();
        call(put("/api/b/" + b + "/products/" + taken), bearer(owner), "{\"name\":\"Ya existe\",\"priceMinor\":100,\"barcode\":\"9999\"}").andExpect(status().isCreated());
        String body = rows(
                row(2, "Bueno", "1111", "10", null, null, null, null, null, null),
                row(3, "Precio malo", null, "abc", null, null, null, null, null, null),
                row(4, "Muchos decimales", null, "10.555", null, null, null, null, null, null),
                row(5, "Unidad mala", null, "10", null, "GALON", null, null, null, null),
                row(6, "", null, "10", null, null, null, null, null, null),
                row(7, "Código repetido", "1111", "10", null, null, null, null, null, null),
                row(8, "Sin precio", null, null, null, null, null, null, null, null),
                row(9, "Stock malo", null, "10", null, null, null, "si", "-5", null),
                row(10, "Otro producto con código ocupado", "9999", "10", null, null, null, null, null, null));
        // La fila 10 trae el código de "Ya existe" pero otro nombre: se reconoce como ese producto y lo actualiza (el código identifica al producto).
        call(post(url(b, false)), bearer(owner), body).andExpect(status().isOk()).andExpect(jsonPath("$.summary.total", is(9))).andExpect(jsonPath("$.summary.created", is(1))).andExpect(jsonPath("$.summary.updated", is(1)))
                .andExpect(jsonPath("$.summary.failed", is(7)))
                .andExpect(jsonPath("$.rows[*].code", contains(null, "INVALID_PRICE", "INVALID_PRICE", "INVALID_UNIT", "INVALID_NAME", "DUPLICATE_IN_FILE", "INVALID_PRICE", "INVALID_STOCK", null)))
                .andExpect(jsonPath("$.rows[1].line", is(3)));
        assertEquals(2, products(b));
    }

    @Test
    void onlyOwnersAndAdminsImportAndTheSizeIsLimited() throws Exception {
        String owner = login("impe");
        UUID b = createBusiness(owner, "Imp E");
        String cashier = joinAs(owner, b, "impe2", "CASHIER");
        String admin = joinAs(owner, b, "impe3", "ADMIN");
        String one = rows(row(2, "Pan", null, "5", null, null, null, null, null, null));
        call(post(url(b, false)), bearer(cashier), one).andExpect(status().isForbidden());
        call(post(url(b, false)), bearer(admin), one).andExpect(status().isOk()).andExpect(jsonPath("$.summary.created", is(1)));
        call(post(url(b, true)), bearer(owner), "{\"rows\":[]}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("EMPTY_FILE")));
        String big = "{\"rows\":[" + IntStream.range(0, 2001).mapToObj(i -> row(i + 2, "P" + i, null, "1", null, null, null, null, null, null)).collect(Collectors.joining(",")) + "]}";
        call(post(url(b, true)), bearer(owner), big).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("TOO_MANY_ROWS")));
    }
}
