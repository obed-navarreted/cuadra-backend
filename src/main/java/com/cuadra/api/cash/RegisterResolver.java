package com.cuadra.api.cash;

import com.cuadra.api.common.ApiException;
import com.cuadra.api.tenancy.MemberContext;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** A qué caja física pertenece una operación: la que se indica, o la del teléfono, o la primera del negocio. */
@Component
public class RegisterResolver {
    private final JdbcClient jdbc;

    public RegisterResolver(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID resolve(MemberContext ctx, UUID requested) {
        if (requested != null) {
            if (jdbc.sql("SELECT count(*) FROM cash_register WHERE id = :r AND business_id = :b").param("r", requested).param("b", ctx.businessId()).query(Integer.class).single() == 0) {
                throw ApiException.badRequest("INVALID_CASH_REGISTER", "Cash register not found");
            }
            return requested;
        }
        if (ctx.deviceId() != null) {
            UUID fromDevice = jdbc.sql("SELECT cash_register_id FROM device WHERE id = :d").param("d", ctx.deviceId()).query((rs, n) -> rs.getObject(1, UUID.class)).optional().orElse(null);
            if (fromDevice != null) return fromDevice;
        }
        return jdbc.sql("SELECT id FROM cash_register WHERE business_id = :b AND active ORDER BY name LIMIT 1").param("b", ctx.businessId()).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.badRequest("NO_CASH_REGISTER", "The business has no cash register"));
    }
}
