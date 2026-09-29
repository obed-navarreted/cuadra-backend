package com.cuadra.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cuadra.api.common.ApiException;
import com.cuadra.api.security.GoogleIdentity;
import com.cuadra.api.security.GoogleTokenVerifier;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Flujo completo contra PostgreSQL real: identidad, negocios, equipo, invitaciones, teléfonos y aislamiento. */
class IdentityFlowTest extends ApiTestBase {

    // ---------- pruebas ----------

    @Test
    void protectedEndpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code", is("UNAUTHENTICATED")));
        mvc.perform(get("/api/me").header("Authorization", "Bearer nope")).andExpect(status().isUnauthorized());
    }

    @Test
    void invalidGoogleTokenIsRejected() throws Exception {
        mvc.perform(post("/api/auth/google").contentType(MediaType.APPLICATION_JSON).content("{\"idToken\":\"garbage\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code", is("INVALID_GOOGLE_TOKEN")));
    }

    @Test
    void ownerCreatesBusinessWithOnlyANameAndLandsInFreeSale() throws Exception {
        String owner = login("olga");
        String json = call(post("/api/businesses"), bearer(owner), "{\"name\":\"Quesería San Benito\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency", is("NIO")))
                .andExpect(jsonPath("$.timezone", is("America/Managua")))
                .andExpect(jsonPath("$.inventoryMode", is("OFF")))
                .andExpect(jsonPath("$.posViews[0]", is("TYPE")))
                .andExpect(jsonPath("$.modules.inventory", is(false)))
                .andExpect(jsonPath("$.modules.credit", is(true)))
                .andReturn().getResponse().getContentAsString();
        UUID businessId = UUID.fromString(JsonPath.read(json, "$.id"));

        call(get("/api/me"), bearer(owner), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.businesses", hasSize(1)))
                .andExpect(jsonPath("$.businesses[0].role", is("OWNER")))
                .andExpect(jsonPath("$.businesses[0].businessId", is(businessId.toString())));
    }

    @Test
    void enablingInventoryModuleSwitchesInventoryMode() throws Exception {
        String owner = login("ivan");
        UUID b = createBusiness(owner, "Tienda Iván");
        call(put("/api/b/" + b), bearer(owner), "{\"modules\":{\"inventory\":true},\"posViews\":[\"TYPE\",\"QUICK\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inventoryMode", is("PER_PRODUCT")))
                .andExpect(jsonPath("$.modules.inventory", is(true)))
                .andExpect(jsonPath("$.modules.credit", is(true)))
                .andExpect(jsonPath("$.posViews", hasSize(2)));
        call(put("/api/b/" + b), bearer(owner), "{\"modules\":{\"inventory\":false}}")
                .andExpect(jsonPath("$.inventoryMode", is("OFF")));
        call(put("/api/b/" + b), bearer(owner), "{\"modules\":{\"nope\":true}}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("INVALID_MODULE")));
    }

    @Test
    void anotherUserCannotSeeOrTouchABusinessTheyDoNotBelongTo() throws Exception {
        String owner = login("carla");
        String stranger = login("sergio");
        UUID b = createBusiness(owner, "Pulpería Carla");

        call(get("/api/b/" + b), bearer(stranger), null).andExpect(status().isNotFound());
        call(get("/api/b/" + b + "/members"), bearer(stranger), null).andExpect(status().isNotFound());
        call(put("/api/b/" + b), bearer(stranger), "{\"name\":\"hack\"}").andExpect(status().isNotFound());
        call(post("/api/b/" + b + "/invitations"), bearer(stranger), "{\"role\":\"CASHIER\"}").andExpect(status().isNotFound());
        call(get("/api/b/" + b + "/devices"), bearer(stranger), null).andExpect(status().isNotFound());
    }

    @Test
    void cashierWithPinCanBeCreatedAndListedWithPinHashOnlyForDevices() throws Exception {
        String owner = login("paula");
        UUID b = createBusiness(owner, "Pulpería Paula");

        String created = call(post("/api/b/" + b + "/members"), bearer(owner), "{\"displayName\":\"Kevin\",\"role\":\"CASHIER\",\"pin\":\"1234\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.pinSet", is(true)))
                .andExpect(jsonPath("$.pinHash", nullValue())).andReturn().getResponse().getContentAsString();
        UUID kevin = UUID.fromString(JsonPath.read(created, "$.id"));

        call(post("/api/b/" + b + "/members"), bearer(owner), "{\"displayName\":\"X\",\"role\":\"CASHIER\",\"pin\":\"12\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("INVALID_PIN")));

        // El dueño ve a los miembros sin hash; un teléfono vinculado sí lo recibe (validación offline).
        call(get("/api/b/" + b + "/members"), bearer(owner), null).andExpect(jsonPath("$[1].pinHash", nullValue()));

        String deviceToken = linkDevice(owner, b);
        call(get("/api/b/" + b + "/members"), "Device " + deviceToken, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[?(@.id=='" + kevin + "')].pinHash[0]", notNullValue()));

        // El teléfono actúa como Kevin (cajero): puede leer miembros pero no crear a otros.
        call(post("/api/b/" + b + "/members"), "Device " + deviceToken,
                "{\"displayName\":\"Otro\",\"role\":\"CASHIER\",\"pin\":\"5555\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("MEMBER_REQUIRED")));
        mvc.perform(post("/api/b/" + b + "/members").header("Authorization", "Device " + deviceToken)
                        .header("X-Member-Id", kevin.toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Otro\",\"role\":\"CASHIER\",\"pin\":\"5555\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code", is("FORBIDDEN")));
    }

    @Test
    void revokedDeviceLosesAccessImmediately() throws Exception {
        String owner = login("rosa");
        UUID b = createBusiness(owner, "Pulpería Rosa");
        String device = linkDevice(owner, b);
        call(get("/api/b/" + b + "/members"), "Device " + device, null).andExpect(status().isOk());

        String list = call(get("/api/b/" + b + "/devices"), bearer(owner), null).andExpect(jsonPath("$", hasSize(1)))
                .andReturn().getResponse().getContentAsString();
        String deviceId = JsonPath.read(list, "$[0].id");
        call(delete("/api/b/" + b + "/devices/" + deviceId), bearer(owner), null).andExpect(status().isNoContent());

        call(get("/api/b/" + b + "/members"), "Device " + device, null).andExpect(status().isUnauthorized());
    }

    @Test
    void deviceCannotReachAnotherBusiness() throws Exception {
        String ownerA = login("ana");
        String ownerB = login("beto");
        UUID a = createBusiness(ownerA, "Negocio A");
        UUID b = createBusiness(ownerB, "Negocio B");
        String deviceA = linkDevice(ownerA, a);
        call(get("/api/b/" + b + "/members"), "Device " + deviceA, null).andExpect(status().isNotFound());
        call(get("/api/b/" + b), "Device " + deviceA, null).andExpect(status().isNotFound());
    }

    @Test
    void invitationLetsAGoogleUserJoinAsAdminButAdminCannotInviteAdmins() throws Exception {
        String owner = login("oscar");
        String guest = login("gina");
        UUID b = createBusiness(owner, "Tienda Oscar");

        String inv = call(post("/api/b/" + b + "/invitations"), bearer(owner), "{\"role\":\"ADMIN\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.url", notNullValue())).andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(inv, "$.code");

        // Vista previa pública: solo nombre del negocio y rol.
        mvc.perform(get("/api/invitations/" + code)).andExpect(status().isOk())
                .andExpect(jsonPath("$.businessName", is("Tienda Oscar"))).andExpect(jsonPath("$.role", is("ADMIN")))
                .andExpect(jsonPath("$.valid", is(true)));

        call(post("/api/invitations/" + code + "/accept"), bearer(guest), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.businessId", is(b.toString())));
        // Un solo uso: ya no vale para otra persona ni la misma.
        call(post("/api/invitations/" + code + "/accept"), bearer(guest), null).andExpect(status().isConflict());
        call(post("/api/invitations/" + code + "/accept"), bearer(login("hugo")), null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("INVITATION_EXHAUSTED")));

        call(get("/api/me"), bearer(guest), null).andExpect(jsonPath("$.businesses[0].role", is("ADMIN")));

        // El admin gestiona cajeros e invita cajeros, pero no admins.
        call(post("/api/b/" + b + "/invitations"), bearer(guest), "{\"role\":\"CASHIER\"}").andExpect(status().isCreated());
        call(post("/api/b/" + b + "/invitations"), bearer(guest), "{\"role\":\"ADMIN\"}").andExpect(status().isForbidden());
        call(post("/api/b/" + b + "/members"), bearer(guest), "{\"displayName\":\"Caj\",\"role\":\"CASHIER\",\"pin\":\"4321\"}")
                .andExpect(status().isCreated());
        call(post("/api/b/" + b + "/members"), bearer(guest), "{\"displayName\":\"Adm\",\"role\":\"ADMIN\",\"pin\":\"4321\"}")
                .andExpect(status().isForbidden());
        // ...ni edita ajustes del negocio.
        call(put("/api/b/" + b), bearer(guest), "{\"name\":\"Mío ahora\"}").andExpect(status().isForbidden());
    }

    @Test
    void adminCannotModifyTheOwnerNorThemselves() throws Exception {
        String owner = login("dora");
        String admin = login("adan");
        UUID b = createBusiness(owner, "Tienda Dora");
        String inv = call(post("/api/b/" + b + "/invitations"), bearer(owner), "{\"role\":\"ADMIN\"}").andReturn().getResponse().getContentAsString();
        call(post("/api/invitations/" + JsonPath.read(inv, "$.code") + "/accept"), bearer(admin), null).andExpect(status().isOk());

        UUID ownerMember = memberIdOf(owner, b);
        UUID adminMember = memberIdOf(admin, b);
        call(put("/api/b/" + b + "/members/" + ownerMember), bearer(admin), "{\"status\":\"DISABLED\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code", is("CANNOT_MODIFY_OWNER")));
        call(put("/api/b/" + b + "/members/" + adminMember), bearer(admin), "{\"role\":\"CASHIER\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code", is("CANNOT_MODIFY_SELF")));
        call(put("/api/b/" + b + "/members/" + ownerMember + "/pin"), bearer(admin), "{\"pin\":\"9999\"}").andExpect(status().isForbidden());
    }

    @Test
    void ownershipTransferKeepsExactlyOneOwner() throws Exception {
        String owner = login("elena");
        String heir = login("felix");
        UUID b = createBusiness(owner, "Tienda Elena");
        String inv = call(post("/api/b/" + b + "/invitations"), bearer(owner), "{\"role\":\"ADMIN\"}").andReturn().getResponse().getContentAsString();
        call(post("/api/invitations/" + JsonPath.read(inv, "$.code") + "/accept"), bearer(heir), null);
        UUID heirMember = memberIdOf(heir, b);

        // Un miembro solo con PIN no puede heredar: el dueño exige cuenta de Google.
        String pinMember = call(post("/api/b/" + b + "/members"), bearer(owner), "{\"displayName\":\"Solo PIN\",\"role\":\"CASHIER\",\"pin\":\"1111\"}")
                .andReturn().getResponse().getContentAsString();
        call(post("/api/b/" + b + "/owner-transfer"), bearer(owner), "{\"memberId\":\"" + JsonPath.read(pinMember, "$.id") + "\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("GOOGLE_REQUIRED")));

        call(post("/api/b/" + b + "/owner-transfer"), bearer(heir), "{\"memberId\":\"" + heirMember + "\"}").andExpect(status().isForbidden());
        call(post("/api/b/" + b + "/owner-transfer"), bearer(owner), "{\"memberId\":\"" + heirMember + "\"}").andExpect(status().isNoContent());

        call(get("/api/me"), bearer(owner), null).andExpect(jsonPath("$.businesses[0].role", is("ADMIN")));
        call(get("/api/me"), bearer(heir), null).andExpect(jsonPath("$.businesses[0].role", is("OWNER")));
    }

    @Test
    void ownerCannotDeleteAccountUntilBusinessIsGone() throws Exception {
        String owner = login("mario");
        UUID b = createBusiness(owner, "Tienda Mario");
        call(delete("/api/me"), bearer(owner), null).andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("OWNS_BUSINESSES")));

        call(delete("/api/b/" + b), bearer(owner), null).andExpect(status().isAccepted());
        call(get("/api/me"), bearer(owner), null).andExpect(jsonPath("$.businesses", hasSize(0)));
        call(delete("/api/me"), bearer(owner), null).andExpect(status().isNoContent());
        call(get("/api/me"), bearer(owner), null).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesTheSession() throws Exception {
        String token = login("lola");
        call(post("/api/auth/logout"), bearer(token), null).andExpect(status().isNoContent());
        call(get("/api/me"), bearer(token), null).andExpect(status().isUnauthorized());
    }

    @Test
    void supportTicketIsSavedAndRateLimited() throws Exception {
        String owner = login("sofia");
        UUID b = createBusiness(owner, "Tienda Sofía");
        String body = "{\"category\":\"PROBLEM\",\"message\":\"No me deja cerrar la caja del turno\",\"businessId\":\"" + b + "\","
                + "\"diagnostics\":\"app 0.1.0 · Android 14\",\"locale\":\"es\"}";
        for (int i = 0; i < 5; i++) {
            call(post("/api/support/tickets"), bearer(owner), body).andExpect(status().isCreated())
                    .andExpect(jsonPath("$.reference", notNullValue()));
        }
        call(post("/api/support/tickets"), bearer(owner), body).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code", is("TOO_MANY_TICKETS")));
        call(post("/api/support/tickets"), bearer(login("otra")), "{\"message\":\"corto\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
    }

    @Test
    void publicConfigIsAvailableWithoutLogin() throws Exception {
        mvc.perform(get("/api/config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.supportEmail", is("ndiazobed@gmail.com")))
                .andExpect(jsonPath("$.donationMode", is("external_link")))
                .andExpect(jsonPath("$.donationUrl", org.hamcrest.Matchers.startsWith("https://www.paypal.com/donate/")));
    }

    @Test
    void ownerLinksTheirOwnPhoneWithoutACodeAndAnAdminOnlyWithGoogle() throws Exception {
        String owner = login("selfa");
        UUID b = createBusiness(owner, "Tienda Self");
        String json = call(post("/api/b/" + b + "/devices/self"), bearer(owner), "{\"deviceName\":\"Mi teléfono\",\"model\":\"Pixel\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.deviceToken", notNullValue())).andExpect(jsonPath("$.cashRegisterId", notNullValue()))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(json, "$.deviceToken");
        // El token recién emitido ya funciona; y el negocio lo lista.
        call(get("/api/b/" + b + "/members"), "Device " + token, null).andExpect(status().isOk());
        call(get("/api/b/" + b + "/devices"), bearer(owner), null).andExpect(jsonPath("$", hasSize(1)));
        // Un cajero (aunque tenga Google) no puede vincular teléfonos; un teléfono no vincula otros sin código.
        String cashier = joinAs(owner, b, "selfb", "CASHIER");
        call(post("/api/b/" + b + "/devices/self"), bearer(cashier), "{\"deviceName\":\"X\"}").andExpect(status().isForbidden());
        mvc.perform(post("/api/b/" + b + "/devices/self").header("Authorization", "Device " + token).header("X-Member-Id", memberIdOf(owner, b).toString())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"deviceName\":\"Y\"}")).andExpect(status().isForbidden());
    }
}
