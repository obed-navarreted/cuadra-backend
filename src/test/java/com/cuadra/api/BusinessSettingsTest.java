package com.cuadra.api;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class BusinessSettingsTest extends ApiTestBase {
    @Test
    void anOptionalSettingCanBeClearedWithAnExplicitFlagAndAnAbsentValueMeansNoChange() throws Exception {
        String owner = login("bset");
        UUID b = createBusiness(owner, "Ajustes");
        String url = "/api/b/" + b;
        call(put(url), bearer(owner), "{\"shiftNoteThresholdMinor\":5000,\"creditDefaultDueDays\":15}").andExpect(status().isOk())
                .andExpect(jsonPath("$.shiftNoteThresholdMinor", is(5000))).andExpect(jsonPath("$.creditDefaultDueDays", is(15)));
        // Un campo ausente no cambia nada (la actualización es parcial).
        call(put(url), bearer(owner), "{\"name\":\"Otro nombre\"}").andExpect(jsonPath("$.shiftNoteThresholdMinor", is(5000))).andExpect(jsonPath("$.creditDefaultDueDays", is(15)));
        // Para dejarlos SIN valor se pide explícitamente.
        call(put(url), bearer(owner), "{\"clearShiftNoteThreshold\":true}").andExpect(status().isOk()).andExpect(jsonPath("$.shiftNoteThresholdMinor", nullValue())).andExpect(jsonPath("$.creditDefaultDueDays", is(15)));
        call(put(url), bearer(owner), "{\"clearCreditDefaultDueDays\":true}").andExpect(jsonPath("$.creditDefaultDueDays", nullValue()));
        call(get(url), bearer(owner), null).andExpect(jsonPath("$.name", is("Otro nombre")));
        // Pedir vaciar y a la vez poner un valor: gana el valor.
        call(put(url), bearer(owner), "{\"clearShiftNoteThreshold\":true,\"shiftNoteThresholdMinor\":700}").andExpect(jsonPath("$.shiftNoteThresholdMinor", is(700)));
    }

    @Test
    void anAdminEditsTheBusinessSettingsButNeitherDeletesItNorTouchesTheAccessCode() throws Exception {
        String owner = login("bset-a");
        UUID b = createBusiness(owner, "Ajustes admin");
        String url = "/api/b/" + b;
        String admin = joinAs(owner, b, "bset-a2", "ADMIN");
        String cashier = joinAs(owner, b, "bset-a3", "CASHIER");
        call(put(url), bearer(admin), "{\"name\":\"Nuevo nombre\",\"registerCheckout\":true}").andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Nuevo nombre"))).andExpect(jsonPath("$.registerCheckout", is(true)));
        call(put(url), bearer(admin), "{\"registerCheckout\":false}").andExpect(status().isOk()).andExpect(jsonPath("$.registerCheckout", is(false)));
        call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(url), bearer(admin), null).andExpect(status().isForbidden());
        call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url + "/access-code"), bearer(admin), null).andExpect(status().isForbidden());
        call(put(url + "/access-code"), bearer(admin), "{\"accessCode\":\"88888\"}").andExpect(status().isForbidden());
        call(put(url), bearer(cashier), "{\"name\":\"Intruso\"}").andExpect(status().isForbidden());
        call(get(url), bearer(owner), null).andExpect(jsonPath("$.name", is("Nuevo nombre")));
    }
}
