package com.cuadra.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Con UNA sola conexión en el pool, una petición que pide una segunda conexión mientras conserva la primera se queda esperando para siempre
 * (con el pool real pasa cuando hay más peticiones simultáneas que conexiones: la prueba de carga lo mostró como 401 tras 5 s).
 * Aquí cualquier código así falla en 1 s en vez de aparecer en producción bajo carga.
 */
@TestPropertySource(properties = {"spring.datasource.hikari.maximum-pool-size=1", "spring.datasource.hikari.minimum-idle=1", "spring.datasource.hikari.connection-timeout=1500"})
class PoolSafetyTest extends ApiTestBase {
    @Autowired JdbcClient jdbc;

    /** Flyway pide dos conexiones a la vez: con un pool de una sola se le da su propia conexión para que las migraciones no lo impidan. */
    @DynamicPropertySource
    static void flywayOwnConnection(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Test
    void theMainFlowsNeverNeedASecondConnectionWhileHoldingTheFirst() throws Exception {
        String owner = login("pool-a");
        UUID b = createBusiness(owner, "Pool");
        String base = "/api/b/" + b;
        // La sesión y el teléfono con `last_seen` viejo: el caso que pedía una segunda conexión dentro del mapeo de filas.
        jdbc.sql("UPDATE auth_session SET last_seen_at = now() - interval '2 days'").update();
        call(get(base + "/plan"), bearer(owner), null).andExpect(status().isOk());
        String device = linkDevice(owner, b);
        UUID member = memberIdOf(owner, b);
        jdbc.sql("UPDATE device SET last_seen_at = now() - interval '2 days'").update();
        asDevice(get(base + "/plan"), device, member, null).andExpect(status().isOk());

        UUID product = UUID.randomUUID();
        asDevice(put(base + "/products/" + product), device, member, "{\"name\":\"Queso\",\"priceMinor\":900}").andExpect(status().isCreated());
        UUID sale = UUID.randomUUID();
        asDevice(put(base + "/sales/" + sale), device, member, SaleTest.sale("COMPLETED", SaleTest.item("Queso", 900, 1000), SaleTest.pay("CASH", 900, ""), ""))
                .andExpect(status().isCreated());
        String op = UUID.randomUUID().toString();
        asDevice(post(base + "/sync/push"), device, member, "{\"ops\":[{\"opId\":\"" + op + "\",\"kind\":\"PRODUCT_UPSERT\",\"entityId\":\"" + UUID.randomUUID()
                + "\",\"payload\":{\"name\":\"Cuajada\",\"priceMinor\":2500}}]}").andExpect(status().isOk());
        asDevice(get(base + "/sync/pull?since=0"), device, member, null).andExpect(status().isOk());
        call(get(base + "/reports/overview"), bearer(owner), null).andExpect(status().isOk());
        call(get(base + "/notifications"), bearer(owner), null).andExpect(status().isOk());
        call(get(base + "/activity"), bearer(owner), null).andExpect(status().isOk());
        call(get("/api/me"), bearer(owner), null).andExpect(status().isOk());
    }
}
