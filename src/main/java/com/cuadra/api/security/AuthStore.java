package com.cuadra.api.security;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Búsqueda de sesiones y dispositivos por hash de token. */
@Repository
public class AuthStore {
    private static final Duration SESSION_TOUCH = Duration.ofMinutes(5);
    private static final Duration DEVICE_TOUCH = Duration.ofMinutes(1);

    private final JdbcClient jdbc;
    private final Clock clock;

    public AuthStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    private record SessionRow(UUID id, UUID userId, Instant seen, UUID viewAs) {}

    private record DeviceRow(UUID id, UUID businessId, Instant seen, String trust, Instant revokedAt, String revokedReason) {}

    /**
     * IMPORTANTE: primero se lee y se suelta la conexión; recién después se actualiza `last_seen`. Hacerlo dentro del mapeo de filas pedía una
     * SEGUNDA conexión mientras la primera seguía tomada: con más peticiones simultáneas que conexiones en el pool, todas se quedaban esperando
     * a la vez (interbloqueo del pool, visto en la prueba de carga como 401 tras 5 s).
     */
    public Optional<Actor> findUserSession(String token) {
        Instant now = clock.instant();
        Optional<SessionRow> row = jdbc.sql("""
                        SELECT s.id, s.user_account_id, s.last_seen_at, s.view_as_business_id
                          FROM auth_session s JOIN user_account u ON u.id = s.user_account_id
                         WHERE s.token_hash = :h AND s.revoked_at IS NULL AND s.expires_at > :now AND u.deleted_at IS NULL
                        """)
                .param("h", TokenHasher.hash(token))
                .param("now", Timestamp.from(now))
                .query((rs, n) -> new SessionRow(rs.getObject("id", UUID.class), rs.getObject("user_account_id", UUID.class),
                        rs.getTimestamp("last_seen_at").toInstant(), rs.getObject("view_as_business_id", UUID.class)))
                .optional();
        row.ifPresent(r -> {
            if (Duration.between(r.seen(), now).compareTo(SESSION_TOUCH) > 0) {
                jdbc.sql("UPDATE auth_session SET last_seen_at = :now WHERE id = :id").param("now", Timestamp.from(now)).param("id", r.id()).update();
            }
        });
        return row.map(r -> r.viewAs() == null ? Actor.user(r.userId(), r.id()) : Actor.viewAs(r.userId(), r.id(), r.viewAs()));
    }

    public Optional<Actor> findDevice(String token) {
        Instant now = clock.instant();
        // Un teléfono revocado no entra, SALVO el personal de alguien dado de baja: ese solo puede terminar de enviar lo pendiente (ver Actor.draining).
        Optional<DeviceRow> row = jdbc.sql("""
                        SELECT id, business_id, last_seen_at, trust_role, revoked_at, revoked_reason FROM device
                         WHERE token_hash = :h AND (revoked_at IS NULL OR revoked_reason = 'MEMBER_DISABLED')
                        """)
                .param("h", TokenHasher.hash(token))
                .query((rs, n) -> new DeviceRow(rs.getObject("id", UUID.class), rs.getObject("business_id", UUID.class),
                        rs.getTimestamp("last_seen_at") == null ? null : rs.getTimestamp("last_seen_at").toInstant(), rs.getString("trust_role"),
                        rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant(), rs.getString("revoked_reason")))
                .optional();
        row.ifPresent(r -> {
            if (r.revokedAt() == null && (r.seen() == null || Duration.between(r.seen(), now).compareTo(DEVICE_TOUCH) > 0)) {
                jdbc.sql("UPDATE device SET last_seen_at = :now WHERE id = :id").param("now", Timestamp.from(now)).param("id", r.id()).update();
            }
        });
        return row.map(r -> r.revokedAt() == null ? Actor.device(r.id(), r.businessId(), r.trust()) : Actor.draining(r.id(), r.businessId(), r.trust(), r.revokedAt()));
    }
}
