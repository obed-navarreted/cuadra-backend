package com.cuadra.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cuadra.api.tenancy.TenantContext;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Elevación por PIN verificado (ADR 0012, actualización 2026-10-01): cualquier persona del negocio usa cualquier teléfono del negocio con su PIN. Por encima
 * del rol base del teléfono (el de quien lo vinculó) se actúa solo si el SERVIDOR comprobó el PIN de esa persona en ese teléfono; sin eso se vende y se cobra
 * igual y lo demás responde PIN_VERIFICATION_REQUIRED.
 */
class PinElevationTest extends ApiTestBase {
    @Autowired JdbcClient jdbc;

    private String codeOf(String owner, UUID b) throws Exception {
        return JsonPath.read(call(get("/api/b/" + b), bearer(owner), null).andReturn().getResponse().getContentAsString(), "$.accessCode");
    }

    /** Un teléfono vinculado por una persona del equipo con código + PIN: su rol base es el de esa persona. */
    private String phoneLinkedBy(String code, String pin) throws Exception {
        String json = mvc.perform(post("/api/auth/member-login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessCode\":\"" + code + "\",\"pin\":\"" + pin + "\",\"deviceName\":\"Caja compartida\",\"model\":\"X\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.deviceToken");
    }

    private ResultActions verify(String phone, UUID member, String pin) throws Exception {
        return mvc.perform(post("/api/devices/me/members/" + member + "/verify-pin").header("Authorization", "Device " + phone)
                .contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"" + pin + "\"}"));
    }

    private static String opBy(UUID member, Instant at, String kind, UUID entityId, String payload) {
        return "{\"opId\":\"" + UUID.randomUUID() + "\",\"kind\":\"" + kind + "\",\"entityId\":\"" + entityId + "\",\"payload\":" + payload
                + ",\"memberId\":\"" + member + "\",\"createdAt\":\"" + at + "\"}";
    }

    private static String push(String... ops) { return "{\"ops\":[" + String.join(",", ops) + "]}"; }

    private static final String WITHDRAWAL = "{\"kind\":\"WITHDRAWAL\",\"amountMinor\":1000}";

    private UUID deviceIdOf(String phone) throws Exception {
        return UUID.fromString(JsonPath.read(mvc.perform(get("/api/devices/me").header("Authorization", "Device " + phone)).andReturn().getResponse().getContentAsString(), "$.deviceId"));
    }

    @Test
    void onAPhoneLinkedByACashierAnaVerifiesHerPinAndThenManagesTheTeam() throws Exception {
        String owner = login("elev-a");
        UUID b = createBusiness(owner, "Elevación A");
        UUID ownerMember = memberIdOf(owner, b);
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        UUID ana = createPinMember(owner, b, "Ana", "ADMIN");
        String phone = phoneLinkedBy(codeOf(owner, b), pinOf(b, "Kevin"));

        // El teléfono recibe a todos (también al dueño y a Ana) con el hash de su PIN: cualquiera puede desbloquearlo.
        asDevice(get("/api/b/" + b + "/members"), phone, kevin, null).andExpect(jsonPath("$", hasSize(3)));
        mvc.perform(get("/api/devices/me").header("Authorization", "Device " + phone)).andExpect(status().isOk())
                .andExpect(jsonPath("$.baseRole", is("CASHIER"))).andExpect(jsonPath("$.grants", hasSize(0)));

        // Sin verificar: Ana vende (como cajera), pero gestionar el equipo o los teléfonos pide confirmar el PIN; nunca DEVICE_NOT_TRUSTED.
        String sale = SaleTest.sale("COMPLETED", SaleTest.item("Cuajada", 1500, 1000), SaleTest.pay("CASH", 1500, ""), "\"completedAt\":\"" + Instant.now() + "\"");
        asDevice(post("/api/b/" + b + "/sync/push"), phone, ana, push(opBy(ana, Instant.now(), "SALE_UPSERT", UUID.randomUUID(), sale)))
                .andExpect(jsonPath("$.results[0].status", is("APPLIED")));
        assertCode(asDevice(get("/api/b/" + b + "/devices"), phone, ana, null).andExpect(status().isForbidden()), "PIN_VERIFICATION_REQUIRED");
        assertCode(asDevice(post("/api/b/" + b + "/members"), phone, ana, "{\"displayName\":\"Luis\",\"role\":\"CASHIER\",\"pin\":\"71717\"}").andExpect(status().isForbidden()), "PIN_VERIFICATION_REQUIRED");
        assertCode(asDevice(get("/api/b/" + b + "/activity"), phone, ownerMember, null).andExpect(status().isForbidden()), "PIN_VERIFICATION_REQUIRED");
        // Kevin (cajero) sigue recibiendo el FORBIDDEN de siempre: no hay PIN que confirmar.
        assertCode(asDevice(get("/api/b/" + b + "/devices"), phone, kevin, null).andExpect(status().isForbidden()), "FORBIDDEN");

        // Ana confirma su PIN con el servidor en ESTE teléfono: ahora actúa como admin.
        verify(phone, ana, pinOf(b, "Ana")).andExpect(status().isOk()).andExpect(jsonPath("$.role", is("ADMIN"))).andExpect(jsonPath("$.baseRole", is("CASHIER")))
                .andExpect(jsonPath("$.expiresAt", notNullValue()));
        asDevice(get("/api/b/" + b + "/devices"), phone, ana, null).andExpect(status().isOk());
        asDevice(post("/api/b/" + b + "/members"), phone, ana, "{\"displayName\":\"Luis\",\"role\":\"CASHIER\",\"pin\":\"71717\"}").andExpect(status().isCreated());
        asDevice(post("/api/b/" + b + "/sync/push"), phone, ana, push(opBy(ana, Instant.now(), "CASH_MOVEMENT_UPSERT", UUID.randomUUID(), WITHDRAWAL)))
                .andExpect(jsonPath("$.results[0].status", is("APPLIED")));
        mvc.perform(get("/api/devices/me").header("Authorization", "Device " + phone)).andExpect(jsonPath("$.grants", hasSize(1)))
                .andExpect(jsonPath("$.grants[0].memberId", is(ana.toString())));
        // El permiso es de ESTE teléfono: en otro teléfono de cajero vuelve a pedirse.
        String other = phoneLinkedBy(codeOf(owner, b), pinOf(b, "Kevin"));
        assertCode(asDevice(get("/api/b/" + b + "/devices"), other, ana, null).andExpect(status().isForbidden()), "PIN_VERIFICATION_REQUIRED");
        // Y el dueño también verifica su PIN en un teléfono de cajero (su PIN para desbloquear la caja).
        call(put("/api/b/" + b + "/members/" + ownerMember + "/pin"), bearer(owner), "{\"pin\":\"60606\"}").andExpect(status().isNoContent());
        verify(phone, ownerMember, "60606").andExpect(status().isOk()).andExpect(jsonPath("$.role", is("OWNER")));
        asDevice(get("/api/b/" + b + "/activity"), phone, ownerMember, null).andExpect(status().isOk());
    }

    @Test
    void wrongPinsLockThePersonAndCountForTheBusiness() throws Exception {
        String owner = login("elev-b");
        UUID b = createBusiness(owner, "Elevación B");
        createPinMember(owner, b, "Kevin", "CASHIER");
        UUID ana = createPinMember(owner, b, "Ana", "ADMIN");
        String phone = phoneLinkedBy(codeOf(owner, b), pinOf(b, "Kevin"));
        String wrong = pinOf(b, "Ana").equals("99999") ? "99998" : "99999";
        for (int i = 0; i < 4; i++) assertCode(verify(phone, ana, wrong).andExpect(status().isUnauthorized()), "INVALID_CREDENTIALS");
        assertCode(verify(phone, ana, wrong).andExpect(status().isTooManyRequests()), "LOCKED");
        // Bloqueada: ni con el PIN correcto. El dueño se entera, y los fallos cuentan para la pausa del negocio.
        assertCode(verify(phone, ana, pinOf(b, "Ana")).andExpect(status().isTooManyRequests()), "LOCKED");
        call(get("/api/b/" + b + "/notifications?size=50"), bearer(owner), null).andExpect(jsonPath("$.items[?(@.type=='PIN_LOCKOUT')]", hasSize(1)));
        assertEquals(5, jdbc.sql("SELECT member_login_failures FROM business WHERE id = :b").param("b", b).query(Integer.class).single());
        // A los 15 minutos se levanta.
        baseJdbc.sql("UPDATE member SET pin_locked_until = now() - interval '1 second' WHERE id = :m").param("m", ana).update();
        verify(phone, ana, pinOf(b, "Ana")).andExpect(status().isOk());
        // Con la entrada del negocio en pausa tampoco se verifica.
        baseJdbc.sql("UPDATE business SET member_login_locked_until = now() + interval '10 minutes' WHERE id = :b").param("b", b).update();
        assertCode(verify(phone, ana, pinOf(b, "Ana")).andExpect(status().isTooManyRequests()), "LOCKED");
        // Alguien de otro negocio (o inventado) no se verifica aquí.
        baseJdbc.sql("UPDATE business SET member_login_locked_until = NULL WHERE id = :b").param("b", b).update();
        assertCode(verify(phone, UUID.randomUUID(), "12345").andExpect(status().isForbidden()), "MEMBER_NOT_ACTIVE");
    }

    @Test
    void theGrantIsRevokedWhenThePinIsResetOrThePersonIsDisabledOrThePhoneRevoked() throws Exception {
        String owner = login("elev-c");
        UUID b = createBusiness(owner, "Elevación C");
        createPinMember(owner, b, "Kevin", "CASHIER");
        UUID ana = createPinMember(owner, b, "Ana", "ADMIN");
        UUID rosa = createPinMember(owner, b, "Rosa", "ADMIN");
        String phone = phoneLinkedBy(codeOf(owner, b), pinOf(b, "Kevin"));
        verify(phone, ana, pinOf(b, "Ana")).andExpect(status().isOk());
        asDevice(get("/api/b/" + b + "/devices"), phone, ana, null).andExpect(status().isOk());
        // PIN restablecido: lo verificado con el PIN viejo ya no vale.
        call(put("/api/b/" + b + "/members/" + ana + "/pin"), bearer(owner), "{\"pin\":\"82828\"}").andExpect(status().isNoContent());
        assertCode(asDevice(get("/api/b/" + b + "/devices"), phone, ana, null).andExpect(status().isForbidden()), "PIN_VERIFICATION_REQUIRED");
        verify(phone, ana, "82828").andExpect(status().isOk());
        asDevice(get("/api/b/" + b + "/devices"), phone, ana, null).andExpect(status().isOk());
        // Baja: el permiso queda revocado (y además ya no está activa).
        call(put("/api/b/" + b + "/members/" + ana), bearer(owner), "{\"status\":\"DISABLED\"}").andExpect(status().isOk());
        assertEquals(0, jdbc.sql("SELECT count(*) FROM device_member_grant WHERE member_id = :m AND revoked_at IS NULL").param("m", ana).query(Integer.class).single());
        assertCode(verify(phone, ana, "82828").andExpect(status().isForbidden()), "MEMBER_NOT_ACTIVE");
        // Teléfono revocado: sus permisos también.
        verify(phone, rosa, pinOf(b, "Rosa")).andExpect(status().isOk());
        UUID deviceId = deviceIdOf(phone);
        call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/b/" + b + "/devices/" + deviceId), bearer(owner), null).andExpect(status().isNoContent());
        assertEquals(0, jdbc.sql("SELECT count(*) FROM device_member_grant WHERE device_id = :d AND revoked_at IS NULL").param("d", deviceId).query(Integer.class).single());
    }

    @Test
    void whatWasDoneOfflineWhileTheGrantWasValidIsAcceptedLater() throws Exception {
        String owner = login("elev-d");
        UUID b = createBusiness(owner, "Elevación D");
        createPinMember(owner, b, "Kevin", "CASHIER");
        UUID ana = createPinMember(owner, b, "Ana", "ADMIN");
        UUID kevin = memberByName(b, "Kevin");
        String phone = phoneLinkedBy(codeOf(owner, b), pinOf(b, "Kevin"));
        UUID deviceId = deviceIdOf(phone);
        Instant now = Instant.now();
        // El teléfono se vinculó hace dos días; Ana verificó su PIN hace 20 h y el permiso venció hace 8 h (sin conexión desde entonces).
        baseJdbc.sql("UPDATE device SET linked_at = :t WHERE id = :d").param("t", java.sql.Timestamp.from(now.minus(Duration.ofDays(2)))).param("d", deviceId).update();
        baseJdbc.sql("INSERT INTO device_member_grant (id, device_id, member_id, business_id, granted_at, expires_at) VALUES (:id, :d, :m, :b, :g, :e)")
                .param("id", UUID.randomUUID()).param("d", deviceId).param("m", ana).param("b", b)
                .param("g", java.sql.Timestamp.from(now.minus(Duration.ofHours(20)))).param("e", java.sql.Timestamp.from(now.minus(Duration.ofHours(8)))).update();
        String url = "/api/b/" + b + "/sync/push";
        // Hecho hace 15 h (dentro del permiso): se acepta aunque llegue ahora y el permiso ya venció.
        asDevice(post(url), phone, kevin, push(opBy(ana, now.minus(Duration.ofHours(15)), "CASH_MOVEMENT_UPSERT", UUID.randomUUID(), WITHDRAWAL)))
                .andExpect(jsonPath("$.results[0].status", is("APPLIED")));
        // Hecho hace 2 h (permiso vencido): no; y no se recuerda el rechazo, porque se puede resolver confirmando el PIN.
        String late = opBy(ana, now.minus(Duration.ofHours(2)), "CASH_MOVEMENT_UPSERT", UUID.randomUUID(), WITHDRAWAL);
        asDevice(post(url), phone, kevin, push(late)).andExpect(jsonPath("$.results[0].status", is("REJECTED")))
                .andExpect(jsonPath("$.results[0].code", is("PIN_VERIFICATION_REQUIRED"))).andExpect(jsonPath("$.results[0].detail.memberId", is(ana.toString())));
        // Una hora del teléfono imposible (antes de vincularlo) no cuela aunque caiga en un permiso.
        asDevice(post(url), phone, kevin, push(opBy(ana, now.minus(Duration.ofDays(3)), "CASH_MOVEMENT_UPSERT", UUID.randomUUID(), WITHDRAWAL)))
                .andExpect(jsonPath("$.results[0].code", is("PIN_VERIFICATION_REQUIRED")));
        // Ana confirma su PIN («Confirmar PIN» en Requiere atención): lo que quedó en su cola de las últimas 48 h se acepta.
        verify(phone, ana, pinOf(b, "Ana")).andExpect(status().isOk());
        asDevice(post(url), phone, kevin, push(late)).andExpect(jsonPath("$.results[0].status", is("APPLIED")));
        // Vender nunca necesitó permiso.
        String sale = SaleTest.sale("COMPLETED", SaleTest.item("Pan", 500, 1000), SaleTest.pay("CASH", 500, ""), "\"completedAt\":\"" + now.minus(Duration.ofHours(3)) + "\"");
        asDevice(post(url), phone, kevin, push(opBy(kevin, now.minus(Duration.ofHours(3)), "SALE_UPSERT", UUID.randomUUID(), sale)))
                .andExpect(jsonPath("$.results[0].status", is("APPLIED")));
    }

    @Test
    void grantsAreIsolatedPerBusinessByRls() throws Exception {
        String ownerA = login("elev-e");
        String ownerB = login("elev-f");
        UUID a = createBusiness(ownerA, "Elevación E");
        UUID b = createBusiness(ownerB, "Elevación F");
        createPinMember(ownerA, a, "Kevin", "CASHIER");
        UUID ana = createPinMember(ownerA, a, "Ana", "ADMIN");
        String phone = phoneLinkedBy(codeOf(ownerA, a), pinOf(a, "Kevin"));
        verify(phone, ana, pinOf(a, "Ana")).andExpect(status().isOk());
        UUID deviceA = deviceIdOf(phone);
        assertEquals(1L, (long) TenantContext.call(a, () -> jdbc.sql("SELECT count(*) FROM device_member_grant").query(Long.class).single()));
        TenantContext.call(b, () -> {
            assertEquals(0L, (long) jdbc.sql("SELECT count(*) FROM device_member_grant").query(Long.class).single(), "otro negocio no ve los permisos");
            assertEquals(0, jdbc.sql("UPDATE device_member_grant SET revoked_at = NULL").update());
            org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> jdbc.sql(
                            "INSERT INTO device_member_grant (id, device_id, member_id, business_id, granted_at, expires_at) VALUES (:id, :d, :m, :b, now(), now() + interval '1 hour')")
                    .param("id", UUID.randomUUID()).param("d", deviceA).param("m", ana).param("b", a).update());
            return null;
        });
        // Un teléfono de B no puede verificar a alguien de A.
        String phoneB = linkDevice(ownerB, b);
        assertCode(verify(phoneB, ana, pinOf(a, "Ana")).andExpect(status().isForbidden()), "MEMBER_NOT_ACTIVE");
        // Sin teléfono (sesión de usuario) no hay verificación de PIN.
        assertTrue(mvc.perform(post("/api/devices/me/members/" + ana + "/verify-pin").header("Authorization", bearer(ownerA))
                .contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"12345\"}")).andReturn().getResponse().getStatus() == 403);
    }

    private UUID memberByName(UUID b, String name) {
        return baseJdbc.sql("SELECT id FROM member WHERE business_id = :b AND display_name = :n").param("b", b).param("n", name).query(UUID.class).single();
    }
}
