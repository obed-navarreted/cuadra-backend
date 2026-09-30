package com.cuadra.api.tenancy;

import com.cuadra.api.common.ApiException;
import com.cuadra.api.security.Actor;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Único punto donde se decide a qué negocio y con qué rol se actúa. Todo endpoint de negocio pasa por aquí:
 * un usuario solo entra a negocios donde es miembro; un teléfono solo al suyo.
 */
@Component
public class Access {
    public static final String MEMBER_HEADER = "X-Member-Id";

    private final JdbcClient jdbc;

    public Access(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Actúa como una persona concreta (usuario con Google, o teléfono + miembro con PIN). */
    public MemberContext member(Actor actor, UUID businessId, UUID memberHeader) {
        if (actor.isViewAs()) return viewAs(actor, businessId);
        if (actor.isDevice()) {
            if (!actor.deviceBusinessId().equals(businessId)) throw notFound();
            requireNotSuspended(businessId);
            if (memberHeader == null) {
                throw ApiException.badRequest("MEMBER_REQUIRED", MEMBER_HEADER + " header is required with a device token");
            }
            MemberContext ctx = jdbc.sql("SELECT id, role FROM member WHERE id = :m AND business_id = :b AND status = 'ACTIVE'")
                    .param("m", memberHeader).param("b", businessId)
                    .query((rs, n) -> new MemberContext(businessId, rs.getObject("id", UUID.class),
                            Role.valueOf(rs.getString("role")), null, actor.deviceId()))
                    .optional().orElseThrow(() -> ApiException.forbidden("MEMBER_NOT_ACTIVE", "Member not active in this business"));
            // Un teléfono nunca tiene más poder que quien lo vinculó: uno vinculado por un cajero solo actúa como cajero, aunque alguien
            // fabrique la cabecera X-Member-Id de un admin o del dueño (el PIN se valida en el teléfono, así que el servidor no puede fiarse de la cabecera).
            if (!ctx.role().atMost(deviceTrust(actor))) throw ApiException.forbidden("DEVICE_NOT_TRUSTED", "This phone cannot act with that role");
            return ctx;
        }
        return userMember(actor.userId(), businessId).orElseThrow(Access::notFound);
    }

    /**
     * Quién envía una tanda de la cola (`sync/push`). Como `member`, pero: la persona de la cabecera puede estar dada de baja (lo que hizo antes de la baja
     * se sigue aceptando, ver `actingFor`), puede no venir (la pantalla de PIN también envía lo pendiente) y el teléfono personal revocado por esa baja
     * puede terminar de enviar (`drainUntil`). Cada operación se atribuye después a SU persona con `actingFor`.
     */
    public record Pusher(MemberContext ctx, Role deviceTrust, java.time.Instant drainUntil, java.time.Instant deviceLinkedAt) {
        public Pusher(MemberContext ctx, Role deviceTrust, java.time.Instant drainUntil) { this(ctx, deviceTrust, drainUntil, null); }
    }

    /** Margen para relojes de teléfono (unos minutos de diferencia con el servidor son normales). */
    public static final java.time.Duration CLOCK_SKEW = java.time.Duration.ofMinutes(5);
    /** Lo más viejo que se acepta de alguien dado de baja: lo hecho sin conexión en las 48 h antes de la baja. */
    public static final java.time.Duration LATE_WINDOW = java.time.Duration.ofHours(48);

    public Pusher pusher(Actor actor, UUID businessId, UUID memberHeader) {
        if (!actor.isDevice() || actor.isViewAs()) return new Pusher(member(actor, businessId, memberHeader), null, null);
        if (!actor.deviceBusinessId().equals(businessId)) throw notFound();
        requireNotSuspended(businessId);
        Role trust = deviceTrust(actor);
        MemberContext ctx = memberHeader == null ? null : jdbc.sql("SELECT id, role FROM member WHERE id = :m AND business_id = :b")
                .param("m", memberHeader).param("b", businessId)
                .query((rs, n) -> new MemberContext(businessId, rs.getObject("id", UUID.class), Role.valueOf(rs.getString("role")), null, actor.deviceId()))
                .optional().orElseThrow(() -> ApiException.forbidden("MEMBER_NOT_ACTIVE", "Member not active in this business"));
        if (ctx != null && !ctx.role().atMost(trust)) throw ApiException.forbidden("DEVICE_NOT_TRUSTED", "This phone cannot act with that role");
        if (ctx == null) ctx = new MemberContext(businessId, null, Role.CASHIER, null, actor.deviceId());
        java.time.Instant linkedAt = jdbc.sql("SELECT linked_at FROM device WHERE id = :d").param("d", actor.deviceId())
                .query((rs, n) -> rs.getTimestamp(1).toInstant()).optional().orElse(null);
        return new Pusher(ctx, trust, actor.drainUntil(), linkedAt);
    }

    /**
     * La persona con la que se aplica UNA operación de la cola: la que la hizo (guardada en el teléfono al hacerla), no la que está activa al enviarla.
     * - Con sesión de usuario (web) solo puede ser quien envía.
     * - Con teléfono: debe ser del negocio y no tener más poder que el teléfono (`trust_role`); si fue dada de baja, solo vale lo hecho ANTES de la baja
     *   (`createdAt` es la hora del teléfono al hacerla). El teléfono revocado por esa baja solo envía lo hecho antes de la revocación.
     */
    public MemberContext actingFor(Pusher p, UUID memberId, java.time.Instant createdAt, java.util.Map<UUID, Object[]> cache) {
        UUID who = memberId != null ? memberId : p.ctx().memberId();
        if (who == null) throw ApiException.badRequest("MEMBER_REQUIRED", "Say who did this operation");
        if (p.deviceTrust() == null) {
            if (!who.equals(p.ctx().memberId())) throw ApiException.forbidden("MEMBER_MISMATCH", "A session can only act as itself");
            return p.ctx();
        }
        Object[] row = memberRow(p, who, cache);
        if (row.length == 0) throw ApiException.forbidden("MEMBER_NOT_ACTIVE", "Member not active in this business");
        Role role = (Role) row[0];
        if (!role.atMost(p.deviceTrust())) throw ApiException.forbidden("DEVICE_NOT_TRUSTED", "This phone cannot act with that role");
        if (p.drainUntil() != null && (createdAt == null || createdAt.isAfter(p.drainUntil()))) {
            throw ApiException.forbidden("ACCESS_DISABLED", "This phone was disabled before this operation");
        }
        if ("DISABLED".equals(row[1])) requirePlausibleBeforeDisable(p, (java.time.Instant) row[2], (String) row[3], createdAt);
        return new MemberContext(p.ctx().businessId(), who, role, null, p.ctx().deviceId());
    }

    private Object[] memberRow(Pusher p, UUID who, java.util.Map<UUID, Object[]> cache) {
        return cache.computeIfAbsent(who, id -> jdbc.sql("SELECT role, status, disabled_at, disable_snapshot FROM member WHERE id = :m AND business_id = :b")
                .param("m", id).param("b", p.ctx().businessId())
                .query((rs, n) -> new Object[] {Role.valueOf(rs.getString("role")), rs.getString("status"),
                        rs.getTimestamp("disabled_at") == null ? null : rs.getTimestamp("disabled_at").toInstant(), rs.getString("disable_snapshot")})
                .optional().orElse(new Object[0]));
    }

    /** Si quien hizo esta operación está dado de baja (ya validada por `actingFor`): lo que llega se acepta, pero se marca «llegó después de la baja». */
    public boolean isDisabled(Pusher p, UUID memberId, java.util.Map<UUID, Object[]> cache) {
        UUID who = memberId != null ? memberId : p.ctx().memberId();
        if (who == null || p.deviceTrust() == null) return false;
        Object[] row = memberRow(p, who, cache);
        return row.length > 0 && "DISABLED".equals(row[1]);
    }

    /**
     * Lo que alguien dado de baja hizo sin conexión ANTES de la baja se acepta (el dinero ya está en el cajón), pero la hora (`createdAt`) la pone el
     * teléfono, y un teléfono se puede atrasar a propósito. Por eso solo se acepta lo PLAUSIBLE, con datos que el servidor conoce:
     * 1. hecho antes de la baja (`createdAt` ≤ `disabled_at`);
     * 2. y no más de {@link #LATE_WINDOW} antes de la baja: nadie trabaja días sin conexión y lo manda justo después de la baja;
     * 3. y no antes de que este teléfono se vinculara (imposible);
     * 4. y, si al momento de la baja este teléfono había contactado al servidor informando CERO operaciones pendientes, no antes de ese contacto: todo lo
     *    hecho antes ya se habría enviado entonces (la foto de cada teléfono se guarda al dar de baja, `disable_snapshot`).
     * Todo con {@link #CLOCK_SKEW} de margen. Lo que no cumple se rechaza (visible en «Requiere atención» del teléfono) con el motivo.
     */
    private void requirePlausibleBeforeDisable(Pusher p, java.time.Instant disabledAt, String snapshot, java.time.Instant createdAt) {
        if (disabledAt == null || createdAt == null || createdAt.isAfter(disabledAt)) {
            throw ApiException.forbidden("MEMBER_NOT_ACTIVE", "Member was disabled before this operation").with("disabledAt", disabledAt == null ? null : disabledAt.toString());
        }
        if (createdAt.isBefore(disabledAt.minus(LATE_WINDOW))) throw lateRejected("TOO_OLD", disabledAt);
        if (p.deviceLinkedAt() != null && createdAt.isBefore(p.deviceLinkedAt().minus(CLOCK_SKEW))) throw lateRejected("BEFORE_LINK", disabledAt);
        java.time.Instant lastClean = lastCleanContact(snapshot, p.ctx().deviceId());
        if (lastClean != null && createdAt.isBefore(lastClean.minus(CLOCK_SKEW))) throw lateRejected("ALREADY_SYNCED", disabledAt);
    }

    private static ApiException lateRejected(String why, java.time.Instant disabledAt) {
        return ApiException.forbidden("LATE_OP_REJECTED", "This operation is not plausible for someone who was deactivated").with("reason", why).with("disabledAt", disabledAt.toString());
    }

    /** Último contacto del teléfono ANTES de la baja en que informó 0 pendientes (de la foto `disable_snapshot`: {deviceId: {lastSyncAt, pendingOps}}). */
    static java.time.Instant lastCleanContact(String snapshot, UUID deviceId) {
        if (snapshot == null || deviceId == null) return null;
        try {
            var node = tools.jackson.databind.json.JsonMapper.builder().build().readTree(snapshot).get(deviceId.toString());
            if (node == null || !node.hasNonNull("lastSyncAt")) return null;
            if (!node.hasNonNull("pendingOps") || node.get("pendingOps").asInt() != 0) return null;
            return java.time.Instant.parse(node.get("lastSyncAt").asString());
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static Role deviceTrust(Actor actor) {
        return actor.deviceTrust() == null ? Role.OWNER : Role.valueOf(actor.deviceTrust());
    }

    /** Un teléfono vinculado sin persona elegida (pantalla de entrada), o cualquier miembro. */
    public void businessAccess(Actor actor, UUID businessId) {
        if (actor.isViewAs()) {
            viewAs(actor, businessId);
            return;
        }
        if (actor.isDevice()) {
            if (!actor.deviceBusinessId().equals(businessId)) throw notFound();
            return;
        }
        userMember(actor.userId(), businessId).orElseThrow(Access::notFound);
    }

    public Optional<MemberContext> userMember(UUID userId, UUID businessId) {
        Optional<MemberContext> ctx = jdbc.sql("""
                        SELECT m.id, m.role FROM member m JOIN business b ON b.id = m.business_id
                         WHERE m.user_account_id = :u AND m.business_id = :b AND m.status = 'ACTIVE' AND b.status <> 'DELETING'
                        """)
                .param("u", userId).param("b", businessId)
                .query((rs, n) -> new MemberContext(businessId, rs.getObject("id", UUID.class),
                        Role.valueOf(rs.getString("role")), userId, null))
                .optional();
        ctx.ifPresent(c -> requireNotSuspended(businessId));
        return ctx;
    }

    /**
     * Un negocio suspendido por la plataforma (abuso, falta de pago acordada) no opera: ni la web ni los teléfonos. El dueño ve un mensaje claro.
     * No es un límite de plan (esos nunca bloquean la caja): lo decide una persona desde la consola y queda auditado.
     */
    private void requireNotSuspended(UUID businessId) {
        String status = jdbc.sql("SELECT status FROM business WHERE id = :b").param("b", businessId).query(String.class).optional().orElse("ACTIVE");
        if ("SUSPENDED".equals(status)) throw ApiException.forbidden("BUSINESS_SUSPENDED", "This business is suspended");
        // Un negocio en eliminación ya no opera en ningún lado (ni en los teléfonos del equipo): para ellos deja de existir.
        if ("DELETING".equals(status)) throw notFound();
    }

    /** Soporte mirando un negocio: contexto de dueño, pero la sesión ya está limitada a lectura por el filtro. Solo el negocio para el que se emitió. */
    private MemberContext viewAs(Actor actor, UUID businessId) {
        if (!businessId.equals(actor.viewAsBusinessId())) throw notFound();
        UUID owner = jdbc.sql("SELECT id FROM member WHERE business_id = :b AND role = 'OWNER' AND status = 'ACTIVE'").param("b", businessId).query(UUID.class).optional().orElseThrow(Access::notFound);
        return new MemberContext(businessId, owner, Role.OWNER, actor.userId(), null);
    }

    /** Un negocio ajeno no revela su existencia: siempre 404. */
    private static ApiException notFound() {
        return ApiException.notFound("BUSINESS_NOT_FOUND", "Business not found");
    }
}
