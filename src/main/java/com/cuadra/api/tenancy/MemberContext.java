package com.cuadra.api.tenancy;

import com.cuadra.api.common.ApiException;
import java.util.UUID;

/**
 * Persona y rol con que se actúa dentro de un negocio concreto. `deviceId` viene del teléfono, si lo hay.
 * `unverifiedRole`: en un teléfono cuyo rol base es menor que el de la persona y sin PIN verificado por el servidor en ese teléfono, la persona actúa con
 * el rol base (`role`) y aquí queda su rol real: lo que solo ese rol puede hacer responde 403 PIN_VERIFICATION_REQUIRED (ADR 0012, 2026-10-01).
 */
public record MemberContext(UUID businessId, UUID memberId, Role role, UUID userId, UUID deviceId, Role unverifiedRole) {

    public MemberContext(UUID businessId, UUID memberId, Role role, UUID userId, UUID deviceId) {
        this(businessId, memberId, role, userId, deviceId, null);
    }

    /** La misma persona, con el rol base del teléfono mientras su PIN no se verifique en él. */
    public MemberContext capped(Role base) {
        return role.atMost(base) ? this : new MemberContext(businessId, memberId, base, userId, deviceId, role);
    }

    public void require(Role.Permission permission) {
        if (!role.can(permission)) {
            if (unverifiedRole != null && unverifiedRole.can(permission)) throw pinVerificationRequired();
            throw ApiException.forbidden("FORBIDDEN", "Missing permission " + permission);
        }
    }

    /** Para las reglas que exigen un rol concreto (p. ej. solo el dueño): igual que `require`, con el mismo código si falta verificar el PIN. */
    public void requireRole(Role needed) {
        if (role == needed) return;
        if (unverifiedRole == needed) throw pinVerificationRequired();
        throw ApiException.forbidden("FORBIDDEN", "Only " + needed + " can do this");
    }

    /** El rol real de la persona (con el PIN verificado o no). */
    public Role realRole() { return unverifiedRole != null ? unverifiedRole : role; }

    public ApiException pinVerificationRequired() {
        return ApiException.forbidden("PIN_VERIFICATION_REQUIRED", "Confirm your PIN on this phone (needs internet the first time)")
                .with("memberId", memberId == null ? null : memberId.toString());
    }
}
