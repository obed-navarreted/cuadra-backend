package com.cuadra.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CatalogTest extends ApiTestBase {

    private static String product(String name, long price, String extra) {
        return "{\"name\":\"" + name + "\",\"priceMinor\":" + price + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    @Test
    void cashierCanCreateFromTheRegisterButOnlyManagersCanEdit() throws Exception {
        String owner = login("cata");
        UUID b = createBusiness(owner, "Catálogo A");
        UUID cashier = createPinMember(owner, b, "Kevin", "CASHIER");
        String device = linkDevice(owner, b);
        UUID id = UUID.randomUUID();

        asDevice(put("/api/b/" + b + "/products/" + id), device, cashier, product("Cuajada", 2500, "\"isQuick\":true"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name", is("Cuajada"))).andExpect(jsonPath("$.priceMinor", is(2500)))
                .andExpect(jsonPath("$.isQuick", is(true))).andExpect(jsonPath("$.pricing", is("FIXED")));

        asDevice(put("/api/b/" + b + "/products/" + id), device, cashier, product("Cuajada", 3000, ""))
                .andExpect(status().isForbidden());
        call(put("/api/b/" + b + "/products/" + id), bearer(owner), product("Cuajada", 3000, ""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.priceMinor", is(3000)));
        asDevice(delete("/api/b/" + b + "/products/" + id), device, cashier, null).andExpect(status().isForbidden());
        call(delete("/api/b/" + b + "/products/" + id), bearer(owner), null).andExpect(status().isNoContent());
        call(get("/api/b/" + b + "/products/" + id), bearer(owner), null).andExpect(jsonPath("$.active", is(false)));
    }

    @Test
    void repeatingTheSameUpsertChangesNothing() throws Exception {
        String owner = login("catb");
        UUID b = createBusiness(owner, "Catálogo B");
        UUID id = UUID.randomUUID();
        String first = call(put("/api/b/" + b + "/products/" + id), bearer(owner), product("Arroz", 2200, "\"barcode\":\"7410001\""))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String again = call(put("/api/b/" + b + "/products/" + id), bearer(owner), product("Arroz", 2200, "\"barcode\":\"7410001\""))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        // Sin cambios no sube la revisión: la sincronización no se ensucia con reintentos.
        org.junit.jupiter.api.Assertions.assertEquals((Integer) JsonPath.read(first, "$.rev"), (Integer) JsonPath.read(again, "$.rev"));
    }

    @Test
    void oneCodeIdentifiesOneActiveProductAndUpcAEqualsEan13() throws Exception {
        String owner = login("catc");
        UUID b = createBusiness(owner, "Catálogo C");
        UUID fanta = UUID.randomUUID();
        call(put("/api/b/" + b + "/products/" + fanta), bearer(owner), product("Fanta", 3000, "\"barcode\":\"012345678905\"")).andExpect(status().isCreated());

        // Mismo código, otro producto: 409 con el id del que ya lo usa, para agregarlo a la venta en vez de duplicarlo.
        call(put("/api/b/" + b + "/products/" + UUID.randomUUID()), bearer(owner), product("Otra", 100, "\"barcode\":\"012345678905\""))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("BARCODE_IN_USE"))).andExpect(jsonPath("$.existingId", is(fanta.toString())));
        // El UPC-A de 12 dígitos y su EAN-13 con cero inicial son el mismo código.
        call(put("/api/b/" + b + "/products/" + UUID.randomUUID()), bearer(owner), product("Otra", 100, "\"barcode\":\"0012345678905\""))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.existingId", is(fanta.toString())));

        for (String scanned : new String[] {"012345678905", "0012345678905"}) {
            call(get("/api/b/" + b + "/products/barcode/" + scanned), bearer(owner), null).andExpect(status().isOk()).andExpect(jsonPath("$.id", is(fanta.toString())));
        }
        call(get("/api/b/" + b + "/products/barcode/999"), bearer(owner), null).andExpect(status().isNotFound());

        // Dado de baja, el código queda libre.
        call(delete("/api/b/" + b + "/products/" + fanta), bearer(owner), null).andExpect(status().isNoContent());
        call(put("/api/b/" + b + "/products/" + UUID.randomUUID()), bearer(owner), product("Nueva", 100, "\"barcode\":\"012345678905\"")).andExpect(status().isCreated());
    }

    @Test
    void weightProductsAndQuickFilterAndSearch() throws Exception {
        String owner = login("catd");
        UUID b = createBusiness(owner, "Quesería");
        call(put("/api/b/" + b + "/products/" + UUID.randomUUID()), bearer(owner),
                product("Queso seco", 9000, "\"unit\":\"LB\",\"pricing\":\"BY_WEIGHT\",\"isQuick\":true,\"quickPosition\":1")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.pricing", is("BY_WEIGHT"))).andExpect(jsonPath("$.unit", is("LB")));
        call(put("/api/b/" + b + "/products/" + UUID.randomUUID()), bearer(owner), product("Sal", 1000, "\"shortCode\":\"S1\"")).andExpect(status().isCreated());

        call(get("/api/b/" + b + "/products?quick=true"), bearer(owner), null).andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].name", is("Queso seco")));
        call(get("/api/b/" + b + "/products?q=sal"), bearer(owner), null).andExpect(jsonPath("$.items", hasSize(1)));
        call(get("/api/b/" + b + "/products?q=S1"), bearer(owner), null).andExpect(jsonPath("$.items[0].name", is("Sal")));
        call(get("/api/b/" + b + "/products"), bearer(owner), null).andExpect(jsonPath("$.total", is(2))).andExpect(jsonPath("$.last", is(true)));
    }

    @Test
    void validationRejectsBadProducts() throws Exception {
        String owner = login("cate");
        UUID b = createBusiness(owner, "Catálogo E");
        String url = "/api/b/" + b + "/products/";
        call(put(url + UUID.randomUUID()), bearer(owner), "{\"name\":\"\",\"priceMinor\":100}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("INVALID_NAME")));
        call(put(url + UUID.randomUUID()), bearer(owner), "{\"name\":\"X\",\"priceMinor\":-1}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("INVALID_PRICE")));
        call(put(url + UUID.randomUUID()), bearer(owner), "{\"name\":\"X\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("INVALID_PRICE")));
        call(put(url + UUID.randomUUID()), bearer(owner), product("X", 1, "\"unit\":\"GALLON\"")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("INVALID_UNIT")));
    }

    @Test
    void otherBusinessesCannotSeeOrCollideWithProducts() throws Exception {
        String ownerA = login("catf");
        String ownerB = login("catg");
        UUID a = createBusiness(ownerA, "Negocio A");
        UUID b = createBusiness(ownerB, "Negocio B");
        UUID id = UUID.randomUUID();
        call(put("/api/b/" + a + "/products/" + id), bearer(ownerA), product("Privado", 500, "\"barcode\":\"555\"")).andExpect(status().isCreated());

        call(get("/api/b/" + a + "/products"), bearer(ownerB), null).andExpect(status().isNotFound());
        call(get("/api/b/" + b + "/products/" + id), bearer(ownerB), null).andExpect(status().isNotFound());
        call(get("/api/b/" + b + "/products/barcode/555"), bearer(ownerB), null).andExpect(status().isNotFound());
        // Reusar el id de otro negocio no revela nada ni sobrescribe.
        call(put("/api/b/" + b + "/products/" + id), bearer(ownerB), product("Robado", 1, "")).andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("ID_TAKEN")));
        // El mismo código sí puede repetirse entre negocios distintos.
        call(put("/api/b/" + b + "/products/" + UUID.randomUUID()), bearer(ownerB), product("Propio", 1, "\"barcode\":\"555\"")).andExpect(status().isCreated());
    }
}
