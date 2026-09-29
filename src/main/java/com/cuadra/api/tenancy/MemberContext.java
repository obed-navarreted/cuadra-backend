package com.cuadra.api.tenancy;

import com.cuadra.api.common.ApiException;
import java.util.UUID;

/** Persona y rol con que se actúa dentro de un negocio concreto. `deviceId` viene del teléfono, si lo hay. */
public record MemberContext(UUID businessId, UUID memberId, Role role, UUID userId, UUID deviceId) {

    public void require(Role.Permission permission) {
        if (!role.can(permission)) {
            throw ApiException.forbidden("FORBIDDEN", "Missing permission " + permission);
        }
    }
}
