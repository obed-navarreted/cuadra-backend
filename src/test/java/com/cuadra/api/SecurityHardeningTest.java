package com.cuadra.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cuadra.api.config.CuadraProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Cabeceras de seguridad, tope de tamaño y la guardia de configuración de producción. */
class SecurityHardeningTest extends ApiTestBase {
    @Test
    void theApiSendsRestrictiveSecurityHeaders() throws Exception {
        mvc.perform(get("/api/config")).andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                .andExpect(header().string("Referrer-Policy", is("no-referrer")))
                .andExpect(header().string("X-Content-Type-Options", is("nosniff")))
                .andExpect(header().string("X-Frame-Options", is("DENY")))
                .andExpect(header().string("Permissions-Policy", containsString("camera=()")));
    }

    @Test
    void passwordLoginOfTheConsoleDoesNotExistUnlessConfigured() throws Exception {
        mvc.perform(post("/api/auth/platform").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"root\",\"password\":\"x\"}")).andExpect(status().isNotFound());
    }

    @Test
    void anOversizedBodyIsRejectedBeforeItIsRead() throws Exception {
        String owner = login("hard-a");
        byte[] big = new byte[3 * 1024 * 1024];
        java.util.Arrays.fill(big, (byte) 'a');
        mvc.perform(post("/api/businesses").header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON).content(big))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code", is("PAYLOAD_TOO_LARGE")));
    }

    @Test
    void productionRefusesToStartWithDevelopmentValues() {
        CuadraProperties bad = new CuadraProperties(null, null, 12, new CuadraProperties.Google(List.of()), null, new CuadraProperties.Platform(List.of(), "", ""),
                new CuadraProperties.App("http://localhost:5173", "0", "", "hidden"));
        List<String> problems = com.cuadra.api.config.ProductionGuardProbe.problems(bad, "prod", "cuadra", false, false, List.of("http://localhost:5173"));
        assertEquals(7, problems.size(), problems.toString());
        // En desarrollo no molesta.
        assertTrue(com.cuadra.api.config.ProductionGuardProbe.problems(bad, "dev", "cuadra", false, false, List.of()).isEmpty());
        CuadraProperties good = new CuadraProperties(null, null, 12, new CuadraProperties.Google(List.of("id.apps.googleusercontent.com")), null,
                new CuadraProperties.Platform(List.of(), "", ""), new CuadraProperties.App("https://app.cuadra.example", "0", "", "hidden"));
        assertTrue(com.cuadra.api.config.ProductionGuardProbe.problems(good, "prod", "una-clave-larga-y-unica", true, true, List.of("https://obed.github.io")).isEmpty());
    }
}
