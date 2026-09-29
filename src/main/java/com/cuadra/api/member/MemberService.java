package com.cuadra.api.member;

import com.cuadra.api.common.ApiException;
import com.cuadra.api.common.Audit;
import com.cuadra.api.tenancy.MemberContext;
import com.cuadra.api.tenancy.Role;
import com.cuadra.api.tenancy.Role.Permission;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberService {
    private final JdbcClient jdbc;
    private final PasswordEncoder pinEncoder;
    private final Audit audit;
    private final com.cuadra.api.plan.PlanService plans;

    public MemberService(JdbcClient jdbc, PasswordEncoder pinEncoder, Audit audit, com.cuadra.api.plan.PlanService plans) {
        this.jdbc = jdbc;
        this.pinEncoder = pinEncoder;
        this.audit = audit;
        this.plans = plans;
    }

    /** `pinHash` solo se entrega a teléfonos vinculados: lo necesitan para validar el PIN sin conexión. */
    public record MemberView(UUID id, String displayName, String role, String status, boolean hasGoogle, boolean pinSet,
                             boolean pinMustChange, String color, String pinHash) {}

    public record CreatePinMember(String displayName, Role role, String pin, boolean mustChangePin) {}

    public record UpdateMember(String displayName, Role role, String status, String color) {}

    public List<MemberView> list(UUID businessId, boolean includePinHash) {
        return jdbc.sql("""
                        SELECT id, display_name, role, status, user_account_id IS NOT NULL AS has_google,
                               pin_hash IS NOT NULL AS pin_set, pin_must_change, color, pin_hash
                          FROM member WHERE business_id = :b ORDER BY (role = 'OWNER') DESC, (role = 'ADMIN') DESC, display_name
                        """)
                .param("b", businessId)
                .query((rs, n) -> new MemberView(rs.getObject("id", UUID.class), rs.getString("display_name"), rs.getString("role"),
                        rs.getString("status"), rs.getBoolean("has_google"), rs.getBoolean("pin_set"), rs.getBoolean("pin_must_change"),
                        rs.getString("color"), includePinHash && rs.getString("pin_hash") != null ? rs.getString("pin_hash") : null))
                .list();
    }

    @Transactional
    public MemberView createWithPin(MemberContext ctx, CreatePinMember req) {
        if (req.role() == Role.OWNER) throw ApiException.badRequest("INVALID_ROLE", "A business has a single owner");
        requireManage(ctx, req.role());
        validatePin(req.pin());
        // Un límite de plan solo impide AGREGAR gente; quien ya está sigue trabajando.
        plans.requireRoom(ctx.businessId(), com.cuadra.api.plan.PlanService.Feature.MEMBERS);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO member (id, business_id, display_name, role, pin_hash, pin_set_at, pin_must_change, created_by_member_id)
                        VALUES (:id, :b, :n, :r, :pin, now(), :must, :by)
                        """)
                .param("id", id).param("b", ctx.businessId()).param("n", req.displayName().trim()).param("r", req.role().name())
                .param("pin", pinEncoder.encode(req.pin())).param("must", req.mustChangePin()).param("by", ctx.memberId()).update();
        audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "member.create", "member", id, req.role().name());
        return get(ctx.businessId(), id);
    }

    @Transactional
    public MemberView update(MemberContext ctx, UUID memberId, UpdateMember req) {
        MemberView target = get(ctx.businessId(), memberId);
        Role targetRole = Role.valueOf(target.role());
        if (targetRole == Role.OWNER && !ctx.memberId().equals(memberId)) {
            throw ApiException.forbidden("CANNOT_MODIFY_OWNER", "The owner cannot be modified");
        }
        boolean self = ctx.memberId().equals(memberId);
        if (self && (req.role() != null || req.status() != null)) {
            throw ApiException.forbidden("CANNOT_MODIFY_SELF", "You cannot change your own role or status");
        }
        if (!self) requireManage(ctx, targetRole);
        if (req.role() != null) {
            if (req.role() == Role.OWNER) throw ApiException.badRequest("USE_OWNER_TRANSFER", "Use the ownership transfer");
            requireManage(ctx, req.role());
        }
        if (req.status() != null && !List.of("ACTIVE", "DISABLED").contains(req.status())) {
            throw ApiException.badRequest("INVALID_STATUS", "Invalid status");
        }
        var sets = new java.util.ArrayList<String>();
        if (req.displayName() != null) sets.add("display_name = :n");
        if (req.role() != null) sets.add("role = :r");
        if (req.status() != null) sets.add("status = :s");
        if (req.color() != null) sets.add("color = :c");
        if (!sets.isEmpty()) {
            sets.add("rev = nextval('change_rev_seq')");
            var stmt = jdbc.sql("UPDATE member SET " + String.join(", ", sets) + " WHERE id = :id AND business_id = :b")
                    .param("id", memberId).param("b", ctx.businessId());
            if (req.displayName() != null) stmt = stmt.param("n", req.displayName().trim());
            if (req.role() != null) stmt = stmt.param("r", req.role().name());
            if (req.status() != null) stmt = stmt.param("s", req.status());
            if (req.color() != null) stmt = stmt.param("c", req.color());
            stmt.update();
            audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "member.update", "member", memberId,
                    (req.role() != null ? "role=" + req.role() + " " : "") + (req.status() != null ? "status=" + req.status() : ""));
        }
        return get(ctx.businessId(), memberId);
    }

    @Transactional
    public void resetPin(MemberContext ctx, UUID memberId, String pin, boolean mustChange) {
        MemberView target = get(ctx.businessId(), memberId);
        Role targetRole = Role.valueOf(target.role());
        if (!ctx.memberId().equals(memberId)) requireManage(ctx, targetRole);
        validatePin(pin);
        jdbc.sql("""
                        UPDATE member SET pin_hash = :h, pin_set_at = now(), pin_must_change = :m, rev = nextval('change_rev_seq')
                         WHERE id = :id AND business_id = :b
                        """)
                .param("h", pinEncoder.encode(pin)).param("m", mustChange).param("id", memberId).param("b", ctx.businessId()).update();
        audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "member.pin_reset", "member", memberId, null);
    }

    /** Traspasa la propiedad a otro miembro con cuenta de Google. Siempre queda un solo dueño activo. */
    @Transactional
    public void transferOwnership(MemberContext ctx, UUID newOwnerId) {
        ctx.require(Permission.TRANSFER_OWNERSHIP);
        if (ctx.memberId().equals(newOwnerId)) throw ApiException.badRequest("ALREADY_OWNER", "Already the owner");
        var target = jdbc.sql("SELECT user_account_id, status FROM member WHERE id = :id AND business_id = :b")
                .param("id", newOwnerId).param("b", ctx.businessId())
                .query((rs, n) -> new Object[] {rs.getObject("user_account_id"), rs.getString("status")}).optional()
                .orElseThrow(() -> ApiException.notFound("MEMBER_NOT_FOUND", "Member not found"));
        if (target[0] == null) throw ApiException.conflict("GOOGLE_REQUIRED", "The new owner needs a Google account");
        if (!"ACTIVE".equals(target[1])) throw ApiException.conflict("MEMBER_NOT_ACTIVE", "Member is not active");
        // Primero baja el dueño actual: el índice único admite un solo dueño activo.
        jdbc.sql("UPDATE member SET role = 'ADMIN', rev = nextval('change_rev_seq') WHERE id = :id").param("id", ctx.memberId()).update();
        jdbc.sql("UPDATE member SET role = 'OWNER', rev = nextval('change_rev_seq') WHERE id = :id").param("id", newOwnerId).update();
        audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "owner.transfer", "member", newOwnerId, null);
    }

    public MemberView get(UUID businessId, UUID memberId) {
        return list(businessId, false).stream().filter(m -> m.id().equals(memberId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("MEMBER_NOT_FOUND", "Member not found"));
    }

    /** Administrar admins es del dueño; administrar cajeros, de dueño y admins. */
    private static void requireManage(MemberContext ctx, Role targetRole) {
        ctx.require(targetRole == Role.CASHIER ? Permission.MANAGE_CASHIERS : Permission.MANAGE_ADMINS);
    }

    private static void validatePin(String pin) {
        if (pin == null || !pin.matches("\\d{4,6}")) throw ApiException.badRequest("INVALID_PIN", "PIN must be 4 to 6 digits");
    }
}
