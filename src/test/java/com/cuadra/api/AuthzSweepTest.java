package com.cuadra.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Barrido de autorización sobre TODAS las rutas de la API (se descubren solas, así una ruta nueva queda cubierta sin escribir nada):
 * sin credenciales → 401; con credenciales de OTRO negocio (persona o teléfono) → nunca 2xx ni 5xx; la consola de plataforma para un usuario común → 404.
 */
class AuthzSweepTest extends ApiTestBase {
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

    private record Route(HttpMethod method, String pattern) {}

    private static final List<String> PUBLIC = List.of("POST /api/auth/google", "POST /api/auth/platform", "POST /api/auth/member-login", "GET /api/config", "GET /api/config/countries", "POST /api/devices/link-requests", "GET /api/devices/link-requests/{code}");

    private List<Route> routes(String prefix) {
        List<Route> out = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            if (e.getKey().getPathPatternsCondition() == null) continue;
            for (var p : e.getKey().getPathPatternsCondition().getPatterns()) {
                if (!p.getPatternString().startsWith(prefix)) continue;
                for (var m : e.getKey().getMethodsCondition().getMethods()) out.add(new Route(HttpMethod.valueOf(m.name()), p.getPatternString()));
            }
        }
        return out;
    }

    private MockHttpServletRequestBuilder build(Route r, UUID business) {
        String path = r.pattern().replace("{businessId}", business.toString()).replaceAll("\\{[^}/]+}", UUID.randomUUID().toString());
        MockHttpServletRequestBuilder b = MockMvcRequestBuilders.request(r.method(), path);
        if (r.method() != HttpMethod.GET && r.method() != HttpMethod.DELETE) b.contentType(MediaType.APPLICATION_JSON).content("{}");
        return b;
    }

    @Test
    void routesAreDiscovered() {
        assertTrue(routes("/api/b/{businessId}").size() > 100, "se esperaban más de 100 rutas de negocio, hay " + routes("/api/b/{businessId}").size());
        assertTrue(routes("/api/platform").size() > 15);
    }

    @Test
    void everyBusinessRouteRejectsAnonymousCallers() throws Exception {
        UUID some = UUID.randomUUID();
        List<String> bad = new ArrayList<>();
        for (Route r : routes("/api/b/{businessId}")) {
            int st = mvc.perform(build(r, some)).andReturn().getResponse().getStatus();
            if (st != 401) bad.add(r.method() + " " + r.pattern() + " → " + st);
        }
        assertEquals(List.of(), bad);
    }

    @Test
    void everyBusinessRouteRefusesAnotherBusinessesPeopleAndPhones() throws Exception {
        String ownerA = login("sweep-a");
        String ownerB = login("sweep-b");
        UUID a = createBusiness(ownerA, "Barrido A");
        UUID b = createBusiness(ownerB, "Barrido B");
        String deviceA = linkDevice(ownerA, a);
        UUID memberA = memberIdOf(ownerA, a);
        List<String> bad = new ArrayList<>();
        for (Route r : routes("/api/b/{businessId}")) {
            int person = mvc.perform(build(r, b).header("Authorization", bearer(ownerA))).andReturn().getResponse().getStatus();
            int phone = mvc.perform(build(r, b).header("Authorization", "Device " + deviceA).header("X-Member-Id", memberA.toString())).andReturn().getResponse().getStatus();
            for (int st : new int[] {person, phone}) {
                if (st < 400 || st >= 500 || st == 401) bad.add(r.method() + " " + r.pattern() + " → " + person + " (persona) / " + phone + " (teléfono)");
            }
        }
        assertEquals(List.of(), bad.stream().distinct().toList());
    }

    /**
     * Elevación por PIN verificado (ADR 0012, 2026-10-01): en un teléfono vinculado por un cajero, el dueño SIN su PIN verificado en ese teléfono nunca puede
     * más que el cajero: donde al cajero se le niega (403), al dueño también (y con PIN_VERIFICATION_REQUIRED cuando su rol sí podría). Nunca DEVICE_NOT_TRUSTED.
     */
    @Test
    void onACashierPhoneAnUnverifiedOwnerNeverGetsMoreThanTheCashier() throws Exception {
        String owner = login("sweep-d");
        UUID b = createBusiness(owner, "Barrido D");
        UUID ownerMember = memberIdOf(owner, b);
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        String code = com.jayway.jsonpath.JsonPath.read(mvc.perform(MockMvcRequestBuilders.get("/api/b/" + b).header("Authorization", bearer(owner))).andReturn().getResponse().getContentAsString(), "$.accessCode");
        String phone = com.jayway.jsonpath.JsonPath.read(mvc.perform(MockMvcRequestBuilders.post("/api/auth/member-login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"businessCode\":\"" + code + "\",\"pin\":\"" + pinOf(b, "Kevin") + "\",\"deviceName\":\"Caja\"}")).andReturn().getResponse().getContentAsString(), "$.deviceToken");
        List<String> bad = new ArrayList<>();
        int pinRequired = 0;
        for (Route r : routes("/api/b/{businessId}")) {
            var asKevin = mvc.perform(build(r, b).header("Authorization", "Device " + phone).header("X-Member-Id", kevin.toString())).andReturn().getResponse();
            var asOwner = mvc.perform(build(r, b).header("Authorization", "Device " + phone).header("X-Member-Id", ownerMember.toString())).andReturn().getResponse();
            String body = asOwner.getContentAsString();
            if (body.contains("DEVICE_NOT_TRUSTED")) bad.add(r.method() + " " + r.pattern() + " → DEVICE_NOT_TRUSTED");
            if (asKevin.getStatus() == 403 && asOwner.getStatus() != 403) bad.add(r.method() + " " + r.pattern() + " → cajero 403, dueño sin verificar " + asOwner.getStatus());
            if (body.contains("PIN_VERIFICATION_REQUIRED")) pinRequired++;
        }
        assertEquals(List.of(), bad);
        assertTrue(pinRequired > 20, "se esperaba PIN_VERIFICATION_REQUIRED en las rutas de administración, hubo " + pinRequired);
    }

    @Test
    void thePlatformConsoleDoesNotExistForOrdinaryUsers() throws Exception {
        String user = login("sweep-c");
        UUID b = createBusiness(user, "Barrido C");
        List<String> bad = new ArrayList<>();
        for (Route r : routes("/api/platform")) {
            int anon = mvc.perform(build(r, b)).andReturn().getResponse().getStatus();
            int st = mvc.perform(build(r, b).header("Authorization", bearer(user))).andReturn().getResponse().getStatus();
            if (anon != 401 || st != 404) bad.add(r.method() + " " + r.pattern() + " → anónimo " + anon + ", usuario " + st);
        }
        assertEquals(List.of(), bad);
    }

    @Test
    void everyOtherRouteIsEitherKnownPublicOrNeedsCredentials() throws Exception {
        List<String> bad = new ArrayList<>();
        for (Route r : routes("/api/")) {
            if (r.pattern().startsWith("/api/b/{businessId}") || r.pattern().startsWith("/api/platform")) continue;
            if (PUBLIC.contains(r.method() + " " + r.pattern())) continue;
            int st = mvc.perform(build(r, UUID.randomUUID())).andReturn().getResponse().getStatus();
            if (st != 401) bad.add(r.method() + " " + r.pattern() + " → " + st);
        }
        assertEquals(List.of(), bad, "rutas públicas que no están en la lista permitida de la prueba");
    }
}
