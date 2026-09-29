package com.cuadra.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cuadra.api.platform.AnnouncementService;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Consola de la plataforma: quién entra, qué puede hacer, y que cada cosa deje rastro (también para el dueño del negocio). */
class PlatformTest extends ApiTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired AnnouncementService announcementService;

    private String admin() throws Exception {
        String json = mvc.perform(post("/api/auth/google").contentType(MediaType.APPLICATION_JSON).content("{\"idToken\":\"root-sub|ndiazobed@gmail.com|Root\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.token");
    }

    private String b(UUID id) { return "/api/b/" + id; }

    private static final String WHY = "{\"reason\":\"caso de soporte 123\"}";

    // ---------- acceso ----------

    @Test
    void onlyAPlatformAdminWithAFreshSessionSeesTheConsole() throws Exception {
        String user = login("plt-a");
        call(get("/api/platform/metrics"), bearer(user), null).andExpect(status().isNotFound());
        // Marcado como admin pero con un correo que no está en la lista permitida: tampoco.
        jdbc.sql("UPDATE user_account SET is_platform_admin = true WHERE email = 'plt-a@test.com'").update();
        call(get("/api/platform/metrics"), bearer(user), null).andExpect(status().isNotFound());
        String root = admin();
        call(get("/api/platform/metrics"), bearer(root), null).andExpect(status().isOk()).andExpect(jsonPath("$.businesses", greaterThanOrEqualTo(0)));
        // Una sesión de más de 12 horas ya no vale para la consola: hay que volver a entrar con Google.
        jdbc.sql("UPDATE auth_session SET issued_at = now() - interval '13 hours' WHERE user_account_id = (SELECT id FROM user_account WHERE email = 'ndiazobed@gmail.com')").update();
        call(get("/api/platform/metrics"), bearer(root), null).andExpect(status().isNotFound());
        // Un teléfono vinculado no es una persona con Google.
        mvc.perform(get("/api/platform/metrics")).andExpect(status().isUnauthorized());
    }

    // ---------- suspensión ----------

    @Test
    void aSuspendedBusinessStopsOperatingForPeopleAndPhonesAndResumesWhenLifted() throws Exception {
        String owner = login("plt-b");
        UUID biz = createBusiness(owner, "Suspendible");
        String device = linkDevice(owner, biz);
        UUID ownerMember = memberIdOf(owner, biz);
        String root = admin();
        call(post("/api/platform/businesses/" + biz + "/suspend"), bearer(root), "{\"reason\":\"x\"}").andExpect(status().isBadRequest());
        call(post("/api/platform/businesses/" + biz + "/suspend"), bearer(root), WHY).andExpect(status().isOk()).andExpect(jsonPath("$.status", is("SUSPENDED")));
        assertCode(call(get(b(biz) + "/plan"), bearer(owner), null).andExpect(status().isForbidden()), "BUSINESS_SUSPENDED");
        assertCode(asDevice(get(b(biz) + "/plan"), device, ownerMember, null).andExpect(status().isForbidden()), "BUSINESS_SUSPENDED");
        call(post("/api/platform/businesses/" + biz + "/unsuspend"), bearer(root), WHY).andExpect(status().isOk()).andExpect(jsonPath("$.status", is("ACTIVE")));
        call(get(b(biz) + "/plan"), bearer(owner), null).andExpect(status().isOk());
        asDevice(get(b(biz) + "/plan"), device, ownerMember, null).andExpect(status().isOk());
    }

    // ---------- "Ver como" ----------

    @Test
    void viewAsIsReadOnlyLimitedToOneBusinessLoggedAndVisibleToTheOwner() throws Exception {
        String owner = login("plt-c");
        UUID biz = createBusiness(owner, "Mirado");
        UUID other = createBusiness(login("plt-c2"), "Otro");
        UUID cashier = createPinMember(owner, biz, "Caja", "CASHIER");
        String root = admin();
        call(post("/api/platform/businesses/" + biz + "/view-as"), bearer(root), "{}").andExpect(status().isBadRequest());
        String res = call(post("/api/platform/businesses/" + biz + "/view-as"), bearer(root), WHY).andExpect(status().isOk()).andExpect(jsonPath("$.token", notNullValue()))
                .andReturn().getResponse().getContentAsString();
        String viewer = JsonPath.read(res, "$.token");
        // Lee el negocio mirado, con el dueño como contexto…
        call(get(b(biz) + "/plan"), bearer(viewer), null).andExpect(status().isOk());
        call(get("/api/me"), bearer(viewer), null).andExpect(status().isOk()).andExpect(jsonPath("$.platformAdmin", is(false)))
                .andExpect(jsonPath("$.viewAsBusinessId", is(biz.toString()))).andExpect(jsonPath("$.businesses", hasSize(1))).andExpect(jsonPath("$.businesses[0].role", is("OWNER")));
        // …pero no escribe, ni mira otro negocio, ni entra a la consola.
        assertCode(call(post(b(biz) + "/members"), bearer(viewer), "{\"displayName\":\"X\",\"role\":\"CASHIER\",\"pin\":\"1234\"}").andExpect(status().isForbidden()), "VIEW_AS_READ_ONLY");
        assertCode(call(put("/api/me"), bearer(viewer), "{\"fullName\":\"Hack\"}").andExpect(status().isForbidden()), "VIEW_AS_READ_ONLY");
        call(get(b(other) + "/plan"), bearer(viewer), null).andExpect(status().isNotFound());
        call(get("/api/platform/metrics"), bearer(viewer), null).andExpect(status().isNotFound());
        // Caduca a los 30 minutos.
        jdbc.sql("UPDATE auth_session SET expires_at = now() - interval '1 minute' WHERE kind = 'VIEW_AS' AND view_as_business_id = :b").param("b", biz).update();
        call(get(b(biz) + "/plan"), bearer(viewer), null).andExpect(status().isUnauthorized());
        // El dueño lo ve en su actividad, con el motivo; el resto del equipo no la ve.
        call(get(b(biz) + "/activity"), bearer(owner), null).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].action", is("platform.view_as")))
                .andExpect(jsonPath("$.items[0].byPlatform", is(true))).andExpect(jsonPath("$.items[0].detail", containsString("caso de soporte 123")));
        String cashierToken = joinAs(owner, biz, "plt-c3", "CASHIER");
        call(get(b(biz) + "/activity"), bearer(cashierToken), null).andExpect(status().isForbidden());
        // Y en la auditoría de la plataforma.
        call(get("/api/platform/audit"), bearer(root), null).andExpect(jsonPath("$.items[?(@.action=='platform.view_as')]", hasSize(greaterThanOrEqualTo(1))));
    }

    // ---------- planes a mano ----------

    @Test
    void theConsoleChangesPlansAndExtendsTrialsWithAReason() throws Exception {
        String owner = login("plt-d");
        UUID biz = createBusiness(owner, "Planes");
        String root = admin();
        call(put("/api/platform/businesses/" + biz + "/plan"), bearer(root), "{\"plan\":\"FREE\",\"status\":\"MANUAL\",\"reason\":\"no\"}").andExpect(status().isBadRequest());
        call(put("/api/platform/businesses/" + biz + "/plan"), bearer(root), "{\"plan\":\"GOLD\",\"reason\":\"prueba de plan\"}").andExpect(status().isBadRequest());
        call(put("/api/platform/businesses/" + biz + "/plan"), bearer(root), "{\"plan\":\"FREE\",\"status\":\"MANUAL\",\"reason\":\"bajó de plan\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.effectivePlan", is("FREE")));
        call(get(b(biz) + "/plan"), bearer(owner), null).andExpect(jsonPath("$.plan", is("FREE")));
        call(post("/api/platform/businesses/" + biz + "/extend-trial"), bearer(root), "{\"days\":15,\"reason\":\"pidió más tiempo\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.effectivePlan", is("PRO"))).andExpect(jsonPath("$.planStatus", is("TRIALING")));
        call(get(b(biz) + "/plan"), bearer(owner), null).andExpect(jsonPath("$.trialDaysLeft", greaterThanOrEqualTo(43)));
        call(post("/api/platform/businesses/" + biz + "/extend-trial"), bearer(root), "{\"days\":0,\"reason\":\"pidió más tiempo\"}").andExpect(status().isBadRequest());
        // El dueño ve el cambio en su actividad.
        call(get(b(biz) + "/activity"), bearer(owner), null).andExpect(jsonPath("$.items[?(@.action=='platform.trial_extended')]", hasSize(1)));
    }

    // ---------- negocios, usuarios, tickets, teléfonos ----------

    @Test
    void searchAndLookups() throws Exception {
        String owner = login("plt-e");
        UUID biz = createBusiness(owner, "Pulpería Zafiro");
        String root = admin();
        call(get("/api/platform/businesses?q=zafiro"), bearer(root), null).andExpect(jsonPath("$.items", hasSize(1))).andExpect(jsonPath("$.items[0].id", is(biz.toString())))
                .andExpect(jsonPath("$.items[0].effectivePlan", is("PRO")));
        call(get("/api/platform/businesses?q=plt-e@test"), bearer(root), null).andExpect(jsonPath("$.items", hasSize(1)));
        call(get("/api/platform/businesses?q=zafiro&plan=FREE"), bearer(root), null).andExpect(jsonPath("$.items", hasSize(0)));
        call(get("/api/platform/businesses/" + biz), bearer(root), null).andExpect(jsonPath("$.owners[0].email", is("plt-e@test.com"))).andExpect(jsonPath("$.members", is(1)));
        call(get("/api/platform/users?q=plt-e@"), bearer(root), null).andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].businesses[0]", containsString("Pulpería Zafiro")));
        call(get("/api/platform/users"), bearer(root), null).andExpect(jsonPath("$", hasSize(0)));
        String device = linkDevice(owner, biz);
        jdbc.sql("UPDATE device SET last_sync_at = now() - interval '10 days', app_version = '1.0.0' WHERE business_id = :b").param("b", biz).update();
        call(get("/api/platform/devices?staleDays=7"), bearer(root), null).andExpect(jsonPath("$[?(@.businessId=='" + biz + "')]", hasSize(1)));
        call(get("/api/platform/devices?belowVersion=1.2"), bearer(root), null).andExpect(jsonPath("$[?(@.businessId=='" + biz + "')]", hasSize(1)));
        call(get("/api/platform/devices?belowVersion=1.0.0"), bearer(root), null).andExpect(jsonPath("$[?(@.businessId=='" + biz + "')]", hasSize(0)));
        call(post("/api/support/tickets"), bearer(owner), "{\"category\":\"PROBLEM\",\"message\":\"No me sincroniza el teléfono\",\"businessId\":\"" + biz + "\"}").andExpect(status().isCreated());
        String tickets = call(get("/api/platform/tickets?status=NEW&size=100"), bearer(root), null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String ticketId = JsonPath.<java.util.List<String>>read(tickets, "$.items[?(@.businessId=='" + biz + "')].id").get(0);
        call(put("/api/platform/tickets/" + ticketId + "/status"), bearer(root), "{\"status\":\"ANSWERED\"}").andExpect(status().isOk());
        call(put("/api/platform/tickets/" + ticketId + "/status"), bearer(root), "{\"status\":\"WHATEVER\"}").andExpect(status().isBadRequest());
        assertEquals("ANSWERED", jdbc.sql("SELECT status FROM support_ticket WHERE id = :i").param("i", UUID.fromString(ticketId)).query(String.class).single());
    }

    @Test
    void metricsCountBusinessesActivityAndDistributions() throws Exception {
        String owner = login("plt-f");
        createBusiness(owner, "Métrica");
        String root = admin();
        call(get("/api/platform/metrics"), bearer(root), null).andExpect(status().isOk()).andExpect(jsonPath("$.createdLast7", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.byCountry[?(@.key=='NI')]", hasSize(1))).andExpect(jsonPath("$.byPlan[?(@.key=='PRO:TRIALING')]", hasSize(1)))
                .andExpect(jsonPath("$.moduleUsage[?(@.key=='credit')]", hasSize(1))).andExpect(jsonPath("$.retention", notNullValue()));
    }

    // ---------- configuración remota ----------

    @Test
    void remoteConfigIsEditableOnlyForAllowedKeysWithValidValues() throws Exception {
        String root = admin();
        call(put("/api/platform/config"), bearer(root), "{\"key\":\"jwt_secret\",\"value\":\"x\",\"reason\":\"prueba mala\"}").andExpect(status().isBadRequest());
        call(put("/api/platform/config"), bearer(root), "{\"key\":\"min_app_version\",\"value\":\"abc\",\"reason\":\"prueba mala\"}").andExpect(status().isBadRequest());
        call(put("/api/platform/config"), bearer(root), "{\"key\":\"donation_url\",\"value\":\"javascript:alert(1)\",\"reason\":\"prueba mala\"}").andExpect(status().isBadRequest());
        call(put("/api/platform/config"), bearer(root), "{\"key\":\"min_app_version\",\"value\":\"1.4.0\",\"reason\":\"corrige un error\"}").andExpect(status().isOk());
        call(put("/api/platform/config"), bearer(root), "{\"key\":\"recommended_app_version\",\"value\":\"1.5.0\",\"reason\":\"nueva versión\"}").andExpect(status().isOk());
        mvc.perform(get("/api/config")).andExpect(status().isOk()).andExpect(jsonPath("$.minAppVersion", is("1.4.0"))).andExpect(jsonPath("$.recommendedAppVersion", is("1.5.0")));
        call(put("/api/platform/config"), bearer(root), "{\"key\":\"min_app_version\",\"value\":\"\",\"reason\":\"quitar exigencia\"}").andExpect(status().isOk());
        call(put("/api/platform/config"), bearer(root), "{\"key\":\"recommended_app_version\",\"value\":\"\",\"reason\":\"quitar aviso\"}").andExpect(status().isOk());
        mvc.perform(get("/api/config")).andExpect(jsonPath("$.recommendedAppVersion", is(nullValue())));
    }

    // ---------- anuncios ----------

    private String announce(String root, String json) throws Exception {
        return call(post("/api/platform/announcements"), bearer(root), json).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private int inboxCount(String token, UUID biz, String type) throws Exception {
        String json = call(get(b(biz) + "/notifications?size=100"), bearer(token), null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.<java.util.List<Object>>read(json, "$.items[?(@.type=='" + type + "')]").size();
    }

    @Test
    void announcementsReachTheRightPeopleNowOrOnSchedule() throws Exception {
        String owner = login("plt-g");
        UUID biz = createBusiness(owner, "Anuncios");
        String admin = joinAs(owner, biz, "plt-g2", "ADMIN");
        String cashier = joinAs(owner, biz, "plt-g3", "CASHIER");
        String root = admin();
        var mine = "\"segment\":{\"businessIds\":[\"" + biz + "\"]}";

        // Solo dueños.
        String a1 = announce(root, "{\"title\":\"Novedad\",\"body\":\"Ya puedes exportar\",\"audience\":\"OWNERS\"," + mine + "}");
        assertEquals(1, (int) JsonPath.read(a1, "$.recipients"));
        assertEquals("SENT", JsonPath.read(a1, "$.state"));
        assertEquals(1, inboxCount(owner, biz, "PLATFORM_ANNOUNCEMENT"));
        assertEquals(0, inboxCount(admin, biz, "PLATFORM_ANNOUNCEMENT"));
        // Dueños y admins.
        announce(root, "{\"title\":\"Mantenimiento\",\"body\":\"Domingo 2am\",\"audience\":\"OWNERS_ADMINS\"," + mine + "}");
        assertEquals(1, inboxCount(admin, biz, "PLATFORM_ANNOUNCEMENT"));
        assertEquals(0, inboxCount(cashier, biz, "PLATFORM_ANNOUNCEMENT"));
        // Todos.
        announce(root, "{\"title\":\"Feliz año\",\"body\":\"Gracias por usar Cuadra\",\"audience\":\"ALL\"," + mine + "}");
        assertEquals(1, inboxCount(cashier, biz, "PLATFORM_ANNOUNCEMENT"));
        // Segmento que no coincide: llega a nadie.
        String none = announce(root, "{\"title\":\"Solo CR\",\"body\":\"Hola\",\"audience\":\"ALL\",\"segment\":{\"countries\":[\"CR\"],\"businessIds\":[\"" + biz + "\"]}}");
        assertEquals(0, (int) JsonPath.read(none, "$.recipients"));
        // Segmento por plan.
        String pro = announce(root, "{\"title\":\"Solo Free\",\"body\":\"Hola\",\"audience\":\"OWNERS\",\"segment\":{\"plans\":[\"FREE\"],\"businessIds\":[\"" + biz + "\"]}}");
        assertEquals(0, (int) JsonPath.read(pro, "$.recipients"));
        // Cuántos recibirían, antes de enviar.
        call(post("/api/platform/announcements/reach"), bearer(root), "{\"audience\":\"ALL\",\"segment\":{\"businessIds\":[\"" + biz + "\"]}}")
                .andExpect(jsonPath("$.businesses", is(1))).andExpect(jsonPath("$.people", is(3)));

        // Programado: no sale hasta su hora.
        String at = java.time.Instant.now().plusSeconds(3600).toString();
        String sched = announce(root, "{\"title\":\"Más tarde\",\"body\":\"Llegará luego\",\"audience\":\"OWNERS\",\"scheduledAt\":\"" + at + "\"," + mine + "}");
        String id = JsonPath.read(sched, "$.id");
        assertEquals("SCHEDULED", JsonPath.read(sched, "$.state"));
        assertEquals(0, announcementService.runDue());
        assertEquals(3, inboxCount(owner, biz, "PLATFORM_ANNOUNCEMENT"));
        jdbc.sql("UPDATE platform_announcement SET scheduled_at = now() - interval '1 minute' WHERE id = :i").param("i", UUID.fromString(id)).update();
        assertEquals(1, announcementService.runDue());
        assertEquals(4, inboxCount(owner, biz, "PLATFORM_ANNOUNCEMENT"));
        assertEquals(0, announcementService.runDue()); // no se repite
        // Un programado se puede cancelar; uno enviado, no.
        String sched2 = announce(root, "{\"title\":\"Cancelar\",\"body\":\"No debe salir\",\"audience\":\"OWNERS\",\"scheduledAt\":\"" + at + "\"," + mine + "}");
        String id2 = JsonPath.read(sched2, "$.id");
        call(post("/api/platform/announcements/" + id2 + "/cancel"), bearer(root), null).andExpect(jsonPath("$.state", is("CANCELLED")));
        call(post("/api/platform/announcements/" + id + "/cancel"), bearer(root), null).andExpect(status().isConflict());
        call(get("/api/platform/announcements"), bearer(root), null).andExpect(status().isOk());
    }

    @Test
    void aBannerIsForEveryoneAndShowsInThePublicConfigUntilItEnds() throws Exception {
        String root = admin();
        call(post("/api/platform/announcements"), bearer(root), "{\"title\":\"Aviso\",\"body\":\"Hola\",\"audience\":\"OWNERS\",\"banner\":true,\"segment\":{\"countries\":[\"NI\"]}}")
                .andExpect(status().isBadRequest());
        String res = announce(root, "{\"title\":\"Mantenimiento hoy\",\"body\":\"Puede tardar la sincronización\",\"audience\":\"OWNERS\",\"banner\":true,"
                + "\"bannerUntil\":\"" + java.time.Instant.now().plusSeconds(7200) + "\"}");
        mvc.perform(get("/api/config")).andExpect(jsonPath("$.announcement.title", is("Mantenimiento hoy")));
        call(post("/api/platform/announcements/" + JsonPath.read(res, "$.id") + "/end-banner"), bearer(root), null).andExpect(status().isOk());
        mvc.perform(get("/api/config")).andExpect(jsonPath("$.announcement", is(nullValue())));
    }
}
