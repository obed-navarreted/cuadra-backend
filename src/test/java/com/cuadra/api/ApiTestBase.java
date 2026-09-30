package com.cuadra.api;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cuadra.api.common.ApiException;
import com.cuadra.api.security.GoogleIdentity;
import com.cuadra.api.security.GoogleTokenVerifier;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base de las pruebas de integración: un único PostgreSQL real para todas las clases (se arranca una vez)
 * y un verificador de Google falso con tokens `sub|email|nombre`.
 */
@SpringBootTest(properties = {"cuadra.pin-bcrypt-strength=4", "cuadra.jobs.enabled=false", "cuadra.rate-limit.enabled=false"})
@AutoConfigureMockMvc
@Import(ApiTestBase.FakeGoogle.class)
abstract class ApiTestBase {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @TestConfiguration
    static class FakeGoogle {
        @Bean
        @Primary
        GoogleTokenVerifier fakeVerifier() {
            return idToken -> {
                String[] p = idToken.split("\\|");
                if (p.length != 3) throw ApiException.unauthorized("INVALID_GOOGLE_TOKEN", "bad");
                return new GoogleIdentity(p[0], p[1], true, p[2], null);
            };
        }
    }

    @Autowired protected MockMvc mvc;

    // ---------- helpers ----------

    protected String login(String who) throws Exception {
        String body = "{\"idToken\":\"" + who + "-sub|" + who + "@test.com|" + who + "\"}";
        String json = mvc.perform(post("/api/auth/google").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.token");
    }

    protected ResultActions call(MockHttpServletRequestBuilder req, String auth, String bodyJson) throws Exception {
        req.header("Authorization", auth);
        if (bodyJson != null) req.contentType(MediaType.APPLICATION_JSON).content(bodyJson);
        return mvc.perform(req);
    }

    /** Petición como un teléfono vinculado con una persona concreta (validada con su PIN en el teléfono). */
    protected ResultActions asDevice(MockHttpServletRequestBuilder req, String deviceToken, UUID memberId, String bodyJson) throws Exception {
        req.header("Authorization", "Device " + deviceToken).header("X-Member-Id", memberId.toString());
        if (bodyJson != null) req.contentType(MediaType.APPLICATION_JSON).content(bodyJson);
        return mvc.perform(req);
    }

    protected static String bearer(String token) { return "Bearer " + token; }

    protected UUID createBusiness(String userToken, String name) throws Exception {
        String json = call(post("/api/businesses"), bearer(userToken), "{\"name\":\"" + name + "\",\"country\":\"NI\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(json, "$.id"));
    }

    protected UUID memberIdOf(String userToken, UUID businessId) throws Exception {
        String json = call(get("/api/me"), bearer(userToken), null).andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(json, "$.businesses[?(@.businessId=='" + businessId + "')].memberId");
        return UUID.fromString(ids.get(0));
    }

    /** Crea un miembro con PIN y devuelve su id. */
    protected UUID createPinMember(String ownerToken, UUID businessId, String name, String role) throws Exception {
        String json = call(post("/api/b/" + businessId + "/members"), bearer(ownerToken),
                "{\"displayName\":\"" + name + "\",\"role\":\"" + role + "\",\"pin\":\"12345\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(json, "$.id"));
    }

    @Autowired protected org.springframework.jdbc.core.simple.JdbcClient baseJdbc;

    /** Siembra una persona con cuenta de Google y el rol dado en el negocio (ya no hay invitaciones: el modelo la admite, pero solo el dueño usa Google). */
    protected String joinAs(String ownerToken, UUID businessId, String who, String role) throws Exception {
        String guest = login(who);
        String me = call(get("/api/me"), bearer(guest), null).andReturn().getResponse().getContentAsString();
        UUID userId = UUID.fromString(JsonPath.read(me, "$.id"));
        baseJdbc.sql("INSERT INTO member (id, business_id, user_account_id, display_name, role, created_by_member_id) VALUES (:id, :b, :u, :n, :r, :id)")
                .param("id", UUID.randomUUID()).param("b", businessId).param("u", userId).param("n", who).param("r", role).update();
        return guest;
    }

    protected static void assertCode(ResultActions r, String code) throws Exception {
        r.andExpect(jsonPath("$.code", is(code)));
    }

    /** Vincula un teléfono siguiendo el flujo real: código → reclamo → recogida única del token. */
    protected String linkDevice(String adminToken, UUID businessId) throws Exception {
        String req = mvc.perform(post("/api/devices/link-requests").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceName\":\"Samsung A15\",\"model\":\"SM-A155\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(req, "$.code");
        String secret = JsonPath.read(req, "$.pollSecret");

        mvc.perform(get("/api/devices/link-requests/" + code).header("X-Poll-Secret", secret))
                .andExpect(jsonPath("$.status", is("PENDING")));
        mvc.perform(get("/api/devices/link-requests/" + code).header("X-Poll-Secret", "wrong")).andExpect(status().isNotFound());

        call(post("/api/b/" + businessId + "/devices/claim"), bearer(adminToken), "{\"code\":\"" + code + "\",\"name\":\"Caja 1\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name", is("Caja 1")));

        String claimed = mvc.perform(get("/api/devices/link-requests/" + code).header("X-Poll-Secret", secret))
                .andExpect(jsonPath("$.status", is("CLAIMED"))).andExpect(jsonPath("$.businessId", is(businessId.toString())))
                .andReturn().getResponse().getContentAsString();
        // Segunda recogida: el token ya no se entrega.
        mvc.perform(get("/api/devices/link-requests/" + code).header("X-Poll-Secret", secret))
                .andExpect(jsonPath("$.status", is("COLLECTED"))).andExpect(jsonPath("$.deviceToken", nullValue()));
        return JsonPath.read(claimed, "$.deviceToken");
    }
}
