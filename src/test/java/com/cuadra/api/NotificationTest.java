package com.cuadra.api;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cuadra.api.notification.AutomaticNotifications;
import com.cuadra.api.notification.ScheduleService;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class NotificationTest extends ApiTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired ScheduleService schedules;
    @Autowired AutomaticNotifications automatic;

    private String base(UUID b) { return "/api/b/" + b; }

    private List<String> types(String token, UUID b) throws Exception {
        String json = call(get(base(b) + "/notifications?size=100"), bearer(token), null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.items[*].type");
    }

    private UUID trackedProduct(String owner, UUID b, long minMilli, long count) throws Exception {
        UUID p = UUID.randomUUID();
        call(put(base(b) + "/products/" + p), bearer(owner), "{\"name\":\"Crema\",\"priceMinor\":10000,\"trackStock\":true,\"minStockMilli\":" + minMilli + "}").andExpect(status().isCreated());
        call(put(base(b) + "/stock-movements/" + UUID.randomUUID()), bearer(owner), "{\"productId\":\"" + p + "\",\"kind\":\"INITIAL\",\"countedMilli\":" + count + "}").andExpect(status().isCreated());
        return p;
    }

    private void sell(String token, UUID b, UUID product, long qtyMilli) throws Exception {
        String item = "{\"id\":\"" + UUID.randomUUID() + "\",\"productId\":\"" + product + "\",\"name\":\"x\",\"unitPriceMinor\":10000,\"quantityMilli\":" + qtyMilli + "}";
        call(put(base(b) + "/sales/" + UUID.randomUUID()), bearer(token), SaleTest.sale("COMPLETED", item, SaleTest.pay("CASH", 10 * qtyMilli, ""), "")).andExpect(status().isCreated());
    }

    // ---------- avisos automáticos ----------

    @Test
    void lowStockAndOutOfStockNotifyOwnerAndAdminsOnceADayAndNeverCashiers() throws Exception {
        String owner = login("ntfa");
        UUID b = createBusiness(owner, "Ntf A");
        String admin = joinAs(owner, b, "ntfa2", "ADMIN");
        String cashier = joinAs(owner, b, "ntfa3", "CASHIER");
        UUID p = trackedProduct(owner, b, 3000, 5000);
        sell(cashier, b, p, 3000);   // 5 → 2: cruza el mínimo de 3
        sell(cashier, b, p, 500);    // sigue bajando: el mismo día no vuelve a avisar
        org.hamcrest.MatcherAssert.assertThat(types(owner, b), hasItem("LOW_STOCK"));
        assertEquals(1, types(owner, b).stream().filter("LOW_STOCK"::equals).count());
        org.hamcrest.MatcherAssert.assertThat(types(admin, b), hasItem("LOW_STOCK"));
        org.hamcrest.MatcherAssert.assertThat(types(cashier, b), not(hasItem("LOW_STOCK")));
        sell(cashier, b, p, 1500);   // 0: se agotó
        org.hamcrest.MatcherAssert.assertThat(types(owner, b), hasItem("OUT_OF_STOCK"));
        call(get(base(b) + "/notifications"), bearer(owner), null).andExpect(jsonPath("$.items[?(@.type=='LOW_STOCK')].args.productName", hasItem("Crema")))
                .andExpect(jsonPath("$.items[?(@.type=='LOW_STOCK')].channel", hasItem("STOCK"))).andExpect(jsonPath("$.items[?(@.type=='LOW_STOCK')].deepLink", hasItem("cuadra://inventario?filtro=bajo")));
    }

    @Test
    void closingAShiftNotifiesTheOwnerAndTheDifferenceOnlyWhenItPassesTheLimitButNotTheCloser() throws Exception {
        String owner = login("ntfb");
        UUID b = createBusiness(owner, "Ntf B");
        String cashier = joinAs(owner, b, "ntfb2", "CASHIER");
        call(put(base(b)), bearer(owner), "{\"shiftNoteThresholdMinor\":1000}").andExpect(status().isOk());
        UUID shift = UUID.randomUUID();
        call(put(base(b) + "/shifts/" + shift), bearer(cashier), "{\"openingFloatMinor\":100000}").andExpect(status().isCreated());
        // Esperado 1000.00 y contó 990.00: diferencia de 10.00 (= umbral, no lo supera).
        call(post(base(b) + "/shifts/" + shift + "/close"), bearer(cashier), "{\"countedMinor\":99000}").andExpect(status().isOk());
        org.hamcrest.MatcherAssert.assertThat(types(owner, b), hasItem("SHIFT_CLOSED"));
        org.hamcrest.MatcherAssert.assertThat(types(owner, b), not(hasItem("SHIFT_DIFFERENCE")));
        org.hamcrest.MatcherAssert.assertThat(types(cashier, b), not(hasItem("SHIFT_CLOSED")));

        UUID second = UUID.randomUUID();
        call(put(base(b) + "/shifts/" + second), bearer(cashier), "{\"openingFloatMinor\":100000}").andExpect(status().isCreated());
        call(post(base(b) + "/shifts/" + second + "/close"), bearer(cashier), "{\"countedMinor\":50000,\"note\":\"faltó\"}").andExpect(status().isOk());
        org.hamcrest.MatcherAssert.assertThat(types(owner, b), hasItem("SHIFT_DIFFERENCE"));
        call(get(base(b) + "/notifications"), bearer(owner), null).andExpect(jsonPath("$.items[?(@.type=='SHIFT_DIFFERENCE')].push", hasItem(true)))
                .andExpect(jsonPath("$.items[?(@.type=='SHIFT_DIFFERENCE')].deepLink", hasItem("cuadra://cierre/" + second)));
    }

    @Test
    void deletingACompletedSaleNotifiesTheOwnerButNotWhenTheOwnerDeletesIt() throws Exception {
        String owner = login("ntfc");
        UUID b = createBusiness(owner, "Ntf C");
        String admin = joinAs(owner, b, "ntfc2", "ADMIN");
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        for (UUID s : List.of(s1, s2)) call(put(base(b) + "/sales/" + s), bearer(owner), SaleTest.sale("COMPLETED", SaleTest.item("x", 500, 1000), SaleTest.pay("CASH", 500, ""), "")).andExpect(status().isCreated());
        call(post(base(b) + "/sales/" + s1 + "/cancel"), bearer(owner), "{\"reason\":\"error de cobro\"}").andExpect(status().isOk());
        org.hamcrest.MatcherAssert.assertThat(types(owner, b), not(hasItem("SALE_DELETED")));
        call(post(base(b) + "/sales/" + s2 + "/cancel"), bearer(admin), "{\"reason\":\"error de cobro\"}").andExpect(status().isOk());
        call(get(base(b) + "/notifications"), bearer(owner), null).andExpect(jsonPath("$.items[?(@.type=='SALE_DELETED')].args.totalMinor", hasItem(500)));
        org.hamcrest.MatcherAssert.assertThat(types(admin, b), not(hasItem("SALE_DELETED")));
    }

    @Test
    void whoInvitedIsToldWhenTheInvitationIsAccepted() throws Exception {
        String owner = login("ntfd");
        UUID b = createBusiness(owner, "Ntf D");
        joinAs(owner, b, "ntfd2", "CASHIER");
        call(get(base(b) + "/notifications"), bearer(owner), null).andExpect(jsonPath("$.items[?(@.type=='MEMBER_JOINED')]", hasSize(1)))
                .andExpect(jsonPath("$.items[?(@.type=='MEMBER_JOINED')].title", hasItem("Se unió alguien al equipo")));
    }

    // ---------- reglas de entrega ----------

    @Test
    void aPersonCanSilenceATypeAndQuietHoursKeepNonCriticalAlertsInTheInboxOnly() throws Exception {
        String owner = login("ntfe");
        UUID b = createBusiness(owner, "Ntf E");
        String cashier = joinAs(owner, b, "ntfe2", "CASHIER");
        // Horas de silencio que cubren "ahora" en la zona del negocio.
        LocalTime now = LocalTime.now(ZoneId.of("America/Managua"));
        String body = "{\"quietStart\":\"" + now.minusHours(1).withSecond(0).withNano(0) + "\",\"quietEnd\":\"" + now.plusHours(1).withSecond(0).withNano(0) + "\",\"summaryEnabled\":false,\"summaryTime\":\"21:00\",\"shiftReminderTime\":null,\"staleHours\":24}";
        call(put(base(b) + "/notification-settings"), bearer(owner), body).andExpect(status().isOk());
        UUID p = trackedProduct(owner, b, 3000, 5000);
        sell(cashier, b, p, 3000);
        call(get(base(b) + "/notifications"), bearer(owner), null).andExpect(jsonPath("$.items[?(@.type=='LOW_STOCK')].push", hasItem(false)));

        // Apagar un tipo: ya no se crea.
        call(put(base(b) + "/notification-preferences"), bearer(owner), "{\"type\":\"OUT_OF_STOCK\",\"enabled\":false}").andExpect(status().isOk()).andExpect(jsonPath("$.OUT_OF_STOCK", is(false))).andExpect(jsonPath("$.LOW_STOCK", is(true)));
        sell(cashier, b, p, 2000);
        org.hamcrest.MatcherAssert.assertThat(types(owner, b), not(hasItem("OUT_OF_STOCK")));
        call(put(base(b) + "/notification-preferences"), bearer(owner), "{\"type\":\"NOPE\",\"enabled\":false}").andExpect(status().isBadRequest());
    }

    @Test
    void theInboxIsPrivateCountsUnreadAndMarkingReadIsIdempotent() throws Exception {
        String owner = login("ntff");
        UUID b = createBusiness(owner, "Ntf F");
        String admin = joinAs(owner, b, "ntff2", "ADMIN");
        joinAs(owner, b, "ntff3", "CASHIER");
        call(get(base(b) + "/notifications/unread-count"), bearer(owner), null).andExpect(jsonPath("$.unread", is(2)));
        String json = call(get(base(b) + "/notifications"), bearer(owner), null).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(json, "$.items[0].id");
        call(post(base(b) + "/notifications/" + id + "/read"), bearer(admin), null).andExpect(status().isNotFound());
        call(post(base(b) + "/notifications/" + id + "/read"), bearer(owner), null).andExpect(status().isOk()).andExpect(jsonPath("$.readAt", notNullValue()));
        call(post(base(b) + "/notifications/" + id + "/read"), bearer(owner), null).andExpect(status().isOk());
        call(get(base(b) + "/notifications/unread-count"), bearer(owner), null).andExpect(jsonPath("$.unread", is(1)));
        call(get(base(b) + "/notifications?unreadOnly=true"), bearer(owner), null).andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    void markAllReadClearsTheInboxOfThePersonOnly() throws Exception {
        String owner = login("ntfr");
        UUID b = createBusiness(owner, "Ntf R");
        String admin = joinAs(owner, b, "ntfr2", "ADMIN");
        joinAs(owner, b, "ntfr3", "CASHIER");
        call(get(base(b) + "/notifications/unread-count"), bearer(owner), null).andExpect(jsonPath("$.unread", is(2)));
        call(post(base(b) + "/notifications/read-all"), bearer(owner), null).andExpect(status().isOk()).andExpect(jsonPath("$.marked", is(2)));
        call(get(base(b) + "/notifications/unread-count"), bearer(owner), null).andExpect(jsonPath("$.unread", is(0)));
        call(post(base(b) + "/notifications/read-all"), bearer(owner), null).andExpect(jsonPath("$.marked", is(0)));
        // El admin tiene sus propios avisos (uno del cajero que se unió) y no se tocan.
        call(get(base(b) + "/notifications/unread-count"), bearer(admin), null).andExpect(jsonPath("$.unread", is(0)));
    }

    @Test
    void notificationsReachAPhoneThroughSyncAndAreMarkedReadFromIt() throws Exception {
        String owner = login("ntfg");
        UUID b = createBusiness(owner, "Ntf G");
        UUID admin = createPinMember(owner, b, "Ana", "ADMIN");
        String device = linkDevice(owner, b);
        // Un producto que baja de su mínimo avisa a dueño y admins; el admin del teléfono lo recibe por sincronización.
        UUID sale = UUID.randomUUID();
        call(put(base(b) + "/sales/" + sale), bearer(owner), SaleTest.sale("COMPLETED", SaleTest.item("x", 500, 1000), SaleTest.pay("CASH", 500, ""), "")).andExpect(status().isCreated());
        UUID p = trackedProduct(owner, b, 3000, 5000);
        sell(owner, b, p, 3000);
        String pull = asDevice(get(base(b) + "/sync/pull?since=0&limit=500"), device, admin, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(pull, "$.changes[?(@.type=='notification')].data.id");
        org.hamcrest.MatcherAssert.assertThat(ids.size() >= 1, is(true));
        List<String> mine = JsonPath.read(pull, "$.changes[?(@.type=='notification')].data.type");
        org.hamcrest.MatcherAssert.assertThat(mine, hasItem("LOW_STOCK"));
        // Marcar como leída desde el teléfono (operación idempotente).
        UUID opId = UUID.randomUUID();
        String batch = "{\"ops\":[{\"opId\":\"" + opId + "\",\"kind\":\"NOTIFICATION_READ\",\"entityId\":\"" + ids.get(0) + "\",\"payload\":{}}]}";
        for (int i = 0; i < 2; i++) asDevice(post(base(b) + "/sync/push"), device, admin, batch).andExpect(status().isOk()).andExpect(jsonPath("$.results[0].status", is(i == 0 ? "APPLIED" : "DUPLICATE")));
        assertEquals(1, jdbc.sql("SELECT count(*) FROM notification WHERE id = :i AND read_at IS NOT NULL").param("i", UUID.fromString(ids.get(0))).query(Integer.class).single());
    }

    // ---------- avisos por el paso del tiempo ----------

    @Test
    void anUnclosedShiftAStalePhoneAndTheDailySummaryAreNotifiedOncePerDay() throws Exception {
        String owner = login("ntfh");
        UUID b = createBusiness(owner, "Ntf H");
        String cashier = joinAs(owner, b, "ntfh2", "CASHIER");
        UUID shift = UUID.randomUUID();
        call(put(base(b) + "/shifts/" + shift), bearer(cashier), "{\"openingFloatMinor\":1000}").andExpect(status().isCreated());
        String body = "{\"quietStart\":\"21:30\",\"quietEnd\":\"07:00\",\"summaryEnabled\":true,\"summaryTime\":\"00:00\",\"shiftReminderTime\":\"00:00\",\"staleHours\":1}";
        call(put(base(b) + "/notification-settings"), bearer(owner), body).andExpect(status().isOk());
        linkDevice(owner, b);
        jdbc.sql("UPDATE device SET pending_ops = 3, last_sync_at = now() - interval '5 hours' WHERE business_id = :b").param("b", b).update();
        automatic.check(b);
        automatic.check(b);
        List<String> owners = types(owner, b);
        assertEquals(1, owners.stream().filter("DAILY_SUMMARY"::equals).count());
        assertEquals(1, owners.stream().filter("DEVICE_STALE"::equals).count());
        assertEquals(1, owners.stream().filter("SHIFT_NOT_CLOSED"::equals).count());
        // El aviso del turno sin cerrar llega también a quien lo abrió.
        org.hamcrest.MatcherAssert.assertThat(types(cashier, b), hasItem("SHIFT_NOT_CLOSED"));
    }

    // ---------- programadas ----------

    private static String schedule(String title, String audience, String rule) {
        return "{\"title\":\"" + title + "\",\"body\":\"Recuerda contar el fondo\",\"audience\":" + audience + ",\"rule\":" + rule + "}";
    }

    private static final String DAILY9 = "{\"type\":\"DAILY\",\"time\":\"09:00\"}";

    @Test
    void aCashierCannotScheduleAndAnAdminOnlyAudienceIsNotReceivedByCashiers() throws Exception {
        String owner = login("ntfi");
        UUID b = createBusiness(owner, "Ntf I");
        String cashier = joinAs(owner, b, "ntfi2", "CASHIER");
        String admin = joinAs(owner, b, "ntfi3", "ADMIN");
        call(put(base(b) + "/notification-schedules/" + UUID.randomUUID()), bearer(cashier), schedule("Hola", "{\"all\":true}", DAILY9)).andExpect(status().isForbidden());
        call(get(base(b) + "/notification-schedules"), bearer(cashier), null).andExpect(status().isForbidden());
        UUID id = UUID.randomUUID();
        call(put(base(b) + "/notification-schedules/" + id), bearer(admin), schedule("Solo admins", "{\"roles\":[\"ADMIN\"]}", DAILY9)).andExpect(status().isOk()).andExpect(jsonPath("$.active", is(true)))
                .andExpect(jsonPath("$.nextRunAt", notNullValue())).andExpect(jsonPath("$.timezone", is("America/Managua")));
        jdbc.sql("UPDATE notification_schedule SET next_run_at = now() - interval '1 minute' WHERE id = :i").param("i", id).update();
        assertEquals(1, schedules.runDue());
        org.hamcrest.MatcherAssert.assertThat(types(admin, b), hasItem("SCHEDULED"));
        org.hamcrest.MatcherAssert.assertThat(types(cashier, b), not(hasItem("SCHEDULED")));
        // Corrió y programó el siguiente para después; correr otra vez no repite.
        assertEquals(0, schedules.runDue());
        call(get(base(b) + "/notification-schedules"), bearer(admin), null).andExpect(jsonPath("$[0].sent", is(1))).andExpect(jsonPath("$[0].lastRunAt", notNullValue()));
        call(get(base(b) + "/notification-schedules/" + id + "/runs"), bearer(admin), null).andExpect(jsonPath("$[0].status", is("SENT"))).andExpect(jsonPath("$[0].recipients", is(1)));
    }

    @Test
    void aSendMoreThanTwoHoursLateIsSkippedAndPausingNeverFiresWhatWasMissed() throws Exception {
        String owner = login("ntfj");
        UUID b = createBusiness(owner, "Ntf J");
        String cashier = joinAs(owner, b, "ntfj2", "CASHIER");
        UUID late = UUID.randomUUID();
        call(put(base(b) + "/notification-schedules/" + late), bearer(owner), schedule("Atrasada", "{\"roles\":[\"CASHIER\"]}", DAILY9)).andExpect(status().isOk());
        jdbc.sql("UPDATE notification_schedule SET next_run_at = now() - interval '3 hours' WHERE id = :i").param("i", late).update();
        assertEquals(1, schedules.runDue());
        org.hamcrest.MatcherAssert.assertThat(types(cashier, b), not(hasItem("SCHEDULED")));
        call(get(base(b) + "/notification-schedules/" + late + "/runs"), bearer(owner), null).andExpect(jsonPath("$[0].status", is("SKIPPED_LATE")));
        call(get(base(b) + "/notification-schedules"), bearer(owner), null).andExpect(jsonPath("$[0].active", is(true))).andExpect(jsonPath("$[0].nextRunAt", notNullValue()));

        UUID paused = UUID.randomUUID();
        call(put(base(b) + "/notification-schedules/" + paused), bearer(owner), schedule("Pausada", "{\"roles\":[\"CASHIER\"]}", DAILY9)).andExpect(status().isOk());
        call(post(base(b) + "/notification-schedules/" + paused + "/active"), bearer(owner), "{\"active\":false}").andExpect(status().isOk()).andExpect(jsonPath("$.active", is(false))).andExpect(jsonPath("$.nextRunAt", nullValue()));
        assertEquals(0, schedules.runDue());
        // Reanudar calcula desde ahora: nada se dispara al instante.
        call(post(base(b) + "/notification-schedules/" + paused + "/active"), bearer(owner), "{\"active\":true}").andExpect(status().isOk()).andExpect(jsonPath("$.nextRunAt", notNullValue()));
        assertEquals(0, schedules.runDue());
        org.hamcrest.MatcherAssert.assertThat(types(cashier, b), not(hasItem("SCHEDULED")));
    }

    @Test
    void sendNowDeliversImmediatelyToTheChosenPeopleAndPhonesAndKeepsAHistory() throws Exception {
        String owner = login("ntfk");
        UUID b = createBusiness(owner, "Ntf K");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        String deviceToken = linkDevice(owner, b);
        UUID device = jdbc.sql("SELECT id FROM device WHERE business_id = :b").param("b", b).query(UUID.class).single();
        UUID id = UUID.randomUUID();
        call(post(base(b) + "/notification-schedules/" + id + "/send-now"), bearer(owner), schedule("Hoy llega el proveedor", "{\"memberIds\":[\"" + kevin + "\"],\"deviceIds\":[\"" + device + "\"]}", "{\"type\":\"ONCE\",\"at\":\"2030-01-01T09:00\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active", is(false))).andExpect(jsonPath("$.sent", is(2)));
        // Kevin lo ve por su cuenta y el teléfono por ser destinatario.
        String pull = asDevice(get(base(b) + "/sync/pull?since=0&limit=500"), deviceToken, kevin, null).andReturn().getResponse().getContentAsString();
        List<String> titles = JsonPath.read(pull, "$.changes[?(@.type=='notification')].data.title");
        assertEquals(2, titles.size());
        call(get(base(b) + "/notification-schedules/" + id + "/runs"), bearer(owner), null).andExpect(jsonPath("$[0].recipients", is(2)));
        // Repetir la misma orden no envía otra vez.
        call(post(base(b) + "/notification-schedules/" + id + "/send-now"), bearer(owner), schedule("Hoy llega el proveedor", "{\"all\":true}", "{\"type\":\"ONCE\",\"at\":\"2030-01-01T09:00\"}")).andExpect(status().isOk());
        assertEquals(2, jdbc.sql("SELECT count(*) FROM notification WHERE schedule_id = :s").param("s", id).query(Integer.class).single());
    }

    @Test
    void scheduleValidationAndDeletion() throws Exception {
        String owner = login("ntfl");
        UUID b = createBusiness(owner, "Ntf L");
        call(put(base(b) + "/notification-schedules/" + UUID.randomUUID()), bearer(owner), schedule("", "{\"all\":true}", DAILY9)).andExpect(status().isBadRequest());
        call(put(base(b) + "/notification-schedules/" + UUID.randomUUID()), bearer(owner), schedule("x", "{}", DAILY9)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("INVALID_AUDIENCE")));
        call(put(base(b) + "/notification-schedules/" + UUID.randomUUID()), bearer(owner), schedule("x", "{\"all\":true}", "{\"type\":\"WEEKLY\",\"time\":\"09:00\",\"days\":[]}")).andExpect(status().isBadRequest());
        call(put(base(b) + "/notification-schedules/" + UUID.randomUUID()), bearer(owner), schedule("x", "{\"all\":true}", "{\"type\":\"ONCE\",\"at\":\"2020-01-01T09:00\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code", is("RULE_ENDED")));
        call(put(base(b) + "/notification-schedules/" + UUID.randomUUID()), bearer(owner), "{\"title\":\"x\",\"body\":\"y\",\"deepLink\":\"http://evil\",\"audience\":{\"all\":true},\"rule\":" + DAILY9 + "}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_LINK")));
        UUID id = UUID.randomUUID();
        call(put(base(b) + "/notification-schedules/" + id), bearer(owner), "{\"title\":\"x\",\"body\":\"y\",\"deepLink\":\"cuadra://caja\",\"audience\":{\"all\":true},\"rule\":" + DAILY9 + "}").andExpect(status().isOk())
                .andExpect(jsonPath("$.deepLink", is("cuadra://caja")));
        call(delete(base(b) + "/notification-schedules/" + id), bearer(owner), null).andExpect(status().isNoContent());
        call(get(base(b) + "/notification-schedules"), bearer(owner), null).andExpect(jsonPath("$", hasSize(0)));
        // Otro negocio no ve ni toca las del primero.
        String other = login("ntfl2");
        UUID b2 = createBusiness(other, "Ntf L2");
        call(post(base(b2) + "/notification-schedules/" + id + "/active"), bearer(other), "{\"active\":true}").andExpect(status().isNotFound());
    }

    @Test
    void settingsAndPushTokensAreStoredAndOnlyTheOwnerEditsTheBusinessSettings() throws Exception {
        String owner = login("ntfm");
        UUID b = createBusiness(owner, "Ntf M");
        String admin = joinAs(owner, b, "ntfm2", "ADMIN");
        call(get(base(b) + "/notification-settings"), bearer(admin), null).andExpect(jsonPath("$.quietStart", is("21:30"))).andExpect(jsonPath("$.quietEnd", is("07:00"))).andExpect(jsonPath("$.staleHours", is(24)));
        String body = "{\"quietStart\":\"22:00\",\"quietEnd\":\"06:30\",\"summaryEnabled\":true,\"summaryTime\":\"20:00\",\"shiftReminderTime\":\"21:00\",\"staleHours\":12}";
        call(put(base(b) + "/notification-settings"), bearer(admin), body).andExpect(status().isForbidden());
        call(put(base(b) + "/notification-settings"), bearer(owner), body).andExpect(status().isOk()).andExpect(jsonPath("$.summaryEnabled", is(true))).andExpect(jsonPath("$.shiftReminderTime", is("21:00")));
        call(put(base(b) + "/notification-settings"), bearer(owner), body.replace("22:00", "25:00")).andExpect(status().isBadRequest());
        for (int i = 0; i < 2; i++) call(put(base(b) + "/push-tokens"), bearer(owner), "{\"fcmToken\":\"tok-1\",\"locale\":\"en\",\"appVersion\":\"1.0\"}").andExpect(status().isNoContent());
        assertEquals(1, jdbc.sql("SELECT count(*) FROM push_token WHERE fcm_token = 'tok-1'").query(Integer.class).single());
        call(put(base(b) + "/push-tokens"), bearer(owner), "{\"fcmToken\":\"\"}").andExpect(status().isBadRequest());
    }
}
