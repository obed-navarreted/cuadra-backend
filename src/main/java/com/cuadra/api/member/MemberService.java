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
    private final com.cuadra.api.common.Json json;

    public MemberService(JdbcClient jdbc, PasswordEncoder pinEncoder, Audit audit, com.cuadra.api.plan.PlanService plans, com.cuadra.api.common.Json json) {
        this.json = json;
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

    /** Lo que ve un teléfono vinculado: solo las personas con las que PUEDE actuar (rol ≤ el de quien lo vinculó), con su hash de PIN. */
    public List<MemberView> listForDevice(UUID businessId, Role trust) {
        return list(businessId, true).stream().filter(m -> Role.valueOf(m.role()).atMost(trust)).toList();
    }

    /** Dentro de un negocio no puede haber dos personas con el mismo nombre (es el "usuario" con el que se entra): sin distinguir mayúsculas ni espacios. */
    void requireUniqueName(UUID businessId, String displayName, UUID exceptMemberId) {
        Integer n = jdbc.sql("SELECT count(*) FROM member WHERE business_id = :b AND lower(btrim(display_name)) = lower(btrim(:n)) AND (CAST(:x AS uuid) IS NULL OR id <> CAST(:x AS uuid))")
                .param("b", businessId).param("n", displayName).param("x", exceptMemberId, java.sql.Types.OTHER).query(Integer.class).single();
        if (n > 0) throw ApiException.conflict("NAME_TAKEN", "Another person in this business already has that name");
    }

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
        requireUniqueName(ctx.businessId(), req.displayName(), null);
        List<UUID> clashingDisabled = requirePinFree(ctx.businessId(), req.pin(), null);
        // Un límite de plan solo impide AGREGAR gente; quien ya está sigue trabajando.
        plans.requireRoom(ctx.businessId(), com.cuadra.api.plan.PlanService.Feature.MEMBERS);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO member (id, business_id, display_name, role, pin_hash, pin_set_at, pin_must_change, created_by_member_id)
                        VALUES (:id, :b, :n, :r, :pin, now(), :must, :by)
                        """)
                .param("id", id).param("b", ctx.businessId()).param("n", req.displayName().trim()).param("r", req.role().name())
                .param("pin", pinEncoder.encode(req.pin())).param("must", req.mustChangePin()).param("by", ctx.memberId()).update();
        markPinConflicts(ctx.businessId(), clashingDisabled);
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
        if (req.displayName() != null) requireUniqueName(ctx.businessId(), req.displayName(), memberId);
        if ("ACTIVE".equals(req.status()) && "DISABLED".equals(target.status())) {
            // Mientras estuvo de baja, alguien activo quedó con su mismo PIN: primero hay que darle un PIN nuevo.
            boolean conflict = jdbc.sql("SELECT pin_conflict FROM member WHERE id = :id AND business_id = :b").param("id", memberId).param("b", ctx.businessId())
                    .query(Boolean.class).optional().orElse(false);
            if (conflict) throw ApiException.conflict("PIN_TAKEN", "Another active member of this business uses that PIN; set a new PIN first");
        }
        var sets = new java.util.ArrayList<String>();
        if (req.displayName() != null) sets.add("display_name = :n");
        if (req.role() != null) sets.add("role = :r");
        if (req.status() != null) {
            sets.add("status = :s");
            // La hora de la baja decide qué se acepta de lo que esa persona hizo sin conexión (lo de antes, sí; lo de después, no).
            sets.add("DISABLED".equals(req.status()) ? "disabled_at = CASE WHEN status = 'DISABLED' THEN disabled_at ELSE now() END" : "disabled_at = NULL, disable_snapshot = NULL");
        }
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
            if ("DISABLED".equals(req.status())) {
                snapshotPhones(ctx.businessId(), memberId);
                revokePersonalPhones(ctx, memberId);
                // Si la persona también entra con Google (administrador), se cierran todas sus sesiones (web y teléfono).
                jdbc.sql("""
                                UPDATE auth_session SET revoked_at = now() WHERE revoked_at IS NULL
                                   AND user_account_id = (SELECT user_account_id FROM member WHERE id = :m AND business_id = :b)
                                """).param("m", memberId).param("b", ctx.businessId()).update();
            }
            audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "member.update", "member", memberId,
                    (req.role() != null ? "role=" + req.role() + " " : "") + (req.status() != null ? "status=" + req.status() : ""));
        }
        return get(ctx.businessId(), memberId);
    }

    @Transactional
    public void resetPin(MemberContext ctx, UUID memberId, String pin, boolean mustChange) {
        MemberView target = get(ctx.businessId(), memberId);
        Role targetRole = Role.valueOf(target.role());
        if (!ctx.memberId().equals(memberId)) {
            // Al dueño solo lo cambia él mismo (con su sesión de Google): ni siquiera un admin puede restablecerle el PIN.
            if (targetRole == Role.OWNER) throw ApiException.forbidden("CANNOT_MODIFY_OWNER", "The owner cannot be modified");
            requireManage(ctx, targetRole);
        }
        validatePin(pin);
        List<UUID> clashingDisabled = requirePinFree(ctx.businessId(), pin, memberId);
        jdbc.sql("""
                        UPDATE member SET pin_hash = :h, pin_set_at = now(), pin_must_change = :m, pin_failed_count = 0, pin_locked_until = NULL, pin_conflict = false,
                               rev = nextval('change_rev_seq')
                         WHERE id = :id AND business_id = :b
                        """)
                .param("h", pinEncoder.encode(pin)).param("m", mustChange).param("id", memberId).param("b", ctx.businessId()).update();
        markPinConflicts(ctx.businessId(), clashingDisabled);
        audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "member.pin_reset", "member", memberId, null);
    }

    /**
     * Dar de baja a alguien corta YA sus teléfonos personales (los que vinculó entrando con su usuario y PIN): dejan de bajar datos y de operar, y solo
     * pueden terminar de enviar lo que esa persona hizo sin conexión ANTES de la baja (ver `Actor.draining`). Los teléfonos compartidos siguen: solo esa
     * persona deja de poder entrar en ellos. Las cuentas apartadas que esos teléfonos retenían se liberan.
     */
    /**
     * Foto de los teléfonos del negocio al dar de baja: último contacto y operaciones pendientes que informaron. Con ella el servidor decide qué de lo que
     * llegue después es plausible (ver `Access.requirePlausibleBeforeDisable`). Solo la primera baja cuenta (re-guardar no cambia la foto).
     */
    private void snapshotPhones(UUID businessId, UUID memberId) {
        var snap = new java.util.LinkedHashMap<String, Object>();
        jdbc.sql("SELECT id, last_sync_at, pending_ops FROM device WHERE business_id = :b AND revoked_at IS NULL").param("b", businessId).query((rs, n) -> {
            var one = new java.util.LinkedHashMap<String, Object>();
            one.put("lastSyncAt", rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant().toString());
            one.put("pendingOps", rs.getInt(3));
            snap.put(rs.getObject(1, UUID.class).toString(), one);
            return null;
        }).list();
        jdbc.sql("UPDATE member SET disable_snapshot = :s WHERE id = :m AND business_id = :b AND disable_snapshot IS NULL")
                .param("s", json.write(snap)).param("m", memberId).param("b", businessId).update();
    }

    private void revokePersonalPhones(MemberContext ctx, UUID memberId) {
        List<UUID> phones = jdbc.sql("""
                        UPDATE device SET revoked_at = now(), revoked_reason = 'MEMBER_DISABLED', rev = nextval('change_rev_seq')
                         WHERE business_id = :b AND linked_by_member_id = :m AND kind = 'PERSONAL' AND revoked_at IS NULL RETURNING id
                        """).param("b", ctx.businessId()).param("m", memberId).query(UUID.class).list();
        for (UUID d : phones) {
            jdbc.sql("UPDATE sale SET locked_by_device_id = NULL, locked_until = NULL WHERE locked_by_device_id = :d").param("d", d).update();
            audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "device.revoke", "device", d, "member_disabled");
        }
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

    /**
     * El PIN identifica a la persona al entrar con código del negocio + PIN, así que no se repite entre las personas ACTIVAS del negocio
     * (409 PIN_TAKEN). En la base solo hay hashes: se compara el PIN nuevo contra cada uno (≤ 10 personas; en paralelo porque bcrypt es lento).
     * Devuelve las personas DADAS DE BAJA con ese mismo PIN: quedan marcadas y, para reactivarlas, primero necesitan un PIN nuevo.
     */
    private List<UUID> requirePinFree(UUID businessId, String pin, UUID except) {
        record Holder(UUID id, boolean active, String hash) {}
        // Dos altas a la vez con el mismo PIN: la segunda espera a la primera y la ve.
        jdbc.sql("SELECT id FROM business WHERE id = :b FOR UPDATE").param("b", businessId).query(UUID.class).optional();
        List<Holder> others = jdbc.sql("SELECT id, status, pin_hash FROM member WHERE business_id = :b AND pin_hash IS NOT NULL")
                .param("b", businessId).query((rs, n) -> new Holder(rs.getObject("id", UUID.class), "ACTIVE".equals(rs.getString("status")), rs.getString("pin_hash")))
                .list().stream().filter(h -> !h.id().equals(except)).toList();
        List<Holder> same = others.parallelStream().filter(h -> pinEncoder.matches(pin, h.hash())).toList();
        if (same.stream().anyMatch(Holder::active)) throw ApiException.conflict("PIN_TAKEN", "Another active member of this business uses that PIN");
        return same.stream().map(Holder::id).toList();
    }

    private void markPinConflicts(UUID businessId, List<UUID> members) {
        for (UUID m : members) jdbc.sql("UPDATE member SET pin_conflict = true WHERE id = :id AND business_id = :b").param("id", m).param("b", businessId).update();
    }

    private static void validatePin(String pin) {
        if (pin == null || !pin.matches("\\d{5}")) throw ApiException.badRequest("INVALID_PIN", "PIN must be exactly 5 digits");
    }
}
