package com.cuadra.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** El panel en otro origen (GitHub Pages): solo los orígenes configurados, exactos y sin credenciales de navegador. */
@TestPropertySource(properties = "cuadra.cors.allowed-origins=https://obed.github.io")
class CorsTest extends ApiTestBase {
    @Test
    void thePreflightFromTheConfiguredOriginIsAllowed() throws Exception {
        mvc.perform(options("/api/me").header("Origin", "https://obed.github.io").header("Access-Control-Request-Method", "GET").header("Access-Control-Request-Headers", "authorization,x-member-id"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "https://obed.github.io"))
                .andExpect(header().exists("Access-Control-Allow-Headers")).andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }

    @Test
    void otherOriginsAreRefused() throws Exception {
        mvc.perform(options("/api/me").header("Origin", "https://evil.example").header("Access-Control-Request-Method", "GET")).andExpect(status().isForbidden());
        mvc.perform(get("/api/config").header("Origin", "https://evil.example")).andExpect(status().isForbidden());
        // Un subdominio parecido tampoco.
        mvc.perform(options("/api/me").header("Origin", "https://obed.github.io.evil.example").header("Access-Control-Request-Method", "GET")).andExpect(status().isForbidden());
    }

    @Test
    void realResponsesCarryTheAllowOriginHeader() throws Exception {
        mvc.perform(get("/api/config").header("Origin", "https://obed.github.io")).andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "https://obed.github.io"));
    }
}
