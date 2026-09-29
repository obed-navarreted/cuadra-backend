package com.cuadra.api.business;

import com.cuadra.api.common.ApiException;
import com.cuadra.api.security.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me")
public class MeController {
    private final JdbcClient jdbc;
    private final Clock clock;

    public MeController(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record Membership(UUID businessId, String businessName, UUID memberId, String role, String currency, String timezone) {}

    public record MeView(UUID id, String email, String fullName, String photoUrl, String locale, boolean platformAdmin,
                         List<Membership> businesses, UUID viewAsBusinessId) {}

    public record UpdateMe(@Size(max = 120) String fullName, @Pattern(regexp = "es|en") String locale) {}

    @GetMapping
    public MeView me(@AuthenticationPrincipal Actor actor) {
        requireUser(actor);
        return actor.isViewAs() ? viewAs(actor) : view(actor.userId());
    }

    @PutMapping
    public MeView update(@AuthenticationPrincipal Actor actor, @Valid @RequestBody UpdateMe body) {
        requireUser(actor);
        if (body.fullName() != null) {
            jdbc.sql("UPDATE user_account SET full_name = :n WHERE id = :id").param("n", body.fullName().trim()).param("id", actor.userId()).update();
        }
        if (body.locale() != null) {
            jdbc.sql("UPDATE user_account SET locale = :l WHERE id = :id").param("l", body.locale()).param("id", actor.userId()).update();
        }
        return view(actor.userId());
    }

    /** Eliminar cuenta (requisito de Google Play). Quien es dueño debe transferir o eliminar sus negocios primero. */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@AuthenticationPrincipal Actor actor) {
        requireUser(actor);
        int owned = jdbc.sql("""
                        SELECT count(*) FROM member m JOIN business b ON b.id = m.business_id
                         WHERE m.user_account_id = :u AND m.role = 'OWNER' AND m.status = 'ACTIVE' AND b.status <> 'DELETING'
                        """)
                .param("u", actor.userId()).query(Integer.class).single();
        if (owned > 0) {
            throw ApiException.conflict("OWNS_BUSINESSES", "Transfer or delete your businesses first");
        }
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.sql("UPDATE auth_session SET revoked_at = :now WHERE user_account_id = :u AND revoked_at IS NULL")
                .param("now", now).param("u", actor.userId()).update();
        jdbc.sql("UPDATE member SET status = 'DISABLED', rev = nextval('change_rev_seq') WHERE user_account_id = :u")
                .param("u", actor.userId()).update();
        jdbc.sql("""
                        UPDATE user_account SET deleted_at = :now, email = 'deleted-' || id || '@deleted.invalid',
                               google_sub = 'deleted-' || id, full_name = NULL, photo_url = NULL WHERE id = :u
                        """)
                .param("now", now).param("u", actor.userId()).update();
    }

    private MeView view(UUID userId) {
        List<Membership> businesses = jdbc.sql("""
                        SELECT b.id, b.name, m.id AS member_id, m.role, b.currency, b.timezone
                          FROM member m JOIN business b ON b.id = m.business_id
                         WHERE m.user_account_id = :u AND m.status = 'ACTIVE' AND b.status <> 'DELETING'
                         ORDER BY b.name
                        """)
                .param("u", userId)
                .query((rs, n) -> new Membership(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getObject("member_id", UUID.class), rs.getString("role"), rs.getString("currency"), rs.getString("timezone")))
                .list();
        return jdbc.sql("SELECT id, email, full_name, photo_url, locale, is_platform_admin FROM user_account WHERE id = :u")
                .param("u", userId)
                .query((rs, n) -> new MeView(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("full_name"),
                        rs.getString("photo_url"), rs.getString("locale"), rs.getBoolean("is_platform_admin"), businesses, null))
                .single();
    }

    /** Sesión "Ver como": solo aparece el negocio mirado (con el dueño como contexto) y nunca la consola, aunque quien mira sea admin de plataforma. */
    private MeView viewAs(Actor actor) {
        List<Membership> only = jdbc.sql("""
                        SELECT b.id, b.name, m.id AS member_id, m.role, b.currency, b.timezone
                          FROM business b JOIN member m ON m.business_id = b.id AND m.role = 'OWNER' AND m.status = 'ACTIVE'
                         WHERE b.id = :b
                        """)
                .param("b", actor.viewAsBusinessId())
                .query((rs, n) -> new Membership(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getObject("member_id", UUID.class), rs.getString("role"), rs.getString("currency"), rs.getString("timezone")))
                .list();
        return jdbc.sql("SELECT id, email, full_name, photo_url, locale FROM user_account WHERE id = :u")
                .param("u", actor.userId())
                .query((rs, n) -> new MeView(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("full_name"),
                        rs.getString("photo_url"), rs.getString("locale"), false, only, actor.viewAsBusinessId()))
                .single();
    }

    private static void requireUser(Actor actor) {
        if (!actor.isUser()) throw ApiException.forbidden("GOOGLE_REQUIRED", "This endpoint needs a Google session");
    }
}
