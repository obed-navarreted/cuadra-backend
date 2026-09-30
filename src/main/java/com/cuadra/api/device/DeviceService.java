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
     * El dueño o un admin con sesión de Google vincula el teléfono que tiene en la mano, sin código.
     * Es lo que pasa al crear un negocio: el primer teléfono queda listo para vender.
     */
    @Transactional
    public SelfLinked selfLink(MemberContext ctx, LinkRequestInfo info) {
        ctx.require(Permission.MANAGE_DEVICES);
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

    public record MemberLoginRequest(String businessCode, String username, String pin, String deviceName, String model, String osVersion, String appVersion) {}

    public record MemberLoginResult(UUID deviceId, String deviceToken, UUID businessId, UUID memberId, String memberName, String role, boolean pinMustChange) {}

    private static final int MAX_PIN_FAILURES = 5;
    private static final Duration PIN_LOCKOUT = Duration.ofMinutes(15);

    /**
     * Entrar con CÓDIGO DEL NEGOCIO + USUARIO (el nombre de la persona) + PIN, sin cuenta de Google: para admins y cajeros creados por el dueño.
     * El teléfono queda vinculado al negocio y con el poder máximo de esa persona (un cajero solo actúa como cajero). El dueño entra con Google.
     * Cualquier fallo (código, usuario o PIN) responde igual; 5 fallos seguidos bloquean a esa persona 15 minutos y avisan al dueño y a los admins.
     */
    // Los fallos también se guardan (contador y bloqueo de PIN): la excepción que corta la petición no debe deshacerlos.
    @Transactional(noRollbackFor = ApiException.class)
    public MemberLoginResult memberLogin(MemberLoginRequest in, String userAgent) {
        String code = in.businessCode() == null ? "" : in.businessCode().replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        String username = in.username() == null ? "" : in.username().trim();
        Instant now = clock.instant();
        UUID businessId = jdbc.sql("SELECT id FROM business WHERE access_code = :c AND status = 'ACTIVE'").param("c", code).query(UUID.class).optional().orElse(null);
        var member = businessId == null ? null : jdbc.sql("""
                        SELECT id, display_name, role, pin_hash, pin_must_change, pin_failed_count, pin_locked_until
                          FROM member WHERE business_id = :b AND lower(btrim(display_name)) = lower(btrim(:n)) AND status = 'ACTIVE' AND pin_hash IS NOT NULL
                         ORDER BY created_at LIMIT 1 FOR UPDATE
                        """)
                .param("b", businessId).param("n", username)
                .query((rs, n) -> new Object[] {rs.getObject("id", UUID.class), rs.getString("display_name"), rs.getString("role"), rs.getString("pin_hash"),
                        rs.getBoolean("pin_must_change"), rs.getInt("pin_failed_count"), rs.getTimestamp("pin_locked_until")})
                .optional().orElse(null);
        if (member != null && member[6] != null && ((Timestamp) member[6]).toInstant().isAfter(now)) {
            throw ApiException.tooMany("LOCKED", "Too many failed attempts; try again later");
        }
        boolean pinOk = in.pin() != null && pinEncoder.matches(in.pin(), member == null ? dummyPinHash : (String) member[3]);
        if (member == null || !pinOk) {
            if (member != null) {
                int failures = (Integer) member[5] + 1;
                boolean lock = failures >= MAX_PIN_FAILURES;
                jdbc.sql("UPDATE member SET pin_failed_count = :f, pin_locked_until = :u WHERE id = :id").param("f", lock ? 0 : failures)
                        .param("u", lock ? Timestamp.from(now.plus(PIN_LOCKOUT)) : null, java.sql.Types.TIMESTAMP).param("id", member[0]).update();
                if (lock) {
                    notifications.notify(businessId, com.cuadra.api.notification.NotificationService.Type.PIN_LOCKOUT, java.util.Map.of("memberName", (String) member[1]), null, null,
                            "PIN_LOCKOUT:" + member[0] + ":" + now.getEpochSecond() / 900, "cuentiva://equipo");
                }
            }
            throw ApiException.unauthorized("INVALID_CREDENTIALS", "Invalid business code, user or PIN");
        }
        Role role = Role.valueOf((String) member[2]);
        if (role == Role.OWNER) throw ApiException.forbidden("OWNER_USES_GOOGLE", "The owner signs in with Google");
        jdbc.sql("UPDATE member SET pin_failed_count = 0, pin_locked_until = NULL WHERE id = :id").param("id", member[0]).update();
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
            audit.log(businessId, (UUID) member[0], null, deviceId, "device.revoke", "device", d, "replaced");
        }
        audit.log(businessId, (UUID) member[0], null, deviceId, "device.member_login", "device", deviceId, role.name());
        return new MemberLoginResult(deviceId, token, businessId, (UUID) member[0], (String) member[1], role.name(), (Boolean) member[4]);
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
        audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "device.revoke", "device", deviceId, null);
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
