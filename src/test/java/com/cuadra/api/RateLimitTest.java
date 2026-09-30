package com.cuadra.api;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/** Límites de peticiones por minuto: públicos por IP, el resto por credencial. */
@TestPropertySource(properties = {"cuadra.rate-limit.enabled=true", "cuadra.rate-limit.auth-per-minute=3",
        "cuadra.rate-limit.link-create-per-minute=2", "cuadra.rate-limit.credential-per-minute=5"})
class RateLimitTest extends ApiTestBase {
    private static final String BAD = "{\"idToken\":\"mal\"}";

    @Test
    void loginAttemptsFromOneAddressAreLimited() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/auth/google").contentType(MediaType.APPLICATION_JSON).content(BAD)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/google").contentType(MediaType.APPLICATION_JSON).content(BAD))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code", is("RATE_LIMITED"))).andExpect(header().exists("Retry-After"));
        // Otra dirección no paga por esta.
        mvc.perform(post("/api/auth/google").with(r -> { r.setRemoteAddr("10.9.9.9"); return r; }).contentType(MediaType.APPLICATION_JSON).content(BAD)).andExpect(status().isUnauthorized());
    }

    @Test
    void guessingPinsFromOneAddressIsLimited() throws Exception {
        String body = "{\"businessCode\":\"12345\",\"username\":\"Kevin\",\"pin\":\"11111\"}";
        for (int i = 0; i < 3; i++) mvc.perform(post("/api/auth/member-login").with(r -> { r.setRemoteAddr("10.1.1.1"); return r; }).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/member-login").with(r -> { r.setRemoteAddr("10.1.1.1"); return r; }).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isTooManyRequests());
    }

    @Test
    void creatingLinkRequestsIsLimitedPerAddress() throws Exception {
        String body = "{\"deviceName\":\"Tel\"}";
        for (int i = 0; i < 2; i++) mvc.perform(post("/api/devices/link-requests").with(r -> { r.setRemoteAddr("10.2.2.2"); return r; }).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mvc.perform(post("/api/devices/link-requests").with(r -> { r.setRemoteAddr("10.2.2.2"); return r; }).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isTooManyRequests());
    }

    @Test
    void oneStolenTokenCannotHammerTheServer() throws Exception {
        for (int i = 0; i < 5; i++) mvc.perform(get("/api/me").header("Authorization", "Bearer token-que-no-existe")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").header("Authorization", "Bearer token-que-no-existe")).andExpect(status().isTooManyRequests());
        // Otro token no se ve afectado.
        mvc.perform(get("/api/me").header("Authorization", "Bearer otro-token")).andExpect(status().isUnauthorized());
    }
}
