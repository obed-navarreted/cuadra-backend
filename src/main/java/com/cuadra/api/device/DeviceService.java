package com.cuadra.api.device;

import com.cuadra.api.common.ApiException;
import com.cuadra.api.common.Audit;
import com.cuadra.api.security.TokenHasher;
import com.cuadra.api.tenancy.Role;
import com.cuadra.api.tenancy.MemberContext;
import com.cuadra.api.tenancy.Role.Permission;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vinculación de un teléfono a un negocio: el teléfono nuevo muestra un código, un dueño/admin lo reclama
 * y el teléfono recoge su token de dispositivo una sola vez.
 */
@Service
public class DeviceService {
    /** Teléfonos personales activos por persona (entrando con código del negocio, usuario y PIN). */
    public static final int MAX_PERSONAL_PHONES = 2;
    private static final Duration LINK_TTL = Duration.ofMinutes(10);

    private final JdbcClient jdbc;
    private final Audit audit;
    private final Clock clock;
    private final com.cuadra.api.plan.PlanService plans;
    private final org.springframework.security.crypto.password.PasswordEncoder pinEncoder;
    private final com.cuadra.api.notification.NotificationService notifications;
    private final String dummyPinHash;

    public DeviceService(JdbcClient jdbc, Audit audit, Clock clock, com.cuadra.api.plan.PlanService plans, org.springframework.security.crypto.password.PasswordEncoder pinEncoder,
                         com.cuadra.api.notification.NotificationService notifications) {
        this.pinEncoder = pinEncoder;
        this.notifications = notifications;
        this.dummyPinHash = pinEncoder.encode(UUID.randomUUID().toString());
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        this.plans = plans;
    }

    public record LinkRequestInfo(String deviceName, String model, String osVersion, String appVersion) {}

    public record LinkRequestCreated(String code, String pollSecret, Instant expiresAt) {}

    public record LinkStatus(String status, Instant expiresAt, String deviceToken, UUID deviceId, UUID businessId) {}

    public record DeviceView(UUID id, String name, String model, String appVersion, UUID cashRegisterId, String cashRegisterName, Instant linkedAt,
                             Instant lastSeenAt, Instant lastSyncAt, int pendingOps, boolean revoked) {}

    public LinkRequestCreated createLinkRequest(LinkRequestInfo info) {
        String secret = TokenHasher.newToken();
        Instant expires = clock.instant().plus(LINK_TTL);
        // Un choque de código es improbable (31^6) pero se reintenta por si acaso.
        for (int attempt = 0; attempt < 5; attempt++) {
            String code = TokenHasher.newCode(6);
            int inserted = jdbc.sql("""
                            INSERT INTO device_link_request (code, device_name, model, os_version, app_version, poll_secret_hash, expires_at)
                            VALUES (:c, :n, :m, :o, :a, :h, :e) ON CONFLICT (code) DO NOTHING
                            """)
                    .param("c", code).param("n", info.deviceName()).param("m", info.model()).param("o", info.osVersion())
                    .param("a", info.appVersion()).param("h", TokenHasher.hash(secret)).param("e", Timestamp.from(expires)).update();
            if (inserted == 1) return new LinkRequestCreated(code, secret, expires);
        }
        throw new IllegalStateException("No se pudo generar un código de vinculación");
    }

    @Transactional
    public LinkStatus poll(String code, String secret) {
        var row = jdbc.sql("""
                        SELECT expires_at, device_id, pending_token, poll_secret_hash, claimed_at,
                               (SELECT business_id FROM device d WHERE d.id = r.device_id) AS business_id
                          FROM device_link_request r WHERE code = :c
                        """)
                .param("c", code.toUpperCase())
                .query((rs, n) -> new Object[] {rs.getTimestamp("expires_at").toInstant(), rs.getObject("device_id", UUID.class),
                        rs.getString("pending_token"), rs.getString("poll_secret_hash"), rs.getTimestamp("claimed_at"),
                        rs.getObject("business_id", UUID.class)})
                .optional().orElseThrow(() -> ApiException.notFound("LINK_REQUEST_NOT_FOUND", "Link request not found"));
        if (secret == null || !TokenHasher.hash(secret).equals(row[3])) {
            throw ApiException.notFound("LINK_REQUEST_NOT_FOUND", "Link request not found");
        }
        Instant expires = (Instant) row[0];
        if (row[4] == null) {
            return clock.instant().isAfter(expires)
                    ? new LinkStatus("EXPIRED", expires, null, null, null)
                    : new LinkStatus("PENDING", expires, null, null, null);
        }
        String token = (String) row[2];
        if (token == null) return new LinkStatus("COLLECTED", expires, null, (UUID) row[1], (UUID) row[5]);
        // El token se entrega una sola vez y no queda guardado en claro.
        jdbc.sql("UPDATE device_link_request SET pending_token = NULL WHERE code = :c").param("c", code.toUpperCase()).update();
        return new LinkStatus("CLAIMED", expires, token, (UUID) row[1], (UUID) row[5]);
    }

    @Transactional
    public DeviceView claim(MemberContext ctx, String code, String name, UUID cashRegisterId) {
        ctx.require(Permission.MANAGE_DEVICES);
        plans.requireRoom(ctx.businessId(), com.cuadra.api.plan.PlanService.Feature.DEVICES);
        var req = jdbc.sql("""
                        SELECT device_name, model, os_version, app_version FROM device_link_request
                         WHERE code = :c AND claimed_at IS NULL AND expires_at > :now FOR UPDATE
                        """)
                .param("c", code.toUpperCase()).param("now", Timestamp.from(clock.instant()))
                .query((rs, n) -> new String[] {rs.getString("device_name"), rs.getString("model"), rs.getString("os_version"), rs.getString("app_version")})
                .optional().orElseThrow(() -> ApiException.notFound("LINK_CODE_INVALID", "Code invalid or expired"));

        UUID register = cashRegisterId != null ? cashRegisterId : jdbc.sql("SELECT id FROM cash_register WHERE business_id = :b AND active ORDER BY name LIMIT 1")
                .param("b", ctx.businessId()).query(UUID.class).optional().orElse(null);
        if (cashRegisterId != null && jdbc.sql("SELECT count(*) FROM cash_register WHERE id = :r AND business_id = :b")
                .param("r", cashRegisterId).param("b", ctx.businessId()).query(Integer.class).single() == 0) {
            throw ApiException.badRequest("INVALID_CASH_REGISTER", "Cash register not found");
        }

        UUID deviceId = UUID.randomUUID();
        String token = TokenHasher.newToken();
        jdbc.sql("""
                        INSERT INTO device (id, business_id, kind, name, model, os_version, app_version, token_hash, cash_register_id, linked_by_member_id, trust_role)
                        VALUES (:id, :b, 'SHARED', :n, :m, :o, :a, :h, :r, :by, :trust)
                        """)
                .param("id", deviceId).param("b", ctx.businessId()).param("trust", ctx.role().name())
                .param("n", name == null || name.isBlank() ? req[0] : name.trim()).param("m", req[1]).param("o", req[2]).param("a", req[3])
                .param("h", TokenHasher.hash(token)).param("r", register).param("by", ctx.memberId()).update();
        jdbc.sql("UPDATE device_link_request SET device_id = :d, pending_token = :t, claimed_at = :now WHERE code = :c")
                .param("d", deviceId).param("t", token).param("now", Timestamp.from(clock.instant())).param("c", code.toUpperCase()).update();
        audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "device.claim", "device", deviceId, null);
        return get(ctx.businessId(), deviceId);
    }

    public record SelfLinked(UUID deviceId, String deviceToken, UUID cashRegisterId) {}

    /**
     * Una persona del negocio con sesión de Google vincula el teléfono que tiene en la mano, sin código (el rol base del teléfono es el suyo).
     * Es lo que pasa al crear un negocio: el primer teléfono queda listo para vender.
     */
    @Transactional
    public SelfLinked selfLink(MemberContext ctx, LinkRequestInfo info) {
        // Cualquier persona del negocio con Google puede usar este teléfono para el negocio (ADR 0012, 2026-10-01): queda con SU rol como rol base.
        if (ctx.userId() == null) throw ApiException.forbidden("GOOGLE_REQUIRED", "Only a Google session can link this phone");
        plans.requireRoom(ctx.businessId(), com.cuadra.api.plan.PlanService.Feature.DEVICES);
        UUID register = jdbc.sql("SELECT id FROM cash_register WHERE business_id = :b AND active ORDER BY name LIMIT 1")
                .param("b", ctx.businessId()).query(UUID.class).optional().orElse(null);
        UUID deviceId = UUID.randomUUID();
        String token = TokenHasher.newToken();
        jdbc.sql("""
                        INSERT INTO device (id, business_id, kind, name, model, os_version, app_version, token_hash, cash_register_id, linked_by_member_id, trust_role)
                        VALUES (:id, :b, 'SHARED', :n, :m, :o, :a, :h, :r, :by, :trust)
                        """)
                .param("id", deviceId).param("b", ctx.businessId()).param("trust", ctx.role().name()).param("n", info.deviceName()).param("m", info.model()).param("o", info.osVersion())
                .param("a", info.appVersion()).param("h", TokenHasher.hash(token)).param("r", register, java.sql.Types.OTHER).param("by", ctx.memberId()).update();
        audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), null, "device.self_link", "device", deviceId, null);
        return new SelfLinked(deviceId, token, register);
    }

    public record MemberLoginRequest(String businessCode, String pin, String deviceName, String model, String osVersion, String appVersion) {}

    public record MemberLoginResult(UUID deviceId, String deviceToken, UUID businessId, UUID memberId, String memberName, String role, boolean pinMustChange) {}

    /** Fallos de código+PIN que aguanta un negocio dentro de la ventana antes de pausar la entrada con código. */
    static final int MAX_BUSINESS_FAILURES = 10;
    private static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);
    private static final Duration BUSINESS_LOCKOUT = Duration.ofMinutes(15);

    /**
     * Entrar con CÓDIGO DEL NEGOCIO + PIN, sin cuenta de Google ni usuario: el PIN identifica a la persona (no se repite entre las personas activas
     * del negocio). Se comprueba el PIN contra TODAS las personas activas con PIN (≤ 10), siempre todas, para que el tiempo no delate nada.
     * El teléfono queda vinculado al negocio con el rol de esa persona como rol BASE (quien tenga más rol verifica su PIN en él). El dueño entra con Google.
     * Cualquier fallo (código o PIN) responde igual. Como no se sabe quién se equivoca, el bloqueo es por NEGOCIO: 10 fallos en 15 minutos
     * pausan la entrada con código de ese negocio 15 minutos (429 LOCKED) y avisan una vez al dueño y a los admins. Además, 10 intentos/min por IP.
     */
    // Los fallos también se guardan (contador y bloqueo): la excepción que corta la petición no debe deshacerlos.
    @Transactional(noRollbackFor = ApiException.class)
    public MemberLoginResult memberLogin(MemberLoginRequest in, String userAgent) {
        String code = in.businessCode() == null ? "" : in.businessCode().replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        Instant now = clock.instant();
        var business = jdbc.sql("""
                        SELECT id, member_login_failures, member_login_window_start, member_login_locked_until
                          FROM business WHERE access_code = :c AND status = 'ACTIVE' FOR UPDATE
                        """).param("c", code)
                .query((rs, n) -> new Object[] {rs.getObject("id", UUID.class), rs.getInt("member_login_failures"), rs.getTimestamp("member_login_window_start"),
                        rs.getTimestamp("member_login_locked_until")})
                .optional().orElse(null);
        if (business != null && business[3] != null && ((Timestamp) business[3]).toInstant().isAfter(now)) {
            throw ApiException.tooMany("LOCKED", "Too many failed attempts; try again later");
        }
        UUID businessId = business == null ? null : (UUID) business[0];
        List<Object[]> candidates = businessId == null ? List.of() : jdbc.sql("""
                        SELECT id, display_name, role, pin_hash, pin_must_change
                          FROM member WHERE business_id = :b AND status = 'ACTIVE' AND pin_hash IS NOT NULL ORDER BY created_at
                        """)
                .param("b", businessId)
                .query((rs, n) -> new Object[] {rs.getObject("id", UUID.class), rs.getString("display_name"), rs.getString("role"), rs.getString("pin_hash"),
                        rs.getBoolean("pin_must_change")})
                .list();
        String pin = in.pin() == null ? "" : in.pin().trim();
        Object[] member = matchPin(pin, candidates);
        if (member == null) {
            if (businessId != null) recordBusinessFailure(businessId, business, now);
            throw ApiException.unauthorized("INVALID_CREDENTIALS", "Invalid business code or PIN");
        }
        Role role = Role.valueOf((String) member[2]);
        if (role == Role.OWNER) throw ApiException.forbidden("OWNER_USES_GOOGLE", "The owner signs in with Google");
        jdbc.sql("UPDATE business SET member_login_failures = 0, member_login_window_start = NULL WHERE id = :b").param("b", businessId).update();
        plans.requireRoom(businessId, com.cuadra.api.plan.PlanService.Feature.DEVICES);
        UUID register = jdbc.sql("SELECT id FROM cash_register WHERE business_id = :b AND active ORDER BY name LIMIT 1").param("b", businessId).query(UUID.class).optional().orElse(null);
        UUID deviceId = UUID.randomUUID();
        String token = TokenHasher.newToken();
        String deviceName = in.deviceName() == null || in.deviceName().isBlank() ? "Teléfono de " + member[1] : in.deviceName().trim();
        jdbc.sql("""
                        INSERT INTO device (id, business_id, kind, name, model, os_version, app_version, token_hash, cash_register_id, linked_by_member_id, trust_role)
                        VALUES (:id, :b, 'PERSONAL', :n, :m, :o, :a, :h, :r, :by, :trust)
                        """)
                .param("id", deviceId).param("b", businessId).param("n", deviceName).param("m", in.model()).param("o", in.osVersion()).param("a", in.appVersion())
                .param("h", TokenHasher.hash(token)).param("r", register, java.sql.Types.OTHER).param("by", member[0]).param("trust", role.name()).update();
        // Máximo 2 teléfonos personales activos por persona: el más viejo se cierra (y suelta las cuentas apartadas que retenía).
        List<UUID> replaced = jdbc.sql("""
                        UPDATE device SET revoked_at = :now, revoked_reason = 'REPLACED', rev = nextval('change_rev_seq') WHERE id IN (
                            SELECT id FROM device WHERE business_id = :b AND linked_by_member_id = :m AND kind = 'PERSONAL' AND revoked_at IS NULL
                             ORDER BY linked_at DESC, id OFFSET :keep) RETURNING id
                        """).param("now", Timestamp.from(now)).param("b", businessId).param("m", member[0]).param("keep", MAX_PERSONAL_PHONES).query(UUID.class).list();
        for (UUID d : replaced) {
            jdbc.sql("UPDATE sale SET locked_by_device_id = NULL, locked_until = NULL WHERE locked_by_device_id = :d").param("d", d).update();
            revokeGrants(jdbc, businessId, d, null);
            audit.log(businessId, (UUID) member[0], null, deviceId, "device.revoke", "device", d, "replaced");
        }
        audit.log(businessId, (UUID) member[0], null, deviceId, "device.member_login", "device", deviceId, role.name());
        return new MemberLoginResult(deviceId, token, businessId, (UUID) member[0], (String) member[1], role.name(), (Boolean) member[4]);
    }

    /** Compara el PIN con cada persona (todas, en paralelo: bcrypt es lento a propósito). Si no hay nadie, igual gasta un bcrypt. */
    private Object[] matchPin(String pin, List<Object[]> candidates) {
        if (!pin.matches("\\d{5}") || candidates.isEmpty()) {
            pinEncoder.matches(pin.isEmpty() ? "0" : pin, dummyPinHash);
            return null;
        }
        return candidates.parallelStream().filter(m -> pinEncoder.matches(pin, (String) m[3])).findFirst().orElse(null);
    }

    private void recordBusinessFailure(UUID businessId, Object[] business, Instant now) {
        Timestamp windowStart = (Timestamp) business[2];
        boolean inWindow = windowStart != null && windowStart.toInstant().plus(FAILURE_WINDOW).isAfter(now);
        int failures = (inWindow ? (Integer) business[1] : 0) + 1;
        boolean lock = failures >= MAX_BUSINESS_FAILURES;
        jdbc.sql("UPDATE business SET member_login_failures = :f, member_login_window_start = :w, member_login_locked_until = :u WHERE id = :b")
                .param("f", lock ? 0 : failures).param("w", lock ? null : Timestamp.from(inWindow ? windowStart.toInstant() : now), java.sql.Types.TIMESTAMP)
                .param("u", lock ? Timestamp.from(now.plus(BUSINESS_LOCKOUT)) : null, java.sql.Types.TIMESTAMP).param("b", businessId).update();
        if (lock) {
            // Sin nombre: nadie sabe quién se equivocó. El aviso sale una vez por bloqueo.
            notifications.notify(businessId, com.cuadra.api.notification.NotificationService.Type.PIN_LOCKOUT, java.util.Map.of("scope", "BUSINESS"), null, null,
                    "PIN_LOCKOUT:BUSINESS:" + businessId + ":" + now.getEpochSecond(), "cuentiva://equipo");
            audit.log(businessId, null, null, null, "auth.member_login_locked", "business", businessId, null);
        }
    }

    public List<DeviceView> list(UUID businessId) {
        return jdbc.sql("""
                        SELECT d.id, d.name, d.model, d.app_version, d.cash_register_id, r.name AS register_name, d.linked_at, d.last_seen_at, d.last_sync_at, d.pending_ops, d.revoked_at
                          FROM device d LEFT JOIN cash_register r ON r.id = d.cash_register_id WHERE d.business_id = :b ORDER BY d.linked_at DESC
                        """)
                .param("b", businessId).query((rs, n) -> map(rs)).list();
    }

    public DeviceView get(UUID businessId, UUID deviceId) {
        return list(businessId).stream().filter(d -> d.id().equals(deviceId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "Device not found"));
    }

    @Transactional
    public void revoke(MemberContext ctx, UUID deviceId) {
        ctx.require(Permission.MANAGE_DEVICES);
        int n = jdbc.sql("UPDATE device SET revoked_at = :now, revoked_reason = 'MANUAL', rev = nextval('change_rev_seq') WHERE id = :id AND business_id = :b AND revoked_at IS NULL")
                .param("now", Timestamp.from(clock.instant())).param("id", deviceId).param("b", ctx.businessId()).update();
        if (n == 0) throw ApiException.notFound("DEVICE_NOT_FOUND", "Device not found");
        // Un teléfono revocado no puede seguir reteniendo cuentas apartadas: se liberan para que otro las complete sin esperar los 10 min.
        jdbc.sql("UPDATE sale SET locked_by_device_id = NULL, locked_until = NULL WHERE locked_by_device_id = :d").param("d", deviceId).update();
        revokeGrants(jdbc, ctx.businessId(), deviceId, null);
        audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "device.revoke", "device", deviceId, null);
    }

    /** Revoca los permisos de PIN verificado de un teléfono (`deviceId`), de una persona (`memberId`) o de ambos. */
    public static void revokeGrants(JdbcClient jdbc, UUID businessId, UUID deviceId, UUID memberId) {
        jdbc.sql("""
                        UPDATE device_member_grant SET revoked_at = now()
                         WHERE business_id = :b AND revoked_at IS NULL AND (CAST(:d AS uuid) IS NULL OR device_id = CAST(:d AS uuid)) AND (CAST(:m AS uuid) IS NULL OR member_id = CAST(:m AS uuid))
                        """)
                .param("b", businessId).param("d", deviceId, java.sql.Types.OTHER).param("m", memberId, java.sql.Types.OTHER).update();
    }

    // ---------- elevación por PIN verificado (ADR 0012, actualización 2026-10-01) ----------

    /** Fallos seguidos de PIN de una persona antes de bloquearla (en este flujo; también cuentan para el bloqueo del negocio). */
    static final int MAX_MEMBER_FAILURES = 5;
    private static final Duration MEMBER_LOCKOUT = Duration.ofMinutes(15);

    public record Grant(UUID memberId, Instant grantedAt, Instant expiresAt) {}

    public record DeviceSelf(UUID deviceId, UUID businessId, String kind, String baseRole, List<Grant> grants) {}

    public record VerifiedPin(UUID memberId, String role, String baseRole, Instant grantedAt, Instant expiresAt) {}

    /** Lo que el teléfono necesita saber de sí mismo: su rol base y los permisos vigentes (para decidir cuándo pedir la verificación). */
    public DeviceSelf self(UUID deviceId, UUID businessId) {
        Object[] d = jdbc.sql("SELECT kind, trust_role FROM device WHERE id = :d AND business_id = :b").param("d", deviceId).param("b", businessId)
                .query((rs, n) -> new Object[] {rs.getString(1), rs.getString(2)}).optional()
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "Device not found"));
        List<Grant> grants = jdbc.sql("""
                        SELECT member_id, min(granted_at), max(expires_at) FROM device_member_grant
                         WHERE device_id = :d AND business_id = :b AND revoked_at IS NULL AND expires_at > :now GROUP BY member_id
                        """).param("d", deviceId).param("b", businessId).param("now", Timestamp.from(clock.instant()))
                .query((rs, n) -> new Grant(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant(), rs.getTimestamp(3).toInstant())).list();
        return new DeviceSelf(deviceId, businessId, (String) d[0], (String) d[1], grants);
    }

    /**
     * El servidor comprueba el PIN de una persona en ESTE teléfono (bcrypt) y deja un permiso corto (12 h, que se corre al usarse) para que actúe con su rol
     * aunque el teléfono lo haya vinculado alguien con menos rol. Bloqueos: 5 fallos seguidos de esa persona la bloquean 15 minutos (y avisan al dueño), y
     * cada fallo cuenta también para el bloqueo de entrada con código del negocio (10 en 15 minutos). Además, límite por teléfono y minuto (filtro).
     */
    @Transactional(noRollbackFor = ApiException.class)
    public VerifiedPin verifyPin(UUID deviceId, UUID businessId, UUID memberId, String rawPin) {
        Instant now = clock.instant();
        var business = jdbc.sql("""
                        SELECT id, member_login_failures, member_login_window_start, member_login_locked_until, status FROM business WHERE id = :b FOR UPDATE
                        """).param("b", businessId)
                .query((rs, n) -> new Object[] {rs.getObject("id", UUID.class), rs.getInt("member_login_failures"), rs.getTimestamp("member_login_window_start"),
                        rs.getTimestamp("member_login_locked_until"), rs.getString("status")})
                .optional().orElseThrow(() -> ApiException.notFound("BUSINESS_NOT_FOUND", "Business not found"));
        if ("SUSPENDED".equals(business[4])) throw ApiException.forbidden("BUSINESS_SUSPENDED", "This business is suspended");
        if (!"ACTIVE".equals(business[4])) throw ApiException.notFound("BUSINESS_NOT_FOUND", "Business not found");
        if (business[3] != null && ((Timestamp) business[3]).toInstant().isAfter(now)) throw ApiException.tooMany("LOCKED", "Too many failed attempts; try again later");
        String baseRole = jdbc.sql("SELECT trust_role FROM device WHERE id = :d AND business_id = :b AND revoked_at IS NULL").param("d", deviceId).param("b", businessId)
                .query(String.class).optional().orElseThrow(() -> ApiException.unauthorized("UNAUTHENTICATED", "Unauthorized"));
        var member = jdbc.sql("""
                        SELECT display_name, role, status, pin_hash, pin_failed_count, pin_locked_until FROM member WHERE id = :m AND business_id = :b FOR UPDATE
                        """).param("m", memberId).param("b", businessId)
                .query((rs, n) -> new Object[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getTimestamp(6)})
                .optional().orElse(null);
        if (member == null || !"ACTIVE".equals(member[2])) throw ApiException.forbidden("MEMBER_NOT_ACTIVE", "Member not active in this business");
        if (member[5] != null && ((Timestamp) member[5]).toInstant().isAfter(now)) {
            throw ApiException.tooMany("LOCKED", "Too many failed attempts; try again later").with("retryAfterSeconds", Duration.between(now, ((Timestamp) member[5]).toInstant()).toSeconds());
        }
        String pin = rawPin == null ? "" : rawPin.trim();
        boolean ok;
        if (member[3] != null && pin.matches("\\d{5}")) ok = pinEncoder.matches(pin, (String) member[3]);
        else { pinEncoder.matches(pin.isEmpty() ? "0" : pin, dummyPinHash); ok = false; }   // mismo tiempo que un intento real
        if (!ok) {
            int failures = (Integer) member[4] + 1;
            boolean lock = failures >= MAX_MEMBER_FAILURES;
            jdbc.sql("UPDATE member SET pin_failed_count = :f, pin_locked_until = :u WHERE id = :m AND business_id = :b")
                    .param("f", lock ? 0 : failures).param("u", lock ? Timestamp.from(now.plus(MEMBER_LOCKOUT)) : null, java.sql.Types.TIMESTAMP)
                    .param("m", memberId).param("b", businessId).update();
            if (lock) {
                notifications.notify(businessId, com.cuadra.api.notification.NotificationService.Type.PIN_LOCKOUT,
                        java.util.Map.of("scope", "MEMBER", "memberId", memberId.toString(), "memberName", member[0]), null, null,
                        "PIN_LOCKOUT:MEMBER:" + memberId + ":" + now.getEpochSecond(), "cuentiva://equipo");
                audit.log(businessId, memberId, null, deviceId, "auth.pin_locked", "member", memberId, null);
            }
            recordBusinessFailure(businessId, business, now);
            if (lock) throw ApiException.tooMany("LOCKED", "Too many failed attempts; try again later").with("retryAfterSeconds", MEMBER_LOCKOUT.toSeconds());
            throw ApiException.unauthorized("INVALID_CREDENTIALS", "Wrong PIN").with("attemptsLeft", MAX_MEMBER_FAILURES - failures);
        }
        jdbc.sql("UPDATE member SET pin_failed_count = 0, pin_locked_until = NULL WHERE id = :m AND business_id = :b").param("m", memberId).param("b", businessId).update();
        Instant expires = now.plus(com.cuadra.api.tenancy.Access.GRANT_TTL);
        // Un permiso vigente se renueva; si no, uno nuevo (las ventanas viejas se conservan: cubren lo que se hizo sin conexión dentro de ellas).
        Instant grantedAt = jdbc.sql("""
                        UPDATE device_member_grant SET expires_at = :e WHERE device_id = :d AND member_id = :m AND business_id = :b AND revoked_at IS NULL AND expires_at > :now
                        RETURNING granted_at
                        """).param("e", Timestamp.from(expires)).param("d", deviceId).param("m", memberId).param("b", businessId).param("now", Timestamp.from(now))
                .query((rs, n) -> rs.getTimestamp(1).toInstant()).list().stream().min(Instant::compareTo).orElse(null);
        if (grantedAt == null) {
            grantedAt = now;
            jdbc.sql("""
                            INSERT INTO device_member_grant (id, device_id, member_id, business_id, granted_at, expires_at) VALUES (:id, :d, :m, :b, :g, :e)
                            """).param("id", UUID.randomUUID()).param("d", deviceId).param("m", memberId).param("b", businessId)
                    .param("g", Timestamp.from(now)).param("e", Timestamp.from(expires)).update();
            audit.log(businessId, memberId, null, deviceId, "device.pin_verified", "device", deviceId, (String) member[1]);
        }
        return new VerifiedPin(memberId, (String) member[1], baseRole, grantedAt, expires);
    }

    private static DeviceView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp seen = rs.getTimestamp("last_seen_at");
        Timestamp sync = rs.getTimestamp("last_sync_at");
        return new DeviceView(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("model"), rs.getString("app_version"),
                rs.getObject("cash_register_id", UUID.class), rs.getString("register_name"), rs.getTimestamp("linked_at").toInstant(),
                seen == null ? null : seen.toInstant(), sync == null ? null : sync.toInstant(), rs.getInt("pending_ops"),
                rs.getTimestamp("revoked_at") != null);
    }
}
