package com.cuadra.api;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Lo que pasa cuando algo sale mal fuera de línea (docs/notas/revision-flujo-negocio.md): cada operación de la cola se aplica con quien la HIZO, un pago
 * recibido nunca se pierde, el límite de crédito también lo cuida el servidor, una baja corta los teléfonos personales sin perder lo ya hecho, y un
 * cambio parcial de producto no revierte lo que otro cambió.
 */
class OfflineSafetyTest extends ApiTestBase {
    @Autowired JdbcClient jdbc;

    private static String op(UUID opId, String kind, UUID entityId, String payload) {
        return "{\"opId\":\"" + opId + "\",\"kind\":\"" + kind + "\",\"entityId\":\"" + entityId + "\",\"payload\":" + payload + "}";
    }

    /** Operación con su autor y la hora del teléfono al hacerla. */
    private static String opBy(UUID member, Instant at, String kind, UUID entityId, String payload) {
        return "{\"opId\":\"" + UUID.randomUUID() + "\",\"kind\":\"" + kind + "\",\"entityId\":\"" + entityId + "\",\"payload\":" + payload
                + (member == null ? "" : ",\"memberId\":\"" + member + "\"") + (at == null ? "" : ",\"createdAt\":\"" + at + "\"") + "}";
    }

    private static String push(String... ops) {
        return "{\"ops\":[" + String.join(",", ops) + "]}";
    }

    private static String completed(long price) {
        return SaleTest.sale("COMPLETED", SaleTest.item("Cuajada", price, 1000), SaleTest.pay("CASH", price, ""), "\"completedAt\":\"2026-09-20T15:00:00Z\"");
    }

    private String pushUrl(UUID b) { return "/api/b/" + b + "/sync/push"; }

    private String codeOf(String owner, UUID b) throws Exception {
        return JsonPath.read(call(get("/api/b/" + b), bearer(owner), null).andReturn().getResponse().getContentAsString(), "$.accessCode");
    }

    private ResultActions memberLogin(String code, String pin) throws Exception {
        return mvc.perform(post("/api/auth/member-login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"businessCode\":\"" + code + "\",\"pin\":\"" + pin + "\",\"deviceName\":\"Mi teléfono\",\"model\":\"X\"}"));
    }

    private String personalPhone(String owner, UUID b, String user) throws Exception {
        return JsonPath.read(memberLogin(codeOf(owner, b), pinOf(b, user)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.deviceToken");
    }

    // ---------- quién hizo cada operación ----------

    @Test
    void aSaleMadeByKevinAndSentWhileLuciaIsActiveIsKevins() throws Exception {
        String owner = login("off-a");
        UUID b = createBusiness(owner, "Off A");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        UUID lucia = createPinMember(owner, b, "Lucia", "CASHIER");
        String device = linkDevice(owner, b);
        UUID sale = UUID.randomUUID();
        asDevice(post(pushUrl(b)), device, lucia, push(opBy(kevin, Instant.now(), "SALE_UPSERT", sale, completed(1000))))
                .andExpect(jsonPath("$.results[0].status", is("APPLIED")));
        call(get("/api/b/" + b + "/sales/" + sale), bearer(owner), null).andExpect(jsonPath("$.createdBy.id", is(kevin.toString())))
                .andExpect(jsonPath("$.completedBy.name", is("Kevin")));
        // Sin autor (versión vieja de la app): se usa la persona de la cabecera, como antes.
        UUID old = UUID.randomUUID();
        asDevice(post(pushUrl(b)), device, lucia, push(op(UUID.randomUUID(), "SALE_UPSERT", old, completed(500)))).andExpect(jsonPath("$.results[0].status", is("APPLIED")));
        call(get("/api/b/" + b + "/sales/" + old), bearer(owner), null).andExpect(jsonPath("$.createdBy.id", is(lucia.toString())));
    }

    @Test
    void anOwnerActionQueuedAndSentWhileACashierIsActiveKeepsTheOwnersRoleOnlyIfThePhoneAllowsOrThePinWasVerified() throws Exception {
        String owner = login("off-b");
        UUID b = createBusiness(owner, "Off B");
        UUID ownerMember = memberIdOf(owner, b);
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        String shared = linkDevice(owner, b);   // vinculado por el dueño: puede actuar como dueño
        String withdrawal = "{\"kind\":\"WITHDRAWAL\",\"amountMinor\":1000}";
        asDevice(post(pushUrl(b)), shared, kevin, push(opBy(ownerMember, Instant.now(), "CASH_MOVEMENT_UPSERT", UUID.randomUUID(), withdrawal)))
                .andExpect(jsonPath("$.results[0].status", is("APPLIED")));
        // El mismo retiro como Kevin (el que está activo): un cajero no puede sacar dinero.
        asDevice(post(pushUrl(b)), shared, kevin, push(opBy(kevin, Instant.now(), "CASH_MOVEMENT_UPSERT", UUID.randomUUID(), withdrawal)))
                .andExpect(jsonPath("$.results[0].code", is("FORBIDDEN")));
        // En un teléfono que vinculó un cajero, el dueño solo actúa como dueño si el servidor verificó su PIN en ese teléfono: sin eso, rechazo visible.
        String personal = personalPhone(owner, b, "Kevin");
        asDevice(post(pushUrl(b)), personal, kevin, push(opBy(ownerMember, Instant.now(), "CASH_MOVEMENT_UPSERT", UUID.randomUUID(), withdrawal)))
                .andExpect(jsonPath("$.results[0].status", is("REJECTED"))).andExpect(jsonPath("$.results[0].code", is("PIN_VERIFICATION_REQUIRED")));
        // Una sesión web solo actúa como sí misma.
        call(post(pushUrl(b)), bearer(owner), push(opBy(kevin, Instant.now(), "SALE_UPSERT", UUID.randomUUID(), completed(100))))
                .andExpect(jsonPath("$.results[0].code", is("MEMBER_MISMATCH")));
        // Alguien de otro negocio no existe aquí.
        asDevice(post(pushUrl(b)), shared, kevin, push(opBy(UUID.randomUUID(), Instant.now(), "SALE_UPSERT", UUID.randomUUID(), completed(100))))
                .andExpect(jsonPath("$.results[0].code", is("MEMBER_NOT_ACTIVE")));
    }

    // ---------- baja de una persona ----------

    @Test
    void disablingACashierCutsTheirPersonalPhoneButWhatTheyDidBeforeStillArrives() throws Exception {
        String owner = login("off-c");
        UUID b = createBusiness(owner, "Off C");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        UUID lucia = createPinMember(owner, b, "Lucia", "CASHIER");
        String personal = personalPhone(owner, b, "Kevin");
        String shared = linkDevice(owner, b);
        asDevice(get("/api/b/" + b + "/sync/pull?since=0"), personal, kevin, null).andExpect(status().isOk());
        Instant before = Instant.now().minusSeconds(60);

        call(put("/api/b/" + b + "/members/" + kevin), bearer(owner), "{\"status\":\"DISABLED\"}").andExpect(status().isOk());
        assertEquals("MEMBER_DISABLED", jdbc.sql("SELECT revoked_reason FROM device WHERE linked_by_member_id = :m AND kind = 'PERSONAL'").param("m", kevin).query(String.class).single());

        // Su teléfono ya no baja nada ni usa otra ruta: la app muestra «Tu acceso fue desactivado».
        assertCode(asDevice(get("/api/b/" + b + "/sync/pull?since=0"), personal, kevin, null).andExpect(status().isUnauthorized()), "ACCESS_DISABLED");
        assertCode(asDevice(get("/api/b/" + b + "/members"), personal, kevin, null).andExpect(status().isUnauthorized()), "ACCESS_DISABLED");
        // Pero lo que cobró ANTES de la baja sí llega (el dinero está en el cajón); lo hecho después, no.
        UUID early = UUID.randomUUID();
        asDevice(post(pushUrl(b)), personal, kevin, push(opBy(kevin, before, "SALE_UPSERT", early, completed(1500)),
                        opBy(kevin, Instant.now().plusSeconds(1), "SALE_UPSERT", UUID.randomUUID(), completed(900))))
                .andExpect(jsonPath("$.results[0].status", is("APPLIED")))
                .andExpect(jsonPath("$.results[1].status", is("REJECTED"))).andExpect(jsonPath("$.results[1].code", is("ACCESS_DISABLED")));
        call(get("/api/b/" + b + "/sales/" + early), bearer(owner), null).andExpect(jsonPath("$.createdBy.id", is(kevin.toString())));

        // En un teléfono compartido pasa lo mismo: lo de Kevin anterior a la baja se acepta aunque lo envíe Lucía; lo posterior se rechaza (visible).
        asDevice(post(pushUrl(b)), shared, lucia, push(opBy(kevin, before, "SALE_UPSERT", UUID.randomUUID(), completed(700)),
                        opBy(kevin, Instant.now().plusSeconds(1), "SALE_UPSERT", UUID.randomUUID(), completed(800)), opBy(kevin, null, "SALE_UPSERT", UUID.randomUUID(), completed(800))))
                .andExpect(jsonPath("$.results[0].status", is("APPLIED")))
                .andExpect(jsonPath("$.results[1].code", is("MEMBER_NOT_ACTIVE"))).andExpect(jsonPath("$.results[2].code", is("MEMBER_NOT_ACTIVE")));
        // Kevin ya no puede entrar ni actuar en el compartido.
        assertCode(asDevice(get("/api/b/" + b + "/sync/pull?since=0"), shared, kevin, null).andExpect(status().isForbidden()), "MEMBER_NOT_ACTIVE");
        // Un teléfono revocado a mano no envía nada.
        String other = personalPhone(owner, b, "Lucia");
        UUID otherId = jdbc.sql("SELECT id FROM device WHERE linked_by_member_id = :m").param("m", lucia).query(UUID.class).single();
        call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/b/" + b + "/devices/" + otherId), bearer(owner), null).andExpect(status().is2xxSuccessful());
        asDevice(post(pushUrl(b)), other, lucia, push(opBy(lucia, before, "SALE_UPSERT", UUID.randomUUID(), completed(100)))).andExpect(status().isUnauthorized());
    }

    @Test
    void resettingAPinDoesNotLiftTheBusinessLockout() throws Exception {
        String owner = login("off-d");
        UUID b = createBusiness(owner, "Off D");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        String code = codeOf(owner, b);
        for (int i = 0; i < 10; i++) memberLogin(code, "00000").andExpect(status().isUnauthorized());
        assertCode(memberLogin(code, pinOf(b, "Kevin")).andExpect(status().isTooManyRequests()), "LOCKED");
        // El bloqueo es del negocio (no se sabe quién se equivocó): cambiar un PIN no lo levanta; pasa solo a los 15 minutos.
        call(put("/api/b/" + b + "/members/" + kevin + "/pin"), bearer(owner), "{\"pin\":\"24680\"}").andExpect(status().isNoContent());
        assertCode(memberLogin(code, "24680").andExpect(status().isTooManyRequests()), "LOCKED");
        baseJdbc.sql("UPDATE business SET member_login_locked_until = now() - interval '1 second' WHERE id = :b").param("b", b).update();
        memberLogin(code, "24680").andExpect(status().isOk());
    }

    // ---------- límite de crédito ----------

    @Test
    void theServerEnforcesTheCreditLimitWhenTheBusinessAsksForIt() throws Exception {
        String owner = login("off-e");
        UUID b = createBusiness(owner, "Off E");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        String device = linkDevice(owner, b);
        UUID marta = UUID.randomUUID();
        asDevice(post(pushUrl(b)), device, kevin, push(op(UUID.randomUUID(), "CUSTOMER_UPSERT", marta, "{\"name\":\"Marta\",\"creditLimitMinor\":50000}")))
                .andExpect(jsonPath("$.results[0].status", is("APPLIED")));
        String credit = "\"customerId\":\"" + marta + "\"";
        String over = SaleTest.sale("COMPLETED", SaleTest.item("Arroz", 60000, 1000), SaleTest.pay("CREDIT", 60000, credit), "");
        // Sin la regla encendida, el límite solo avisa: se acepta.
        asDevice(post(pushUrl(b)), device, kevin, push(op(UUID.randomUUID(), "SALE_UPSERT", UUID.randomUUID(), over))).andExpect(jsonPath("$.results[0].status", is("APPLIED")));

        call(put("/api/b/" + b), bearer(owner), "{\"creditLimitEnforced\":true}").andExpect(status().isOk());
        // Ya debe 600 con límite de 500: otro fiado de 10 se rechaza, con el límite y el saldo para explicarlo.
        String small = SaleTest.sale("COMPLETED", SaleTest.item("Pan", 1000, 1000), SaleTest.pay("CREDIT", 1000, credit), "");
        asDevice(post(pushUrl(b)), device, kevin, push(op(UUID.randomUUID(), "SALE_UPSERT", UUID.randomUUID(), small)))
                .andExpect(jsonPath("$.results[0].status", is("REJECTED"))).andExpect(jsonPath("$.results[0].code", is("CREDIT_LIMIT_EXCEEDED")))
                .andExpect(jsonPath("$.results[0].detail.limitMinor", is(50000))).andExpect(jsonPath("$.results[0].detail.balanceMinor", is(60000)));
        // Lo mismo con un fiado sin venta y por la web.
        asDevice(post(pushUrl(b)), device, kevin, push(op(UUID.randomUUID(), "CREDIT_UPSERT", UUID.randomUUID(), "{\"customerId\":\"" + marta + "\",\"amountMinor\":100}")))
                .andExpect(jsonPath("$.results[0].code", is("CREDIT_LIMIT_EXCEEDED")));
        assertCode(call(put("/api/b/" + b + "/credits/" + UUID.randomUUID()), bearer(owner), "{\"customerId\":\"" + marta + "\",\"amountMinor\":100}")
                .andExpect(status().isConflict()), "CREDIT_LIMIT_EXCEEDED");
        // Un fiado a quien no es cliente (o sin límite) no se toca; pagar en efectivo tampoco.
        asDevice(post(pushUrl(b)), device, kevin, push(op(UUID.randomUUID(), "SALE_UPSERT", UUID.randomUUID(),
                SaleTest.sale("COMPLETED", SaleTest.item("Pan", 1000, 1000), SaleTest.pay("CREDIT", 1000, "\"debtorLabel\":\"Don José\""), ""))))
                .andExpect(jsonPath("$.results[0].status", is("APPLIED")));
    }

    // ---------- un pago recibido nunca se pierde ----------

    @Test
    void aParkedTicketChargedOnTwoPhonesKeepsBothSalesAndFlagsTheSecond() throws Exception {
        String owner = login("off-f");
        UUID b = createBusiness(owner, "Off F");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        UUID lucia = createPinMember(owner, b, "Lucia", "CASHIER");
        String phoneA = linkDevice(owner, b);
        String phoneB = linkDevice(owner, b);
        UUID ticket = UUID.randomUUID();
        String parked = SaleTest.sale("PARKED", SaleTest.item("Cuajada", 2000, 1000), "", "");
        asDevice(post(pushUrl(b)), phoneA, kevin, push(op(UUID.randomUUID(), "SALE_UPSERT", ticket, parked))).andExpect(jsonPath("$.results[0].status", is("APPLIED")));
        // Los dos teléfonos la retomaron sin conexión y la cobraron (distinto).
        asDevice(post(pushUrl(b)), phoneA, kevin, push(op(UUID.randomUUID(), "SALE_UPSERT", ticket,
                SaleTest.sale("COMPLETED", SaleTest.item("Cuajada", 2000, 1000), SaleTest.pay("CASH", 2000, ""), "\"fromStatus\":\"PARKED\"")))).andExpect(jsonPath("$.results[0].status", is("APPLIED")));
        UUID secondOp = UUID.randomUUID();
        String second = push(op(secondOp, "SALE_UPSERT", ticket,
                SaleTest.sale("COMPLETED", SaleTest.item("Cuajada", 2000, 2000), SaleTest.pay("CARD", 4000, ""), "\"fromStatus\":\"PARKED\"")));
        String res = asDevice(post(pushUrl(b)), phoneB, lucia, second).andExpect(jsonPath("$.results[0].status", is("APPLIED")))
                .andExpect(jsonPath("$.results[0].code", is("SALE_CONFLICT_COPY"))).andExpect(jsonPath("$.results[0].detail.copySaleId", notNullValue()))
                .andReturn().getResponse().getContentAsString();
        String copy = JsonPath.read(res, "$.results[0].detail.copySaleId");
        // La primera no se pisó; la segunda existe aparte, marcada, y el dueño recibe el aviso.
        call(get("/api/b/" + b + "/sales/" + ticket), bearer(owner), null).andExpect(jsonPath("$.totalMinor", is(2000))).andExpect(jsonPath("$.payments[0].method", is("CASH")));
        call(get("/api/b/" + b + "/sales/" + copy), bearer(owner), null).andExpect(jsonPath("$.totalMinor", is(4000))).andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.conflictOfSaleId", is(ticket.toString()))).andExpect(jsonPath("$.createdBy.id", is(lucia.toString())));
        call(get("/api/b/" + b + "/notifications"), bearer(owner), null).andExpect(jsonPath("$.items[*].type", hasItem("SALE_CONFLICT")));
        // Reenviar la misma operación no crea otra copia.
        asDevice(post(pushUrl(b)), phoneB, lucia, second).andExpect(jsonPath("$.results[0].status", is("DUPLICATE")));
        assertEquals(1, jdbc.sql("SELECT count(*) FROM sale WHERE conflict_of_sale_id = :s").param("s", ticket).query(Integer.class).single());
        // Quien sí puede editar ventas y edita a propósito (sin `fromStatus`) sigue modificando la venta, sin copias.
        call(put("/api/b/" + b + "/sales/" + ticket), bearer(owner), SaleTest.sale("COMPLETED", SaleTest.item("Cuajada", 2500, 1000), SaleTest.pay("CASH", 2500, ""), ""))
                .andExpect(status().is2xxSuccessful()).andExpect(jsonPath("$.totalMinor", is(2500)));
    }

    @Test
    void aTicketChargedAfterAnotherPhoneDiscardedItIsSavedAsANewSaleNotDropped() throws Exception {
        String owner = login("off-g");
        UUID b = createBusiness(owner, "Off G");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        String device = linkDevice(owner, b);
        UUID ticket = UUID.randomUUID();
        asDevice(post(pushUrl(b)), device, kevin, push(op(UUID.randomUUID(), "SALE_UPSERT", ticket, SaleTest.sale("PARKED", SaleTest.item("Queso", 3000, 1000), "", ""))));
        asDevice(post(pushUrl(b)), device, kevin, push(op(UUID.randomUUID(), "SALE_CANCEL", ticket, "{}"))).andExpect(jsonPath("$.results[0].status", is("APPLIED")));
        asDevice(post(pushUrl(b)), device, kevin, push(op(UUID.randomUUID(), "SALE_UPSERT", ticket,
                SaleTest.sale("COMPLETED", SaleTest.item("Queso", 3000, 1000), SaleTest.pay("CASH", 3000, ""), "\"fromStatus\":\"PARKED\""))))
                .andExpect(jsonPath("$.results[0].code", is("SALE_CONFLICT_COPY")));
        call(get("/api/b/" + b + "/sales/" + ticket), bearer(owner), null).andExpect(jsonPath("$.status", is("CANCELLED")));
        assertEquals(3000L, jdbc.sql("SELECT total_minor FROM sale WHERE conflict_of_sale_id = :s AND status = 'COMPLETED'").param("s", ticket).query(Long.class).single());
        // Una versión vieja (apartada) sobre la descartada sigue siendo simplemente vieja.
        asDevice(post(pushUrl(b)), device, kevin, push(op(UUID.randomUUID(), "SALE_UPSERT", ticket, SaleTest.sale("PARKED", SaleTest.item("Queso", 3000, 1000), "", ""))))
                .andExpect(jsonPath("$.results[0].status", is("STALE")));
    }

    @Test
    void aPaymentToACreditClosedOnAnotherPhoneGoesToTheCustomersOtherDebtsOrIsRejectedVisibly() throws Exception {
        String owner = login("off-h");
        UUID b = createBusiness(owner, "Off H");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        String device = linkDevice(owner, b);
        UUID marta = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        asDevice(post(pushUrl(b)), device, kevin, push(op(UUID.randomUUID(), "CUSTOMER_UPSERT", marta, "{\"name\":\"Marta\"}"),
                op(UUID.randomUUID(), "CREDIT_UPSERT", first, "{\"customerId\":\"" + marta + "\",\"amountMinor\":5000}"),
                op(UUID.randomUUID(), "CREDIT_UPSERT", second, "{\"customerId\":\"" + marta + "\",\"amountMinor\":3000}")));
        call(post(pushUrl(b)), bearer(owner), push(op(UUID.randomUUID(), "CREDIT_WRITE_OFF", first, "{\"reason\":\"acuerdo\"}"))).andExpect(jsonPath("$.results[0].status", is("APPLIED")));
        // El cajero, sin conexión, cobró 2,000 a ese fiado ya condonado: el abono va a la otra deuda de Marta.
        UUID pay = UUID.randomUUID();
        asDevice(post(pushUrl(b)), device, kevin, push(op(UUID.randomUUID(), "CREDIT_PAYMENT", pay, "{\"creditId\":\"" + first + "\",\"amountMinor\":2000,\"method\":\"CASH\"}")))
                .andExpect(jsonPath("$.results[0].status", is("APPLIED")));
        call(get("/api/b/" + b + "/credits/" + second), bearer(owner), null).andExpect(jsonPath("$.balanceMinor", is(1000)));
        call(get("/api/b/" + b + "/credits/" + first), bearer(owner), null).andExpect(jsonPath("$.status", is("WRITTEN_OFF")));
        // Sin otra deuda donde ponerlo (no hay «saldo a favor»): rechazo visible, que el teléfono conserva.
        call(post(pushUrl(b)), bearer(owner), push(op(UUID.randomUUID(), "CREDIT_WRITE_OFF", second, "{\"reason\":\"acuerdo\"}")));
        asDevice(post(pushUrl(b)), device, kevin, push(op(UUID.randomUUID(), "CREDIT_PAYMENT", UUID.randomUUID(), "{\"creditId\":\"" + first + "\",\"amountMinor\":500,\"method\":\"CASH\"}")))
                .andExpect(jsonPath("$.results[0].status", is("REJECTED"))).andExpect(jsonPath("$.results[0].code", is("CREDIT_CLOSED")));
    }

    // ---------- cambios parciales de producto ----------

    @Test
    void anOldPhoneChangingOnlyTheNameDoesNotRevertANewerPriceAndCashierPriceChangesNotifyTheOwner() throws Exception {
        String owner = login("off-i");
        UUID b = createBusiness(owner, "Off I");
        UUID kevin = createPinMember(owner, b, "Kevin", "CASHIER");
        String device = linkDevice(owner, b);
        UUID p = UUID.randomUUID();
        asDevice(post(pushUrl(b)), device, kevin, push(op(UUID.randomUUID(), "PRODUCT_UPSERT", p, "{\"name\":\"Cuajada\",\"priceMinor\":2500,\"costMinor\":1500}")));
        // El dueño sube el precio en la web.
        call(put("/api/b/" + b + "/products/" + p), bearer(owner), "{\"name\":\"Cuajada\",\"priceMinor\":3000,\"costMinor\":1500}").andExpect(status().isOk());
        // Un teléfono que aún tenía 2,500 cambia solo el nombre y reordena sus frecuentes: el precio nuevo se queda.
        asDevice(post(pushUrl(b)), device, kevin, push(op(UUID.randomUUID(), "PRODUCT_PATCH", p, "{\"set\":{\"name\":\"Cuajada fresca\"},\"baseRev\":1}"),
                        op(UUID.randomUUID(), "PRODUCT_PATCH", p, "{\"set\":{\"isQuick\":true,\"quickPosition\":2}}")))
                .andExpect(jsonPath("$.results[0].status", is("APPLIED"))).andExpect(jsonPath("$.results[1].status", is("APPLIED")));
        call(get("/api/b/" + b + "/products/" + p), bearer(owner), null).andExpect(jsonPath("$.name", is("Cuajada fresca"))).andExpect(jsonPath("$.priceMinor", is(3000)))
                .andExpect(jsonPath("$.costMinor", is(1500))).andExpect(jsonPath("$.isQuick", is(true))).andExpect(jsonPath("$.quickPosition", is(2)));
        call(get("/api/b/" + b + "/notifications"), bearer(owner), null).andExpect(jsonPath("$.items[*].type", not(hasItem("PRICE_CHANGED"))));
        // Un cajero cambia el precio: el dueño recibe el aviso con el antes y el después.
        asDevice(post(pushUrl(b)), device, kevin, push(op(UUID.randomUUID(), "PRODUCT_PATCH", p, "{\"set\":{\"priceMinor\":3200}}")))
                .andExpect(jsonPath("$.results[0].status", is("APPLIED")));
        call(get("/api/b/" + b + "/notifications"), bearer(owner), null)
                .andExpect(jsonPath("$.items[?(@.type=='PRICE_CHANGED')].args.toPriceMinor", hasItem(3200)))
                .andExpect(jsonPath("$.items[?(@.type=='PRICE_CHANGED')].args.fromPriceMinor", hasItem(3000)))
                .andExpect(jsonPath("$.items[?(@.type=='PRICE_CHANGED')].args.memberName", hasItem("Kevin")));
        // Un cambio parcial a un producto que no existe se rechaza (no inventa uno a medias); vaciar un campo con null explícito sí vale.
        asDevice(post(pushUrl(b)), device, kevin, push(op(UUID.randomUUID(), "PRODUCT_PATCH", UUID.randomUUID(), "{\"set\":{\"name\":\"x\"}}")))
                .andExpect(jsonPath("$.results[0].code", is("PRODUCT_NOT_FOUND")));
        asDevice(post(pushUrl(b)), device, kevin, push(op(UUID.randomUUID(), "PRODUCT_PATCH", p, "{\"set\":{\"costMinor\":null}}"))).andExpect(jsonPath("$.results[0].status", is("APPLIED")));
        call(get("/api/b/" + b + "/products/" + p), bearer(owner), null).andExpect(jsonPath("$.costMinor").doesNotExist()).andExpect(jsonPath("$.priceMinor", is(3200)));
        List<Integer> sizes = JsonPath.read(call(get("/api/b/" + b + "/notifications"), bearer(owner), null).andReturn().getResponse().getContentAsString(), "$.items[?(@.type=='PRICE_CHANGED')].rev");
        org.hamcrest.MatcherAssert.assertThat(sizes, hasSize(2));   // precio y luego costo: dos avisos
    }
}
