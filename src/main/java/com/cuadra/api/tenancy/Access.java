package com.cuadra.api.tenancy;

import com.cuadra.api.common.ApiException;
import com.cuadra.api.security.Actor;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Único punto donde se decide a qué negocio y con qué rol se actúa. Todo endpoint de negocio pasa por aquí:
 * un usuario solo entra a negocios donde es miembro; un teléfono solo al suyo.
 */
@Component
public class Access {
    public static final String MEMBER_HEADER = "X-Member-Id";

    private final JdbcClient jdbc;

    public Access(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Actúa como una persona concreta (usuario con Google, o teléfono + miembro con PIN). */
    public MemberContext member(Actor actor, UUID businessId, UUID memberHeader) {
        if (actor.isViewAs()) return viewAs(actor, businessId);
        if (actor.isDevice()) {
            if (!actor.deviceBusinessId().equals(businessId)) throw notFound();
            requireNotSuspended(businessId);
            if (memberHeader == null) {
                throw ApiException.badRequest("MEMBER_REQUIRED", MEMBER_HEADER + " header is required with a device token");
            }
            return jdbc.sql("SELECT id, role FROM member WHERE id = :m AND business_id = :b AND status = 'ACTIVE'")
                    .param("m", memberHeader).param("b", businessId)
                    .query((rs, n) -> new MemberContext(businessId, rs.getObject("id", UUID.class),
                            Role.valueOf(rs.getString("role")), null, actor.deviceId()))
                    .optional().orElseThrow(() -> ApiException.forbidden("MEMBER_NOT_ACTIVE", "Member not active in this business"));
        }
        return userMember(actor.userId(), businessId).orElseThrow(Access::notFound);
    }

    /** Un teléfono vinculado sin persona elegida (pantalla de entrada), o cualquier miembro. */
    public void businessAccess(Actor actor, UUID businessId) {
        if (actor.isViewAs()) {
            viewAs(actor, businessId);
            return;
        }
        if (actor.isDevice()) {
            if (!actor.deviceBusinessId().equals(businessId)) throw notFound();
            return;
        }
        userMember(actor.userId(), businessId).orElseThrow(Access::notFound);
    }

    public Optional<MemberContext> userMember(UUID userId, UUID businessId) {
        Optional<MemberContext> ctx = jdbc.sql("""
                        SELECT m.id, m.role FROM member m JOIN business b ON b.id = m.business_id
                         WHERE m.user_account_id = :u AND m.business_id = :b AND m.status = 'ACTIVE' AND b.status <> 'DELETING'
                        """)
                .param("u", userId).param("b", businessId)
                .query((rs, n) -> new MemberContext(businessId, rs.getObject("id", UUID.class),
                        Role.valueOf(rs.getString("role")), userId, null))
                .optional();
        ctx.ifPresent(c -> requireNotSuspended(businessId));
        return ctx;
    }

    /**
     * Un negocio suspendido por la plataforma (abuso, falta de pago acordada) no opera: ni la web ni los teléfonos. El dueño ve un mensaje claro.
     * No es un límite de plan (esos nunca bloquean la caja): lo decide una persona desde la consola y queda auditado.
     */
    private void requireNotSuspended(UUID businessId) {
        String status = jdbc.sql("SELECT status FROM business WHERE id = :b").param("b", businessId).query(String.class).optional().orElse("ACTIVE");
        if ("SUSPENDED".equals(status)) throw ApiException.forbidden("BUSINESS_SUSPENDED", "This business is suspended");
    }

    /** Soporte mirando un negocio: contexto de dueño, pero la sesión ya está limitada a lectura por el filtro. Solo el negocio para el que se emitió. */
    private MemberContext viewAs(Actor actor, UUID businessId) {
        if (!businessId.equals(actor.viewAsBusinessId())) throw notFound();
        UUID owner = jdbc.sql("SELECT id FROM member WHERE business_id = :b AND role = 'OWNER' AND status = 'ACTIVE'").param("b", businessId).query(UUID.class).optional().orElseThrow(Access::notFound);
        return new MemberContext(businessId, owner, Role.OWNER, actor.userId(), null);
    }

    /** Un negocio ajeno no revela su existencia: siempre 404. */
    private static ApiException notFound() {
        return ApiException.notFound("BUSINESS_NOT_FOUND", "Business not found");
    }
}
