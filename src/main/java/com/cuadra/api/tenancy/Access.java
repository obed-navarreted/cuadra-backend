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
            // Cualquier persona del negocio usa cualquier teléfono del negocio con su PIN. Pero el PIN se comprueba en el teléfono, así que la cabecera
            // X-Member-Id sola no basta para actuar POR ENCIMA del rol base del teléfono (el de quien lo vinculó): para eso el servidor tiene que haber
            // comprobado el PIN de esa persona en este teléfono (permiso vigente, `verify-pin`). Sin él actúa con el rol base: vende y cobra igual, y lo
            // demás responde PIN_VERIFICATION_REQUIRED (ADR 0012, actualización 2026-10-01).
            return elevate(ctx, actor);
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
        if (ctx != null) ctx = elevate(ctx, actor);
        if (ctx == null) ctx = new MemberContext(businessId, null, Role.CASHIER, null, actor.deviceId());
        java.time.Instant linkedAt = jdbc.sql("SELECT linked_at FROM device WHERE id = :d").param("d", actor.deviceId())
                .query((rs, n) -> rs.getTimestamp(1).toInstant()).optional().orElse(null);
        return new Pusher(ctx, trust, actor.drainUntil(), linkedAt);
    }

    /**
     * La persona con la que se aplica UNA operación de la cola: la que la hizo (guardada en el teléfono al hacerla), no la que está activa al enviarla.
     * - Con sesión de usuario (web) solo puede ser quien envía.
     * - Con teléfono: debe ser del negocio; por encima del rol base del teléfono (`trust_role`) solo con un permiso de PIN verificado que cubra la hora de
     *   la operación (si no, con el rol base); si fue dada de baja, solo vale lo hecho ANTES de la baja
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
        if (p.drainUntil() != null && (createdAt == null || createdAt.isAfter(p.drainUntil()))) {
            throw ApiException.forbidden("ACCESS_DISABLED", "This phone was disabled before this operation");
        }
        if ("DISABLED".equals(row[1])) requirePlausibleBeforeDisable(p, (java.time.Instant) row[2], (String) row[3], createdAt);
        MemberContext acting = new MemberContext(p.ctx().businessId(), who, role, null, p.ctx().deviceId());
        if (role.atMost(p.deviceTrust()) || grantCovers(p, who, createdAt)) return acting;
        return acting.capped(p.deviceTrust());
    }

    // ---------- elevación por PIN verificado ----------

    /** Cuánto dura un permiso desde su último uso. */
    public static final java.time.Duration GRANT_TTL = java.time.Duration.ofHours(12);
    /** Un permiso solo se corre si le quedan menos de esto para llegar a {@link #GRANT_TTL} (no se escribe en cada petición). */
    private static final java.time.Duration GRANT_SLIDE_STEP = java.time.Duration.ofMinutes(5);

    /** Con teléfono: su rol real si no supera el rol base o si tiene un permiso VIGENTE en este teléfono (que se corre al usarse); si no, el rol base. */
    private MemberContext elevate(MemberContext ctx, Actor actor) {
        Role base = deviceTrust(actor);
        if (ctx.role().atMost(base)) return ctx;
        java.time.Instant now = java.time.Instant.now();
        // Correr el vencimiento solo si hace falta (casi todas las peticiones solo leen): se escribe como mucho cada pocos minutos.
        java.sql.Timestamp ts = java.sql.Timestamp.from(now);
        java.time.Instant expires = jdbc.sql("""
                        SELECT max(expires_at) FROM device_member_grant
                         WHERE device_id = :d AND member_id = :m AND business_id = :b AND revoked_at IS NULL AND expires_at > :now
                        """)
                .param("d", actor.deviceId()).param("m", ctx.memberId()).param("b", ctx.businessId()).param("now", ts)
                .query((rs, n) -> java.util.Optional.ofNullable(rs.getTimestamp(1)).map(java.sql.Timestamp::toInstant)).single().orElse(null);
        if (expires != null && expires.isBefore(now.plus(GRANT_TTL).minus(GRANT_SLIDE_STEP))) {
            jdbc.sql("""
                            UPDATE device_member_grant SET expires_at = :e
                             WHERE device_id = :d AND member_id = :m AND business_id = :b AND revoked_at IS NULL AND expires_at > :now
                            """)
                    .param("e", java.sql.Timestamp.from(now.plus(GRANT_TTL))).param("d", actor.deviceId()).param("m", ctx.memberId()).param("b", ctx.businessId())
                    .param("now", ts).update();
        }
        boolean alive = expires != null;
        return alive ? ctx : ctx.capped(base);
    }

    /**
     * ¿Hubo en ESTE teléfono un permiso de esta persona cuando hizo la operación (`createdAt`, hora del teléfono)? Lo hecho sin conexión mientras el permiso
     * valía se acepta después aunque ya venció. Plausibilidad: no antes de vincular el teléfono ni en el futuro. Además, si la persona vuelve a confirmar
     * su PIN en el teléfono (permiso vigente), lo que quedó en su cola sin permiso en las últimas {@link #LATE_WINDOW} se acepta: lo confirma ella.
     */
    private boolean grantCovers(Pusher p, UUID who, java.time.Instant createdAt) {
        if (createdAt == null || p.ctx().deviceId() == null) return false;
        java.time.Instant now = java.time.Instant.now();
        if (createdAt.isAfter(now.plus(CLOCK_SKEW))) return false;
        if (p.deviceLinkedAt() != null && createdAt.isBefore(p.deviceLinkedAt().minus(CLOCK_SKEW))) return false;
        java.sql.Timestamp at = java.sql.Timestamp.from(createdAt);
        return jdbc.sql("""
                        SELECT count(*) FROM device_member_grant
                         WHERE device_id = :d AND member_id = :m AND business_id = :b AND (
                               (granted_at - interval '5 minutes' <= :at AND :at <= LEAST(expires_at, COALESCE(revoked_at, expires_at)) + interval '5 minutes')
                            OR (revoked_at IS NULL AND expires_at > :now AND granted_at >= :at AND :at >= :lateFloor))
                        """)
                .param("d", p.ctx().deviceId()).param("m", who).param("b", p.ctx().businessId()).param("at", at)
                .param("now", java.sql.Timestamp.from(now)).param("lateFloor", java.sql.Timestamp.from(now.minus(LATE_WINDOW)))
                .query(Integer.class).single() > 0;
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

    /** El rol BASE del teléfono: el de quien lo vinculó. Por encima de él, solo con PIN verificado por el servidor en ese teléfono. */
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
