package com.cuadra.api;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Sesiones: máximo 2 por tipo y persona, 2 teléfonos personales por persona, cierre total al dar de baja o eliminar, y "Cerrar todas mis sesiones". */
class SessionLimitsTest extends ApiTestBase {
    @Autowired JdbcClient jdbc;

    private String googleLogin(String who, String kind) throws Exception {
        Thread.sleep(5);
        String body = "{\"idToken\":\"" + who + "-sub|" + who + "@test.com|" + who + "\",\"kind\":\"" + kind + "\"}";
        String json = mvc.perform(post("/api/auth/google").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.token");
    }

    private void alive(String token, boolean ok) throws Exception {
        call(get("/api/me"), bearer(token), null).andExpect(status().is(ok ? 200 : 401));
    }

    @Test
    void theThirdSessionOfTheSameKindClosesTheOldestAndKindsAreIndependent() throws Exception {
        String a1 = googleLogin("sl-a", "APP"), a2 = googleLogin("sl-a", "APP");
        String w1 = googleLogin("sl-a", "WEB");
        alive(a1, true); alive(a2, true); alive(w1, true);
        String a3 = googleLogin("sl-a", "APP");
        alive(a1, false); alive(a2, true); alive(a3, true); alive(w1, true);
        String w2 = googleLogin("sl-a", "WEB"), w3 = googleLogin("sl-a", "WEB");
        alive(w1, false); alive(w2, true); alive(w3, true); alive(a2, true); alive(a3, true);
        // Otra persona no se ve afectada.
        String other = googleLogin("sl-b", "APP");
        googleLogin("sl-b", "APP"); googleLogin("sl-b", "APP");
        alive(other, false);
        alive(a2, true);
    }

    @Test
    void closeAllMySessionsKeepsOnlyTheCurrentOne() throws Exception {
        String a = googleLogin("sl-c", "APP"), w = googleLogin("sl-c", "WEB"), w2 = googleLogin("sl-c", "WEB");
        call(post("/api/me/sessions/revoke-all"), bearer(w2), null).andExpect(status().isOk()).andExpect(jsonPath("$.revoked", is(2)));
        alive(a, false); alive(w, false); alive(w2, true);
        call(post("/api/me/sessions/revoke-all"), bearer(w2), null).andExpect(status().isOk()).andExpect(jsonPath("$.revoked", is(0)));
        mvc.perform(post("/api/me/sessions/revoke-all")).andExpect(status().isUnauthorized());
        String other = googleLogin("sl-d", "WEB");
        alive(other, true);
    }

    @Test
    void deletingTheAccountClosesEverySession() throws Exception {
        String a = googleLogin("sl-e", "APP"), w = googleLogin("sl-e", "WEB");
        call(delete("/api/me"), bearer(a), null).andExpect(status().isNoContent());
        alive(a, false); alive(w, false);
    }

    @Test
    void disablingAMemberClosesAllTheirSessionsAndPersonalPhones() throws Exception {
        String owner = login("sl-f");
        UUID b = createBusiness(owner, "Sesiones");
        String admin = joinAs(owner, b, "sl-g", "ADMIN");
        String admin2 = googleLogin("sl-g", "WEB");
        UUID adminMember = memberIdOf(admin, b);
        call(put("/api/b/" + b + "/members/" + adminMember), bearer(owner), "{\"status\":\"DISABLED\"}").andExpect(status().isOk());
        alive(admin, false); alive(admin2, false);
        alive(owner, true);
        // Re-activarlo no revive sesiones cerradas.
        call(put("/api/b/" + b + "/members/" + adminMember), bearer(owner), "{\"status\":\"ACTIVE\"}").andExpect(status().isOk());
        alive(admin, false);
    }

    private String memberLogin(String code, String user) throws Exception {
        Thread.sleep(5);
        String json = mvc.perform(post("/api/auth/member-login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessCode\":\"" + code + "\",\"username\":\"" + user + "\",\"pin\":\"12345\",\"deviceName\":\"Tel\",\"model\":\"X\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.deviceToken");
    }

    @Test
    void thirdPersonalPhoneOfAMemberClosesTheOldest() throws Exception {
        String owner = login("sl-h");
        UUID b = createBusiness(owner, "Teléfonos 2");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        UUID ana = createPinMember(owner, b, "Ana", "CASHIER");
        String code = JsonPath.read(call(get("/api/b/" + b), bearer(owner), null).andReturn().getResponse().getContentAsString(), "$.accessCode");
        String k1 = memberLogin(code, "Kevin"), k2 = memberLogin(code, "Kevin"), a1 = memberLogin(code, "Ana");
        asDevice(get("/api/b/" + b + "/members"), k1, kevin, null).andExpect(status().isOk());
        String k3 = memberLogin(code, "Kevin");
        asDevice(get("/api/b/" + b + "/members"), k1, kevin, null).andExpect(status().isUnauthorized());
        asDevice(get("/api/b/" + b + "/members"), k2, kevin, null).andExpect(status().isOk());
        asDevice(get("/api/b/" + b + "/members"), k3, kevin, null).andExpect(status().isOk());
        asDevice(get("/api/b/" + b + "/members"), a1, ana, null).andExpect(status().isOk());
        assertEquals(2, jdbc.sql("SELECT count(*) FROM device WHERE business_id = :b AND linked_by_member_id = :m AND revoked_at IS NULL").param("b", b).param("m", kevin).query(Integer.class).single());
        assertEquals("REPLACED", jdbc.sql("SELECT revoked_reason FROM device WHERE business_id = :b AND revoked_at IS NOT NULL").param("b", b).query(String.class).single());
    }
}
