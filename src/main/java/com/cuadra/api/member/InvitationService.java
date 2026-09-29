package com.cuadra.api.member;

import com.cuadra.api.common.ApiException;
import com.cuadra.api.common.Audit;
import com.cuadra.api.config.CuadraProperties;
import com.cuadra.api.security.TokenHasher;
import com.cuadra.api.tenancy.MemberContext;
import com.cuadra.api.tenancy.Role;
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

@Service
public class InvitationService {
    private final JdbcClient jdbc;
    private final Audit audit;
    private final Clock clock;
    private final CuadraProperties props;
    private final com.cuadra.api.notification.NotificationService notifications;
    private final com.cuadra.api.plan.PlanService plans;

    public InvitationService(JdbcClient jdbc, Audit audit, Clock clock, CuadraProperties props, com.cuadra.api.notification.NotificationService notifications, com.cuadra.api.plan.PlanService plans) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        this.props = props;
        this.notifications = notifications;
        this.plans = plans;
    }

    /** `email`: si la invitación es para una persona concreta, solo esa cuenta de Google puede aceptarla. */
    public record InvitationView(UUID id, String role, String code, String url, String email, int maxUses, int usedCount, Instant expiresAt) {}

    public record Preview(String businessName, String role, boolean valid) {}

    @Transactional
    public InvitationView create(MemberContext ctx, Role role, int maxUses, int expiresInDays, String email) {
        if (role == Role.OWNER) throw ApiException.badRequest("INVALID_ROLE", "Cannot invite an owner");
        ctx.require(role == Role.ADMIN ? Permission.INVITE_ADMIN : Permission.INVITE_CASHIER);
        String mail = email == null || email.isBlank() ? null : email.trim().toLowerCase(java.util.Locale.ROOT);
        if (mail != null && (mail.length() > 254 || !mail.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))) throw ApiException.badRequest("INVALID_EMAIL", "Invalid email");
        UUID id = UUID.randomUUID();
        String code = TokenHasher.newCode(8);
        Instant expires = clock.instant().plus(Duration.ofDays(Math.max(1, Math.min(expiresInDays, 30))));
        jdbc.sql("""
                        INSERT INTO invitation (id, business_id, role, code, email, max_uses, expires_at, created_by_member_id)
                        VALUES (:id, :b, :r, :c, :mail, :m, :e, :by)
                        """)
                .param("id", id).param("b", ctx.businessId()).param("r", role.name()).param("c", code).param("mail", mail)
                .param("m", Math.max(1, maxUses)).param("e", Timestamp.from(expires)).param("by", ctx.memberId()).update();
        audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "invitation.create", "invitation", id, role.name());
        return new InvitationView(id, role.name(), code, props.app().baseUrl() + "/i/" + code, mail, Math.max(1, maxUses), 0, expires);
    }

    public List<InvitationView> listActive(MemberContext ctx) {
        ctx.require(Permission.INVITE_CASHIER);
        return jdbc.sql("""
                        SELECT id, role, code, email, max_uses, used_count, expires_at FROM invitation
                         WHERE business_id = :b AND revoked_at IS NULL AND expires_at > :now AND used_count < max_uses
                         ORDER BY created_at DESC
                        """)
                .param("b", ctx.businessId()).param("now", Timestamp.from(clock.instant()))
                .query((rs, n) -> new InvitationView(rs.getObject("id", UUID.class), rs.getString("role"), rs.getString("code"),
                        props.app().baseUrl() + "/i/" + rs.getString("code"), rs.getString("email"), rs.getInt("max_uses"), rs.getInt("used_count"),
                        rs.getTimestamp("expires_at").toInstant()))
                .list();
    }

    @Transactional
    public void revoke(MemberContext ctx, UUID invitationId) {
        ctx.require(Permission.INVITE_CASHIER);
        int n = jdbc.sql("UPDATE invitation SET revoked_at = :now WHERE id = :id AND business_id = :b AND revoked_at IS NULL")
                .param("now", Timestamp.from(clock.instant())).param("id", invitationId).param("b", ctx.businessId()).update();
        if (n == 0) throw ApiException.notFound("INVITATION_NOT_FOUND", "Invitation not found");
        audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "invitation.revoke", "invitation", invitationId, null);
    }

    /** Vista previa pública: solo revela nombre del negocio y rol, nunca datos internos. */
    public Preview preview(String code) {
        return jdbc.sql("""
                        SELECT b.name, i.role, (i.revoked_at IS NULL AND i.expires_at > :now AND i.used_count < i.max_uses) AS valid
                          FROM invitation i JOIN business b ON b.id = i.business_id WHERE i.code = :c AND b.status = 'ACTIVE'
                        """)
                .param("c", code.toUpperCase()).param("now", Timestamp.from(clock.instant()))
                .query((rs, n) -> new Preview(rs.getString("name"), rs.getString("role"), rs.getBoolean("valid")))
                .optional().orElseThrow(() -> ApiException.notFound("INVITATION_NOT_FOUND", "Invitation not found"));
    }

    @Transactional
    public UUID accept(UUID userId, String code) {
        var inv = jdbc.sql("""
                        SELECT i.id, i.business_id, i.role, i.email FROM invitation i JOIN business b ON b.id = i.business_id
                         WHERE i.code = :c AND b.status = 'ACTIVE' AND i.revoked_at IS NULL AND i.expires_at > :now
                        """)
                .param("c", code.toUpperCase()).param("now", Timestamp.from(clock.instant()))
                .query((rs, n) -> new Object[] {rs.getObject("id", UUID.class), rs.getObject("business_id", UUID.class), rs.getString("role"), rs.getString("email")})
                .optional().orElseThrow(() -> ApiException.notFound("INVITATION_NOT_FOUND", "Invitation not found or expired"));
        UUID invitationId = (UUID) inv[0];
        UUID businessId = (UUID) inv[1];
        // Una invitación hecha para un correo solo la acepta esa cuenta: si el enlace llegara a otra persona, no entra.
        if (inv[3] != null) {
            String mine = jdbc.sql("SELECT email FROM user_account WHERE id = :u").param("u", userId).query(String.class).single();
            if (!((String) inv[3]).equalsIgnoreCase(mine)) throw ApiException.forbidden("INVITATION_EMAIL_MISMATCH", "This invitation is for another account");
        }

        boolean already = jdbc.sql("SELECT count(*) FROM member WHERE business_id = :b AND user_account_id = :u")
                .param("b", businessId).param("u", userId).query(Integer.class).single() > 0;
        if (already) throw ApiException.conflict("ALREADY_MEMBER", "You already belong to this business");

        plans.requireRoom(businessId, com.cuadra.api.plan.PlanService.Feature.MEMBERS);
        // Consumir un uso de forma atómica: dos personas no pueden usar la última plaza a la vez.
        int used = jdbc.sql("UPDATE invitation SET used_count = used_count + 1 WHERE id = :id AND used_count < max_uses")
                .param("id", invitationId).update();
        if (used == 0) throw ApiException.conflict("INVITATION_EXHAUSTED", "Invitation already used");

        String name = jdbc.sql("SELECT COALESCE(full_name, email) FROM user_account WHERE id = :u").param("u", userId)
                .query(String.class).single();
        UUID memberId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO member (id, business_id, user_account_id, display_name, role, created_by_member_id)
                        SELECT :id, :b, :u, :n, :r, created_by_member_id FROM invitation WHERE id = :inv
                        """)
                .param("id", memberId).param("b", businessId).param("u", userId).param("n", name).param("r", (String) inv[2])
                .param("inv", invitationId).update();
        audit.log(businessId, memberId, userId, null, "invitation.accept", "invitation", invitationId, (String) inv[2]);
        // Avisa a quien invitó (si sigue activo) que la persona ya entró.
        jdbc.sql("SELECT created_by_member_id FROM invitation WHERE id = :i").param("i", invitationId).query((rs, n) -> java.util.Optional.ofNullable(rs.getObject(1, UUID.class))).single()
                .ifPresent(inviter -> notifications.notify(businessId, com.cuadra.api.notification.NotificationService.Type.MEMBER_JOINED,
                        java.util.Map.of("memberId", memberId.toString(), "memberName", name, "role", (String) inv[2]), java.util.List.of(inviter), memberId, "MEMBER_JOINED:" + memberId, null));
        return businessId;
    }
}
