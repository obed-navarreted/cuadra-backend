package com.cuadra.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Trazabilidad: cada alta, cambio y baja de un producto queda con quién, cuándo y qué cambió (antes → después). */
class ProductHistoryTest extends ApiTestBase {
    private static String product(String name, long price, String extra) {
        return "{\"name\":\"" + name + "\",\"priceMinor\":" + price + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    @Test
    void everyChangeIsTracedWithWhoAndWhatChanged() throws Exception {
        String owner = login("hist-a");
        UUID b = createBusiness(owner, "Historial A");
        UUID cashier = createPinMember(owner, b, "Kevin", "CASHIER");
        UUID admin = createPinMember(owner, b, "Ana", "ADMIN");
        String device = linkDevice(owner, b);
        UUID id = UUID.randomUUID();
        String url = "/api/b/" + b + "/products/" + id;

        asDevice(put(url), device, cashier, product("Cuajada", 2500, "\"costMinor\":1800")).andExpect(status().isCreated());
        asDevice(put(url), device, cashier, product("Cuajada fresca", 3000, "\"costMinor\":1800")).andExpect(status().isOk());
        asDevice(put(url), device, cashier, product("Cuajada fresca", 3000, "\"costMinor\":1800")).andExpect(status().isOk()); // sin cambios: no deja rastro
        asDevice(delete(url), device, admin, null).andExpect(status().isNoContent());

        String path = "/api/b/" + b + "/products/" + id + "/history";
        // Cualquiera que atiende la caja lo puede ver, incluido el cajero.
        asDevice(get(path), device, cashier, null).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].action", is("product.deactivate"))).andExpect(jsonPath("$[0].actorName", is("Ana"))).andExpect(jsonPath("$[0].actorRole", is("ADMIN")))
                .andExpect(jsonPath("$[0].changes.active.from", is(true))).andExpect(jsonPath("$[0].changes.active.to", is(false)))
                .andExpect(jsonPath("$[1].action", is("product.update"))).andExpect(jsonPath("$[1].actorName", is("Kevin"))).andExpect(jsonPath("$[1].actorRole", is("CASHIER")))
                .andExpect(jsonPath("$[1].changes.priceMinor.from", is(2500))).andExpect(jsonPath("$[1].changes.priceMinor.to", is(3000)))
                .andExpect(jsonPath("$[1].changes.name.from", is("Cuajada"))).andExpect(jsonPath("$[1].changes.name.to", is("Cuajada fresca")))
                .andExpect(jsonPath("$[1].changes.costMinor").doesNotExist())
                .andExpect(jsonPath("$[2].action", is("product.create"))).andExpect(jsonPath("$[2].actorName", is("Kevin"))).andExpect(jsonPath("$[2].changes.priceMinor.to", is(2500)));
    }

    @Test
    void historyIsPrivateToTheBusinessAndTheAdminCanReactivate() throws Exception {
        String ownerA = login("hist-b");
        String ownerB = login("hist-c");
        UUID a = createBusiness(ownerA, "Historial B");
        UUID other = createBusiness(ownerB, "Historial C");
        UUID id = UUID.randomUUID();
        call(put("/api/b/" + a + "/products/" + id), bearer(ownerA), product("Queso", 900, "")).andExpect(status().isCreated());
        call(get("/api/b/" + a + "/products/" + id + "/history"), bearer(ownerB), null).andExpect(status().isNotFound());
        call(get("/api/b/" + other + "/products/" + id + "/history"), bearer(ownerB), null).andExpect(status().isNotFound());
        call(get("/api/b/" + a + "/products/" + UUID.randomUUID() + "/history"), bearer(ownerA), null).andExpect(status().isNotFound());
        // Quien tiene permiso de catálogo puede dar de baja y reactivar; ambos quedan en el historial.
        call(put("/api/b/" + a + "/products/" + id), bearer(ownerA), product("Queso", 900, "\"active\":false")).andExpect(status().isOk());
        call(put("/api/b/" + a + "/products/" + id), bearer(ownerA), product("Queso", 900, "\"active\":true")).andExpect(status().isOk());
        call(get("/api/b/" + a + "/products/" + id + "/history"), bearer(ownerA), null).andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].changes.active.to", is(true))).andExpect(jsonPath("$[1].changes.active.to", is(false)));
    }
}
