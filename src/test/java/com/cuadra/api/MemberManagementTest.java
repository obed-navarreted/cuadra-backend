package com.cuadra.api;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Quién gestiona a quién: el dueño a todos; el admin a todos menos al dueño; el cajero solo a sí mismo (su propio PIN). */
class MemberManagementTest extends ApiTestBase {
    private static final String PIN = "{\"pin\":\"9876\"}";

    @Test
    void theOwnerManagesEveryone() throws Exception {
        String owner = login("mm-a");
        UUID b = createBusiness(owner, "Gestión A");
        UUID cashier = createPinMember(owner, b, "Caja", "CASHIER");
        UUID admin = createPinMember(owner, b, "Admin", "ADMIN");
        call(put("/api/b/" + b + "/members/" + cashier + "/pin"), bearer(owner), PIN).andExpect(status().isNoContent());
        call(put("/api/b/" + b + "/members/" + admin + "/pin"), bearer(owner), PIN).andExpect(status().isNoContent());
        call(put("/api/b/" + b + "/members/" + admin), bearer(owner), "{\"displayName\":\"Admin 2\",\"status\":\"DISABLED\"}").andExpect(status().isOk()).andExpect(jsonPath("$.status", is("DISABLED")));
        call(put("/api/b/" + b + "/members/" + cashier), bearer(owner), "{\"role\":\"ADMIN\"}").andExpect(status().isOk()).andExpect(jsonPath("$.role", is("ADMIN")));
    }

    @Test
    void anAdminManagesCashiersAndOtherAdminsButNeverTheOwner() throws Exception {
        String owner = login("mm-b");
        UUID b = createBusiness(owner, "Gestión B");
        String admin = joinAs(owner, b, "mm-b2", "ADMIN");
        UUID ownerMember = memberIdOf(owner, b);
        UUID cashier = createPinMember(owner, b, "Caja", "CASHIER");
        UUID otherAdmin = createPinMember(owner, b, "Otro admin", "ADMIN");
        call(put("/api/b/" + b + "/members/" + cashier + "/pin"), bearer(admin), PIN).andExpect(status().isNoContent());
        call(put("/api/b/" + b + "/members/" + otherAdmin + "/pin"), bearer(admin), PIN).andExpect(status().isNoContent());
        call(put("/api/b/" + b + "/members/" + otherAdmin), bearer(admin), "{\"displayName\":\"Renombrado\"}").andExpect(status().isOk()).andExpect(jsonPath("$.displayName", is("Renombrado")));
        call(put("/api/b/" + b + "/members/" + otherAdmin), bearer(admin), "{\"status\":\"DISABLED\"}").andExpect(status().isOk());
        // Al dueño no: ni sus datos, ni su rol, ni su estado, ni su PIN.
        assertCode(call(put("/api/b/" + b + "/members/" + ownerMember), bearer(admin), "{\"displayName\":\"Yo mando\"}").andExpect(status().isForbidden()), "CANNOT_MODIFY_OWNER");
        assertCode(call(put("/api/b/" + b + "/members/" + ownerMember), bearer(admin), "{\"role\":\"CASHIER\"}").andExpect(status().isForbidden()), "CANNOT_MODIFY_OWNER");
        assertCode(call(put("/api/b/" + b + "/members/" + ownerMember + "/pin"), bearer(admin), PIN).andExpect(status().isForbidden()), "CANNOT_MODIFY_OWNER");
        // Ni convertirse en dueño por la puerta de atrás.
        call(put("/api/b/" + b + "/members/" + cashier), bearer(admin), "{\"role\":\"OWNER\"}").andExpect(status().isBadRequest());
        call(post("/api/b/" + b + "/members"), bearer(admin), "{\"displayName\":\"Otro dueño\",\"role\":\"OWNER\",\"pin\":\"1234\"}").andExpect(status().isBadRequest());
    }

    @Test
    void aCashierOnlyChangesTheirOwnPin() throws Exception {
        String owner = login("mm-c");
        UUID b = createBusiness(owner, "Gestión C");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        UUID lucia = createPinMember(owner, b, "Lucía", "CASHIER");
        UUID ownerMember = memberIdOf(owner, b);
        String device = linkDevice(owner, b);
        // El suyo, sí.
        asDevice(put("/api/b/" + b + "/members/" + kevin + "/pin"), device, kevin, PIN).andExpect(status().isNoContent());
        // El de otro cajero, del admin o del dueño, no.
        asDevice(put("/api/b/" + b + "/members/" + lucia + "/pin"), device, kevin, PIN).andExpect(status().isForbidden());
        asDevice(put("/api/b/" + b + "/members/" + ownerMember + "/pin"), device, kevin, PIN).andExpect(status().isForbidden());
        // Tampoco crea gente, ni edita a otros, ni se cambia el rol o el estado.
        asDevice(post("/api/b/" + b + "/members"), device, kevin, "{\"displayName\":\"Nuevo\",\"role\":\"CASHIER\",\"pin\":\"1234\"}").andExpect(status().isForbidden());
        asDevice(put("/api/b/" + b + "/members/" + lucia), device, kevin, "{\"displayName\":\"Hackeada\"}").andExpect(status().isForbidden());
        assertCode(asDevice(put("/api/b/" + b + "/members/" + kevin), device, kevin, "{\"role\":\"ADMIN\"}").andExpect(status().isForbidden()), "CANNOT_MODIFY_SELF");
        // Sí puede cambiar su propio nombre.
        asDevice(put("/api/b/" + b + "/members/" + kevin), device, kevin, "{\"displayName\":\"Kevin R.\"}").andExpect(status().isOk());
    }
}
