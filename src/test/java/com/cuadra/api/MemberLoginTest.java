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
 * Entrar con CÓDIGO DEL NEGOCIO + USUARIO + PIN (sin Google) para admins y cajeros creados por el dueño. Un teléfono nunca tiene más poder que quien lo vinculó.
 */
class MemberLoginTest extends ApiTestBase {
    @Autowired JdbcClient jdbc;

    private String codeOf(String owner, UUID b) throws Exception {
        String json = call(get("/api/b/" + b), bearer(owner), null).andExpect(jsonPath("$.accessCode", notNullValue())).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.accessCode");
    }

    private ResultActions login(String code, String user, String pin) throws Exception {
        return mvc.perform(post("/api/auth/member-login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"businessCode\":\"" + code + "\",\"username\":\"" + user + "\",\"pin\":\"" + pin + "\",\"deviceName\":\"Mi teléfono\",\"model\":\"X\"}"));
    }

    private String tokenOf(ResultActions r) throws Exception {
        return JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.deviceToken");
    }

    @Test
    void aCashierEntersWithBusinessCodeUsernameAndPin() throws Exception {
        String owner = login("ml-a");
        UUID b = createBusiness(owner, "Panadería");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        String code = codeOf(owner, b);
        // Sin distinguir mayúsculas, espacios ni guiones del código, ni mayúsculas/espacios del usuario.
        String spaced = code.substring(0, 3).toLowerCase() + "-" + code.substring(3);
        ResultActions ok = login(spaced, "  KEVIN ", "12345").andExpect(status().isOk()).andExpect(jsonPath("$.memberId", is(kevin.toString()))).andExpect(jsonPath("$.role", is("CASHIER")))
                .andExpect(jsonPath("$.businessId", is(b.toString()))).andExpect(jsonPath("$.deviceToken", notNullValue()));
        String device = tokenOf(ok);
        // El teléfono ya trabaja como Kevin y recibe SOLO a las personas con las que puede actuar (con el hash del PIN para entrar sin conexión).
        asDevice(get("/api/b/" + b + "/members"), device, kevin, null).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].displayName", is("Kevin"))).andExpect(jsonPath("$[0].pinHash", notNullValue()));
        asDevice(get("/api/b/" + b + "/plan"), device, kevin, null).andExpect(status().isOk());
    }

    @Test
    void everyFailureLooksTheSameAndFiveWrongPinsLockThatPerson() throws Exception {
        String owner = login("ml-b");
        UUID b = createBusiness(owner, "Tienda B");
        createPinMember(owner, b, "Lucía", "CASHIER");
        String code = codeOf(owner, b);
        login(code, "Lucía", "99995").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code", is("INVALID_CREDENTIALS")));
        login(code, "Nadie", "12345").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code", is("INVALID_CREDENTIALS")));
        login("ZZZZZZ", "Lucía", "12345").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code", is("INVALID_CREDENTIALS")));
        for (int i = 0; i < 3; i++) login(code, "Lucía", "00005").andExpect(status().isUnauthorized());   // 4 fallos en total
        login(code, "Lucía", "00005").andExpect(status().isUnauthorized());                                 // el 5.º bloquea
        // Bloqueada: ni con el PIN correcto.
        login(code, "Lucía", "12345").andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code", is("LOCKED")));
        // El dueño se entera.
        call(get("/api/b/" + b + "/notifications?size=50"), bearer(owner), null).andExpect(jsonPath("$.items[?(@.type=='PIN_LOCKOUT')]", hasSize(1)));
    }

    @Test
    void theOwnerSignsInWithGoogleNotWithAPin() throws Exception {
        String owner = login("ml-c");
        UUID b = createBusiness(owner, "Tienda C");
        UUID ownerMember = memberIdOf(owner, b);
        call(put("/api/b/" + b + "/members/" + ownerMember + "/pin"), bearer(owner), "{\"pin\":\"43215\"}").andExpect(status().isNoContent());
        String name = JsonPath.read(call(get("/api/b/" + b + "/members"), bearer(owner), null).andReturn().getResponse().getContentAsString(), "$[0].displayName");
        login(codeOf(owner, b), name, "43215").andExpect(status().isForbidden()).andExpect(jsonPath("$.code", is("OWNER_USES_GOOGLE")));
        login(codeOf(owner, b), name, "11115").andExpect(status().isUnauthorized());
    }

    @Test
    void aPhoneNeverHasMorePowerThanWhoLinkedIt() throws Exception {
        String owner = login("ml-d");
        UUID b = createBusiness(owner, "Tienda D");
        UUID ownerMember = memberIdOf(owner, b);
        UUID cashier = createPinMember(owner, b, "Kevin", "CASHIER");
        UUID admin = createPinMember(owner, b, "Ana", "ADMIN");
        String code = codeOf(owner, b);

        String cashierPhone = tokenOf(login(code, "Kevin", "12345").andExpect(status().isOk()));
        // Aunque alguien fabrique la cabecera de un admin o del dueño, el servidor no se lo permite a un teléfono de cajero.
        assertCode(asDevice(get("/api/b/" + b + "/plan"), cashierPhone, admin, null).andExpect(status().isForbidden()), "DEVICE_NOT_TRUSTED");
        assertCode(asDevice(get("/api/b/" + b + "/plan"), cashierPhone, ownerMember, null).andExpect(status().isForbidden()), "DEVICE_NOT_TRUSTED");
        assertCode(asDevice(put("/api/b/" + b + "/members/" + cashier + "/pin"), cashierPhone, ownerMember, "{\"pin\":\"11115\"}").andExpect(status().isForbidden()), "DEVICE_NOT_TRUSTED");

        String adminPhone = tokenOf(login(code, "Ana", "12345").andExpect(status().isOk()));
        asDevice(get("/api/b/" + b + "/plan"), adminPhone, admin, null).andExpect(status().isOk());
        asDevice(get("/api/b/" + b + "/plan"), adminPhone, cashier, null).andExpect(status().isOk());
        assertCode(asDevice(get("/api/b/" + b + "/plan"), adminPhone, ownerMember, null).andExpect(status().isForbidden()), "DEVICE_NOT_TRUSTED");
        // El teléfono del admin ve a admin y cajero, no al dueño; el de un teléfono vinculado por el dueño lo ve todo.
        asDevice(get("/api/b/" + b + "/members"), adminPhone, admin, null).andExpect(jsonPath("$", hasSize(2)));
        String ownerPhone = linkDevice(owner, b);
        asDevice(get("/api/b/" + b + "/members"), ownerPhone, ownerMember, null).andExpect(jsonPath("$", hasSize(3)));
        // La sincronización también respeta el poder del teléfono.
        String pulled = asDevice(get("/api/b/" + b + "/sync/pull?since=0"), cashierPhone, cashier, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(pulled.contains("\"Ana\"") || pulled.contains("\"role\":\"OWNER\""), "un teléfono de cajero no debe recibir a los admins ni al dueño");
    }

    @Test
    void namesAreUniqueInsideABusinessAndTheCodeCanBeRenewed() throws Exception {
        String owner = login("ml-e");
        UUID b = createBusiness(owner, "Tienda E");
        createPinMember(owner, b, "Kevin", "CASHIER");
        assertCode(call(post("/api/b/" + b + "/members"), bearer(owner), "{\"displayName\":\" kevin \",\"role\":\"CASHIER\",\"pin\":\"12345\"}").andExpect(status().isConflict()), "NAME_TAKEN");
        UUID other = createPinMember(owner, b, "Otra", "CASHIER");
        assertCode(call(put("/api/b/" + b + "/members/" + other), bearer(owner), "{\"displayName\":\"KEVIN\"}").andExpect(status().isConflict()), "NAME_TAKEN");
        call(put("/api/b/" + b + "/members/" + other), bearer(owner), "{\"displayName\":\"Otra persona\"}").andExpect(status().isOk());

        String oldCode = codeOf(owner, b);
        String json = call(post("/api/b/" + b + "/access-code"), bearer(owner), null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String newCode = JsonPath.read(json, "$.accessCode");
        org.junit.jupiter.api.Assertions.assertNotEquals(oldCode, newCode);
        login(oldCode, "Kevin", "12345").andExpect(status().isUnauthorized());
        login(newCode, "Kevin", "12345").andExpect(status().isOk());
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
        for (int i = 0; i < 6; i++) { login(code, "Cajero" + i, "12345").andExpect(status().isOk()); login(code, "Cajero" + i, "12345").andExpect(status().isOk()); }
        jdbc.sql("INSERT INTO device (id, business_id, kind, name, token_hash) SELECT gen_random_uuid(), :b, 'SHARED', 'x' || g, 'h' || g || :b::text FROM generate_series(1, 8) g").param("b", b).update();
        login(code, "Cajero0", "12345").andExpect(status().isForbidden()).andExpect(jsonPath("$.code", is("PLAN_LIMIT"))).andExpect(jsonPath("$.feature", is("DEVICES")));
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
