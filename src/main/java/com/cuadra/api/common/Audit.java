package com.cuadra.api.common;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Auditoría de acciones sensibles: quién, en qué negocio, desde qué teléfono. */
@Component
public class Audit {
    private final JdbcClient jdbc;

    public Audit(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void log(UUID businessId, UUID memberId, UUID userId, UUID deviceId, String action, String entity, Object entityId, String detail) {
        jdbc.sql("""
                        INSERT INTO audit_log (business_id, actor_member_id, actor_user_id, device_id, action, entity, entity_id, detail)
                        VALUES (:b, :m, :u, :d, :a, :e, :eid, :det)
                        """)
                .param("b", businessId).param("m", memberId).param("u", userId).param("d", deviceId)
                .param("a", action).param("e", entity).param("eid", entityId == null ? null : entityId.toString())
                .param("det", detail).update();
    }
}
