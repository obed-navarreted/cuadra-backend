package com.cuadra.api.device;

import com.cuadra.api.common.ApiException;
import com.cuadra.api.common.Audit;
import com.cuadra.api.security.TokenHasher;
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
    private static final Duration LINK_TTL = Duration.ofMinutes(10);

    private final JdbcClient jdbc;
    private final Audit audit;
    private final Clock clock;
    private final com.cuadra.api.plan.PlanService plans;

    public DeviceService(JdbcClient jdbc, Audit audit, Clock clock, com.cuadra.api.plan.PlanService plans) {
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
                        INSERT INTO device (id, business_id, kind, name, model, os_version, app_version, token_hash, cash_register_id, linked_by_member_id)
                        VALUES (:id, :b, 'SHARED', :n, :m, :o, :a, :h, :r, :by)
                        """)
                .param("id", deviceId).param("b", ctx.businessId())
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
                        INSERT INTO device (id, business_id, kind, name, model, os_version, app_version, token_hash, cash_register_id, linked_by_member_id)
                        VALUES (:id, :b, 'SHARED', :n, :m, :o, :a, :h, :r, :by)
                        """)
                .param("id", deviceId).param("b", ctx.businessId()).param("n", info.deviceName()).param("m", info.model()).param("o", info.osVersion())
                .param("a", info.appVersion()).param("h", TokenHasher.hash(token)).param("r", register, java.sql.Types.OTHER).param("by", ctx.memberId()).update();
        audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), null, "device.self_link", "device", deviceId, null);
        return new SelfLinked(deviceId, token, register);
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
        int n = jdbc.sql("UPDATE device SET revoked_at = :now, rev = nextval('change_rev_seq') WHERE id = :id AND business_id = :b AND revoked_at IS NULL")
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
