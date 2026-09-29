package com.cuadra.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.SerializationFeature;

/**
 * Genera el contrato de la API (`docs/openapi.json`) del que sale el cliente TypeScript del panel web. Una prueba lo escribe y comprueba que los
 * endpoints que usa el panel están: si alguien cambia la API sin regenerar el cliente, el panel deja de compilar en vez de fallar en producción.
 */
@TestPropertySource(properties = "springdoc.api-docs.enabled=true")
class OpenApiTest extends ApiTestBase {
    @Test
    void theContractIsGeneratedAndDescribesTheEndpointsThePanelUses() throws Exception {
        String token = login("opena");
        String json = call(get("/v3/api-docs"), bearer(token), null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Map<String, Object> paths = JsonPath.read(json, "$.paths");
        for (String path : new String[] {"/api/b/{businessId}/reports/overview", "/api/b/{businessId}/reports/sales/breakdown", "/api/b/{businessId}/reports/products", "/api/b/{businessId}/reports/receivables",
                "/api/b/{businessId}/products/import", "/api/b/{businessId}/sales", "/api/b/{businessId}/expenses", "/api/b/{businessId}/notification-schedules", "/api/b/{businessId}/purchases", "/api/me"}) {
            if (!paths.containsKey(path)) throw new AssertionError("Falta en el contrato: " + path + " (hay " + paths.size() + " rutas)");
        }
        // Los primitivos de las respuestas salen como obligatorios (el cliente recibe `totalMinor: number`, no `number | undefined`); los envoltorios de las entradas, no.
        List<String> salesRequired = JsonPath.read(json, "$.components.schemas.Sales.required");
        org.junit.jupiter.api.Assertions.assertTrue(salesRequired.containsAll(List.of("count", "totalMinor", "discountMinor")));
        Map<String, Object> movementInput = JsonPath.read(json, "$.components.schemas.MovementInput");
        org.junit.jupiter.api.Assertions.assertFalse(movementInput.containsKey("required"));
        // Los tipos con el mismo nombre simple chocarían en el contrato (uno pisaría al otro): el desglose de ventas y la importación deben ser distintos.
        Map<String, Object> schemas = JsonPath.read(json, "$.components.schemas");
        for (String name : new String[] {"Row", "ImportRow", "ImportRowResult", "ImportSummary", "ImportResult", "SalesReport"}) {
            if (!schemas.containsKey(name)) throw new AssertionError("Falta el tipo en el contrato: " + name);
        }
        Map<String, Object> row = JsonPath.read(json, "$.components.schemas.Row.properties");
        org.junit.jupiter.api.Assertions.assertTrue(row.containsKey("totalMinor") && row.containsKey("label"), "Row debe ser la fila del desglose de ventas");
        // Salida estable (claves ordenadas y con sangría): el archivo del repositorio solo cambia cuando cambia la API.
        JsonMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).enable(tools.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
        Object tree = mapper.readValue(json, Object.class);
        String pretty = mapper.writeValueAsString(tree) + "\n";
        // El contrato vive en el repositorio del backend; en el monorepo local además se copia a ../docs para generar el cliente del panel.
        Path own = Path.of("openapi.json");
        if (!Files.exists(own) || !Files.readString(own).equals(pretty)) Files.writeString(own, pretty);
        Path shared = Path.of("..", "docs", "openapi.json");
        if (Files.isDirectory(shared.getParent()) && (!Files.exists(shared) || !Files.readString(shared).equals(pretty))) Files.writeString(shared, pretty);
    }
}
