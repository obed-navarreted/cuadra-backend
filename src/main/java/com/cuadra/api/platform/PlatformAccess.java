package com.cuadra.api.platform;

import com.cuadra.api.common.ApiException;
import com.cuadra.api.config.CuadraProperties;
import com.cuadra.api.security.Actor;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Quién entra a la consola: una persona con Google, marcada como admin de plataforma, cuyo correo está en la lista de permitidos
 * y con una sesión de menos de 12 horas (hay que volver a entrar con Google). Cualquier otra persona recibe 404: la consola no existe para ella.
 */
@Component
public class PlatformAccess {
    static final Duration MAX_SESSION_AGE = Duration.ofHours(12);

    private final JdbcClient jdbc;
    private final CuadraProperties props;
    private final Clock clock;

    public PlatformAccess(JdbcClient jdbc, CuadraProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    /** Devuelve el id de quien administra. */
    public UUID requireAdmin(Actor actor) {
        if (actor == null || !actor.isUser() || actor.isViewAs()) throw notFound();
        var row = jdbc.sql("""
                        SELECT u.email, u.is_platform_admin, s.issued_at
                          FROM auth_session s JOIN user_account u ON u.id = s.user_account_id
                         WHERE s.id = :s AND u.id = :u AND u.deleted_at IS NULL
                        """)
                .param("s", actor.sessionId()).param("u", actor.userId())
                .query((rs, n) -> new Object[] {rs.getString("email"), rs.getBoolean("is_platform_admin"), rs.getTimestamp("issued_at")})
                .optional().orElseThrow(PlatformAccess::notFound);
        String email = ((String) row[0]).toLowerCase();
        Timestamp issued = (Timestamp) row[2];
        boolean fresh = issued.toInstant().plus(MAX_SESSION_AGE).isAfter(clock.instant());
        if (!(Boolean) row[1] || !props.platform().adminEmails().contains(email) || !fresh) throw notFound();
        return actor.userId();
    }

    private static ApiException notFound() {
        return ApiException.notFound("NOT_FOUND", "Not found");
    }
}
