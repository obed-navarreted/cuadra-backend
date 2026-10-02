package com.cuadra.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Entrar con CÓDIGO DEL NEGOCIO + PIN (sin Google ni usuario: el PIN identifica a la persona) para admins y cajeros creados por el dueño. Cualquier persona usa cualquier teléfono del negocio (ver `PinElevationTest`).
 */
class MemberLoginTest extends ApiTestBase {
    @Autowired JdbcClient jdbc;

    private String codeOf(String owner, UUID b) throws Exception {
        String json = call(get("/api/b/" + b), bearer(owner), null).andExpect(jsonPath("$.accessCode", notNullValue())).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.accessCode");
    }

    private ResultActions login(String code, String pin) throws Exception {
        return mvc.perform(post("/api/auth/member-login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"businessCode\":\"" + code + "\",\"pin\":\"" + pin + "\",\"deviceName\":\"Mi teléfono\",\"model\":\"X\"}"));
    }

    private String tokenOf(ResultActions r) throws Exception {
        return JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.deviceToken");
    }

    @Test
    void aCashierEntersWithBusinessCodeAndPin() throws Exception {
        String owner = login("ml-a");
        UUID b = createBusiness(owner, "Panadería");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER", "24681");
        UUID ana = createPinMember(owner, b, "Ana", "ADMIN", "13579");
        String code = codeOf(owner, b);
        // Sin distinguir mayúsculas, espacios ni guiones del código. El PIN dice quién es.
        String spaced = code.substring(0, 3).toLowerCase() + "-" + code.substring(3);
        ResultActions ok = login(spaced, "24681").andExpect(status().isOk()).andExpect(jsonPath("$.memberId", is(kevin.toString()))).andExpect(jsonPath("$.role", is("CASHIER")))
                .andExpect(jsonPath("$.memberName", is("Kevin"))).andExpect(jsonPath("$.businessId", is(b.toString()))).andExpect(jsonPath("$.deviceToken", notNullValue()));
        String device = tokenOf(ok);
        login(code, "13579").andExpect(status().isOk()).andExpect(jsonPath("$.memberId", is(ana.toString()))).andExpect(jsonPath("$.role", is("ADMIN")));
        // Una app vieja que todavía manda el usuario: se ignora (manda el PIN).
        mvc.perform(post("/api/auth/member-login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessCode\":\"" + code + "\",\"username\":\"Ana\",\"pin\":\"24681\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.memberId", is(kevin.toString())));
        // El teléfono ya trabaja como Kevin y recibe a TODAS las personas del negocio (con el hash del PIN para entrar sin conexión): cualquiera puede usarlo.
        asDevice(get("/api/b/" + b + "/members"), device, kevin, null).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[?(@.displayName=='Kevin')].pinHash", hasSize(1))).andExpect(jsonPath("$[?(@.displayName=='Ana')].pinHash", hasSize(1)));
        asDevice(get("/api/b/" + b + "/plan"), device, kevin, null).andExpect(status().isOk());
        // Una persona dada de baja ya no entra.
        call(put("/api/b/" + b + "/members/" + ana), bearer(owner), "{\"status\":\"DISABLED\"}").andExpect(status().isOk());
        assertCode(login(code, "13579").andExpect(status().isUnauthorized()), "INVALID_CREDENTIALS");
    }

    @Test
    void everyFailureLooksTheSameAndTenWrongPinsPauseTheBusiness() throws Exception {
        String owner = login("ml-b");
        UUID b = createBusiness(owner, "Tienda B");
        createPinMember(owner, b, "Lucía", "CASHIER", "12345");
        String code = codeOf(owner, b);
        UUID other = createBusiness(owner, "Tienda B2");
        createPinMember(owner, other, "Rosa", "CASHIER", "12345");   // el mismo PIN en OTRO negocio está bien
        String otherCode = codeOf(owner, other);
        assertCode(login(code, "99995").andExpect(status().isUnauthorized()), "INVALID_CREDENTIALS");
        assertCode(login("ZZZZZZ", "12345").andExpect(status().isUnauthorized()), "INVALID_CREDENTIALS");
        assertCode(login(code, "12a45").andExpect(status().isUnauthorized()), "INVALID_CREDENTIALS");
        // Fallos de hace más de 15 minutos no cuentan: la ventana vuelve a empezar.
        baseJdbc.sql("UPDATE business SET member_login_window_start = now() - interval '16 minutes' WHERE id = :b").param("b", b).update();
        for (int i = 0; i < 9; i++) login(code, "0000" + i).andExpect(status().isUnauthorized());   // 9 en la ventana nueva
        login(code, "12345").andExpect(status().isOk());                                            // un acierto reinicia la cuenta
        for (int i = 0; i < 9; i++) login(code, "0000" + i).andExpect(status().isUnauthorized());
        assertCode(login(code, "00009").andExpect(status().isUnauthorized()), "INVALID_CREDENTIALS");   // el 10.º pausa el negocio
        // En pausa: ni con el PIN correcto. El otro negocio sigue normal.
        assertCode(login(code, "12345").andExpect(status().isTooManyRequests()), "LOCKED");
        login(otherCode, "12345").andExpect(status().isOk());
        // El dueño se entera UNA vez (sin nombre: nadie sabe quién se equivocó).
        call(get("/api/b/" + b + "/notifications?size=50"), bearer(owner), null).andExpect(jsonPath("$.items[?(@.type=='PIN_LOCKOUT')]", hasSize(1)));
        for (int i = 0; i < 3; i++) login(code, "00001").andExpect(status().isTooManyRequests());
        call(get("/api/b/" + b + "/notifications?size=50"), bearer(owner), null).andExpect(jsonPath("$.items[?(@.type=='PIN_LOCKOUT')]", hasSize(1)));
        // A los 15 minutos se levanta sola.
        baseJdbc.sql("UPDATE business SET member_login_locked_until = now() - interval '1 second' WHERE id = :b").param("b", b).update();
        login(code, "12345").andExpect(status().isOk());
    }

    @Test
    void aPinIsNotRepeatedAmongTheActiveMembersOfABusiness() throws Exception {
        String owner = login("ml-p");
        UUID b = createBusiness(owner, "Tienda P");
        UUID ownerMember = memberIdOf(owner, b);
        call(put("/api/b/" + b + "/members/" + ownerMember + "/pin"), bearer(owner), "{\"pin\":\"55555\"}").andExpect(status().isNoContent());
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER", "11111");
        UUID ana = createPinMember(owner, b, "Ana", "CASHIER", "22222");
        // Alta con un PIN que ya usa otra persona (el dueño también cuenta).
        assertCode(call(post("/api/b/" + b + "/members"), bearer(owner), "{\"displayName\":\"Luis\",\"role\":\"CASHIER\",\"pin\":\"11111\"}").andExpect(status().isConflict()), "PIN_TAKEN");
        assertCode(call(post("/api/b/" + b + "/members"), bearer(owner), "{\"displayName\":\"Luis\",\"role\":\"CASHIER\",\"pin\":\"55555\"}").andExpect(status().isConflict()), "PIN_TAKEN");
        // Restablecer a uno ajeno: no; al suyo mismo: sí.
        assertCode(call(put("/api/b/" + b + "/members/" + ana + "/pin"), bearer(owner), "{\"pin\":\"11111\"}").andExpect(status().isConflict()), "PIN_TAKEN");
        call(put("/api/b/" + b + "/members/" + ana + "/pin"), bearer(owner), "{\"pin\":\"22222\"}").andExpect(status().isNoContent());
        assertCode(call(put("/api/b/" + b + "/members/" + ownerMember + "/pin"), bearer(owner), "{\"pin\":\"22222\"}").andExpect(status().isConflict()), "PIN_TAKEN");
        // Renombrar no tiene que ver con el PIN.
        call(put("/api/b/" + b + "/members/" + kevin), bearer(owner), "{\"displayName\":\"Kevin R\"}").andExpect(status().isOk());
        // De baja, su PIN queda libre… pero si alguien lo toma, para volver necesita uno nuevo.
        call(put("/api/b/" + b + "/members/" + kevin), bearer(owner), "{\"status\":\"DISABLED\"}").andExpect(status().isOk());
        createPinMember(owner, b, "Luis", "CASHIER", "11111");
        assertCode(call(put("/api/b/" + b + "/members/" + kevin), bearer(owner), "{\"status\":\"ACTIVE\"}").andExpect(status().isConflict()), "PIN_TAKEN");
        assertCode(call(put("/api/b/" + b + "/members/" + kevin + "/pin"), bearer(owner), "{\"pin\":\"22222\"}").andExpect(status().isConflict()), "PIN_TAKEN");
        call(put("/api/b/" + b + "/members/" + kevin + "/pin"), bearer(owner), "{\"pin\":\"33333\"}").andExpect(status().isNoContent());
        call(put("/api/b/" + b + "/members/" + kevin), bearer(owner), "{\"status\":\"ACTIVE\"}").andExpect(status().isOk());
        login(codeOf(owner, b), "33333").andExpect(status().isOk()).andExpect(jsonPath("$.memberId", is(kevin.toString())));
        // Alguien dado de baja SIN choque vuelve tal cual.
        call(put("/api/b/" + b + "/members/" + ana), bearer(owner), "{\"status\":\"DISABLED\"}").andExpect(status().isOk());
        call(put("/api/b/" + b + "/members/" + ana), bearer(owner), "{\"status\":\"ACTIVE\"}").andExpect(status().isOk());
        login(codeOf(owner, b), "22222").andExpect(status().isOk()).andExpect(jsonPath("$.memberId", is(ana.toString())));
    }

    @Test
    void theOwnerSignsInWithGoogleNotWithAPin() throws Exception {
        String owner = login("ml-c");
        UUID b = createBusiness(owner, "Tienda C");
        UUID ownerMember = memberIdOf(owner, b);
        call(put("/api/b/" + b + "/members/" + ownerMember + "/pin"), bearer(owner), "{\"pin\":\"43215\"}").andExpect(status().isNoContent());
        login(codeOf(owner, b), "43215").andExpect(status().isForbidden()).andExpect(jsonPath("$.code", is("OWNER_USES_GOOGLE")));
        login(codeOf(owner, b), "11115").andExpect(status().isUnauthorized());
    }

    @Test
    void namesAreUniqueInsideABusinessAndTheCodeCanBeRenewed() throws Exception {
        String owner = login("ml-e");
        UUID b = createBusiness(owner, "Tienda E");
        createPinMember(owner, b, "Kevin", "CASHIER");
        assertCode(call(post("/api/b/" + b + "/members"), bearer(owner), "{\"displayName\":\" kevin \",\"role\":\"CASHIER\",\"pin\":\"98765\"}").andExpect(status().isConflict()), "NAME_TAKEN");
        UUID other = createPinMember(owner, b, "Otra", "CASHIER");
        assertCode(call(put("/api/b/" + b + "/members/" + other), bearer(owner), "{\"displayName\":\"KEVIN\"}").andExpect(status().isConflict()), "NAME_TAKEN");
        call(put("/api/b/" + b + "/members/" + other), bearer(owner), "{\"displayName\":\"Otra persona\"}").andExpect(status().isOk());

        String oldCode = codeOf(owner, b);
        String json = call(post("/api/b/" + b + "/access-code"), bearer(owner), null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String newCode = JsonPath.read(json, "$.accessCode");
        org.junit.jupiter.api.Assertions.assertNotEquals(oldCode, newCode);
        login(oldCode, pinOf(b, "Kevin")).andExpect(status().isUnauthorized());
        login(newCode, pinOf(b, "Kevin")).andExpect(status().isOk());
        // Solo el dueño lo renueva.
        String admin = joinAs(owner, b, "ml-e2", "ADMIN");
        call(post("/api/b/" + b + "/access-code"), bearer(admin), null).andExpect(status().isForbidden());
    }

    @Test
    void theDeviceLimitIsTwentyPerBusiness() throws Exception {
        String owner = login("ml-f");
        UUID b = createBusiness(owner, "Tienda F");
        for (int i = 0; i < 6; i++) createPinMember(owner, b, "Cajero" + i, "CASHIER");
        String code = codeOf(owner, b);
        // Cada persona puede tener 2 teléfonos activos; el negocio, hasta 20 en total.
        for (int i = 0; i < 6; i++) { login(code, pinOf(b, "Cajero" + i)).andExpect(status().isOk()); login(code, pinOf(b, "Cajero" + i)).andExpect(status().isOk()); }
        jdbc.sql("INSERT INTO device (id, business_id, kind, name, token_hash) SELECT gen_random_uuid(), :b, 'SHARED', 'x' || g, 'h' || g || :b::text FROM generate_series(1, 8) g").param("b", b).update();
        login(code, pinOf(b, "Cajero0")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code", is("PLAN_LIMIT"))).andExpect(jsonPath("$.feature", is("DEVICES")));
    }

    /** El código es de 5 dígitos, único; el dueño puede elegir uno propio (libre) y solo él. */
    @Test
    void theCodeHasFiveDigitsAndTheOwnerCanChooseAFreeOne() throws Exception {
        String owner = login("ml-g");
        UUID b = createBusiness(owner, "Tienda G");
        org.junit.jupiter.api.Assertions.assertTrue(codeOf(owner, b).matches("[1-9]\\d{4}"), "el código debe ser de 5 dígitos");
        UUID other = createBusiness(login("ml-h"), "Tienda H");
        String taken = codeOf(login("ml-h"), other);
        for (String bad : new String[] {"1234", "123456", "01234", "12a45", " "}) {
            call(put("/api/b/" + b + "/access-code"), bearer(owner), "{\"accessCode\":\"" + bad + "\"}").andExpect(status().isBadRequest());
        }
        assertCode(call(put("/api/b/" + b + "/access-code"), bearer(owner), "{\"accessCode\":\"" + taken + "\"}").andExpect(status().isConflict()), "ACCESS_CODE_TAKEN");
        String mine = taken.equals("77777") ? "77778" : "77777";
        call(put("/api/b/" + b + "/access-code"), bearer(owner), "{\"accessCode\":\"" + mine + "\"}").andExpect(status().isOk()).andExpect(jsonPath("$.accessCode", is(mine)));
        org.junit.jupiter.api.Assertions.assertEquals(mine, codeOf(owner, b));
        String admin = joinAs(owner, b, "ml-g2", "ADMIN");
        call(put("/api/b/" + b + "/access-code"), bearer(admin), "{\"accessCode\":\"88888\"}").andExpect(status().isForbidden());
    }
}
