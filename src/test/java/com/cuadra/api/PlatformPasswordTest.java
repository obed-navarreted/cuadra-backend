package com.cuadra.api;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/** Consola con usuario y contraseña (el hash viene del entorno, nunca del código): entra, se equivoca, se bloquea, y no existe si no está configurada. */
@TestPropertySource(properties = {"cuadra.platform.admin-user=root", "cuadra.platform.admin-password-hash=$2b$12$8xkk3ZVKQnCJo1ixA5gET.5tUsa3P7BRGcUZN.qjrOZvvHs8sXCe."})
class PlatformPasswordTest extends ApiTestBase {
    private org.springframework.test.web.servlet.ResultActions attempt(String user, String pass) throws Exception {
        return mvc.perform(post("/api/auth/platform").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}"));
    }

    @Test
    void theRightCredentialsOpenTheConsoleAndWrongOnesDoNot() throws Exception {
        attempt("root", "mala").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code", is("INVALID_CREDENTIALS")));
        attempt("otro", "Prueba#Segura1").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code", is("INVALID_CREDENTIALS")));
        String json = attempt("root", "Prueba#Segura1").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(json, "$.token");
        call(get("/api/platform/metrics"), bearer(token), null).andExpect(status().isOk());
        call(get("/api/me"), bearer(token), null).andExpect(jsonPath("$.platformAdmin", is(true)));
        // Queda en la auditoría de la plataforma.
        call(get("/api/platform/audit"), bearer(token), null).andExpect(jsonPath("$.items[?(@.action=='platform.login')].action").isNotEmpty());
    }

    @Test
    void fiveFailuresLockThatUserEvenForTheRightPassword() throws Exception {
        for (int i = 0; i < 5; i++) attempt("bloqueable", "x").andExpect(status().isUnauthorized());
        attempt("bloqueable", "Prueba#Segura1").andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code", is("LOCKED")));
        // Otro usuario (el real) no se ve afectado por los intentos ajenos.
        attempt("root", "Prueba#Segura1").andExpect(status().isOk());
    }

    @Test
    void thirdConsoleSessionClosesTheOldest() throws Exception {
        String[] t = new String[3];
        for (int i = 0; i < 3; i++) {
            Thread.sleep(5);
            t[i] = JsonPath.read(attempt("root", "Prueba#Segura1").andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.token");
        }
        call(get("/api/me"), bearer(t[0]), null).andExpect(status().isUnauthorized());
        call(get("/api/me"), bearer(t[1]), null).andExpect(status().isOk());
        call(get("/api/me"), bearer(t[2]), null).andExpect(status().isOk());
        // "Cerrar todas mis sesiones" deja solo la actual.
        call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/me/sessions/revoke-all"), bearer(t[2]), null).andExpect(status().isOk());
        call(get("/api/me"), bearer(t[1]), null).andExpect(status().isUnauthorized());
        call(get("/api/me"), bearer(t[2]), null).andExpect(status().isOk());
    }
}
