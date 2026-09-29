package com.cuadra.api.security;

import com.cuadra.api.config.CuadraProperties;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final JdbcClient jdbc;
    private final GoogleTokenVerifier google;
    private final CuadraProperties props;
    private final Clock clock;

    public AuthService(JdbcClient jdbc, GoogleTokenVerifier google, CuadraProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.google = google;
        this.props = props;
        this.clock = clock;
    }

    public record LoginResult(String token, Instant expiresAt, UUID userId) {}

    @Transactional
    public LoginResult loginWithGoogle(String idToken, String kind, String userAgent) {
        GoogleIdentity id = google.verify(idToken);
        Instant now = clock.instant();
        boolean platformAdmin = props.platform().adminEmails().contains(id.email().toLowerCase());

        Optional<UUID> existing = jdbc.sql("SELECT id FROM user_account WHERE google_sub = :s AND deleted_at IS NULL")
                .param("s", id.sub()).query(UUID.class).optional();
        UUID userId;
        if (existing.isPresent()) {
            userId = existing.get();
            jdbc.sql("""
                            UPDATE user_account SET email = :e, email_verified = true, full_name = COALESCE(:n, full_name),
                                   photo_url = :p, last_login_at = :now, is_platform_admin = :adm WHERE id = :id
                            """)
                    .param("e", id.email()).param("n", id.name()).param("p", id.picture())
                    .param("now", Timestamp.from(now)).param("adm", platformAdmin).param("id", userId).update();
        } else {
            userId = UUID.randomUUID();
            jdbc.sql("""
                            INSERT INTO user_account (id, google_sub, email, email_verified, full_name, photo_url, last_login_at, is_platform_admin)
                            VALUES (:id, :s, :e, true, :n, :p, :now, :adm)
                            """)
                    .param("id", userId).param("s", id.sub()).param("e", id.email()).param("n", id.name())
                    .param("p", id.picture()).param("now", Timestamp.from(now)).param("adm", platformAdmin).update();
        }

        String token = TokenHasher.newToken();
        Instant expires = now.plus("WEB".equals(kind) ? props.sessionTtlWeb() : props.sessionTtlApp());
        jdbc.sql("""
                        INSERT INTO auth_session (id, user_account_id, token_hash, kind, user_agent, issued_at, last_seen_at, expires_at)
                        VALUES (:id, :u, :h, :k, :ua, :now, :now, :exp)
                        """)
                .param("id", UUID.randomUUID()).param("u", userId).param("h", TokenHasher.hash(token))
                .param("k", kind).param("ua", userAgent).param("now", Timestamp.from(now))
                .param("exp", Timestamp.from(expires)).update();
        return new LoginResult(token, expires, userId);
    }

    /** Duración de la sesión de la consola con contraseña: una jornada (la consola exige además una sesión de menos de 12 h). */
    static final java.time.Duration PLATFORM_SESSION_TTL = java.time.Duration.ofHours(12);
    private static final int MAX_FAILURES = 5;
    private static final java.time.Duration LOCKOUT = java.time.Duration.ofMinutes(15);
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> failures = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Instant> lockedUntil = new java.util.concurrent.ConcurrentHashMap<>();
    private final org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder bcrypt = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12);
    // Hash de relleno (válido): si el usuario no existe se compara igual, para que el tiempo de respuesta no delate si el usuario es válido.
    private final String dummyHash = bcrypt.encode(UUID.randomUUID().toString());

    /**
     * Acceso de la consola con usuario y contraseña (definidos por entorno). 404 si no está configurado; tras 5 fallos seguidos bloquea 15 minutos
     * (por usuario y por instancia, sin importar la IP). Entra como la cuenta del primer correo de la lista de administradores, así `PlatformAccess`
     * (correo permitido + sesión reciente) y la auditoría funcionan igual que con Google.
     */
    @Transactional
    public LoginResult loginPlatformAdmin(String username, String password, String userAgent) {
        CuadraProperties.Platform p = props.platform();
        if (!p.passwordLoginEnabled() || p.adminEmails().isEmpty()) throw com.cuadra.api.common.ApiException.notFound("NOT_FOUND", "Not found");
        Instant now = clock.instant();
        String key = username == null ? "" : username.trim().toLowerCase();
        Instant locked = lockedUntil.get(key);
        if (locked != null && locked.isAfter(now)) throw com.cuadra.api.common.ApiException.tooMany("LOCKED", "Too many failed attempts; try again later");
        boolean userOk = username != null && username.trim().equals(p.adminUser());
        boolean passOk = password != null && bcrypt.matches(password, userOk ? p.adminPasswordHash() : dummyHash);
        if (!(userOk && passOk)) {
            if (failures.computeIfAbsent(key, k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet() >= MAX_FAILURES) {
                lockedUntil.put(key, now.plus(LOCKOUT));
                failures.remove(key);
            }
            throw com.cuadra.api.common.ApiException.unauthorized("INVALID_CREDENTIALS", "Invalid credentials");
        }
        failures.remove(key);
        lockedUntil.remove(key);

        String email = p.adminEmails().get(0);
        UUID userId = jdbc.sql("SELECT id FROM user_account WHERE lower(email) = :e AND deleted_at IS NULL").param("e", email).query(UUID.class).optional().orElse(null);
        if (userId == null) {
            userId = UUID.randomUUID();
            jdbc.sql("INSERT INTO user_account (id, google_sub, email, email_verified, full_name, last_login_at, is_platform_admin) VALUES (:id, :s, :e, true, 'Plataforma', :now, true)")
                    .param("id", userId).param("s", "platform-" + userId).param("e", email).param("now", Timestamp.from(now)).update();
        } else {
            jdbc.sql("UPDATE user_account SET is_platform_admin = true, last_login_at = :now WHERE id = :id").param("now", Timestamp.from(now)).param("id", userId).update();
        }
        String token = TokenHasher.newToken();
        Instant expires = now.plus(PLATFORM_SESSION_TTL);
        jdbc.sql("INSERT INTO auth_session (id, user_account_id, token_hash, kind, user_agent, issued_at, last_seen_at, expires_at) VALUES (:id, :u, :h, 'WEB', :ua, :now, :now, :exp)")
                .param("id", UUID.randomUUID()).param("u", userId).param("h", TokenHasher.hash(token)).param("ua", userAgent).param("now", Timestamp.from(now)).param("exp", Timestamp.from(expires)).update();
        jdbc.sql("INSERT INTO platform_audit_log (actor_user_id, action, target, payload) VALUES (:u, 'platform.login', NULL, 'usuario y contraseña')").param("u", userId).update();
        return new LoginResult(token, expires, userId);
    }

    public void logout(UUID sessionId) {
        jdbc.sql("UPDATE auth_session SET revoked_at = :now WHERE id = :id")
                .param("now", Timestamp.from(clock.instant())).param("id", sessionId).update();
    }
}
