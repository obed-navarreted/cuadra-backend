package com.cuadra.api.credit;

import com.cuadra.api.common.ApiException;
import com.cuadra.api.tenancy.MemberContext;
import com.cuadra.api.tenancy.Role.Permission;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plantillas de los mensajes de WhatsApp que edita cada negocio. Si no hay una guardada, la app usa la que trae por defecto en cada idioma.
 * Variables (las mismas que usa la app al armar el mensaje): {negocio} {cliente} {fecha} {monto} {detalle} {saldo} {pagado_linea} {dias} {desde}.
 * El servidor no las interpreta: solo guarda el texto; quien lo muestra sustituye cada variable.
 */
@Service
public class TemplateService {
    public static final Set<String> KINDS = Set.of("CREDIT_NEW", "PAYMENT", "PAID_OFF", "REMINDER", "STATEMENT", "TICKET");
    private static final Set<String> LOCALES = Set.of("es", "en");

    private final JdbcClient jdbc;

    public TemplateService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record TemplateView(UUID id, String kind, String locale, String body, long rev) {}

    public List<TemplateView> list(UUID businessId) {
        return jdbc.sql("SELECT id, kind, locale, body, rev FROM message_template WHERE business_id = :b ORDER BY kind, locale").param("b", businessId)
                .query((rs, n) -> new TemplateView(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("locale"), rs.getString("body"), rs.getLong("rev"))).list();
    }

    public List<TemplateView> byIds(UUID businessId, List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.sql("SELECT id, kind, locale, body, rev FROM message_template WHERE business_id = :b AND id IN (:ids)").param("b", businessId).param("ids", ids)
                .query((rs, n) -> new TemplateView(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("locale"), rs.getString("body"), rs.getLong("rev"))).list();
    }

    @Transactional
    public TemplateView upsert(MemberContext ctx, String kind, String locale, String body) {
        ctx.require(Permission.MANAGE_CREDIT);
        if (kind == null || !KINDS.contains(kind)) throw ApiException.badRequest("INVALID_KIND", "Unknown template kind");
        if (locale == null || !LOCALES.contains(locale)) throw ApiException.badRequest("INVALID_LOCALE", "Locale must be es or en");
        if (body == null || body.isBlank() || body.length() > 1500) throw ApiException.badRequest("INVALID_BODY", "Template text is required (max 1500)");
        jdbc.sql("""
                        INSERT INTO message_template (business_id, kind, locale, body) VALUES (:b, :k, :l, :body)
                        ON CONFLICT (business_id, kind, locale) DO UPDATE SET body = :body, rev = nextval('change_rev_seq')
                        """)
                .param("b", ctx.businessId()).param("k", kind).param("l", locale).param("body", body.trim()).update();
        return list(ctx.businessId()).stream().filter(t -> t.kind().equals(kind) && t.locale().equals(locale)).findFirst().orElseThrow();
    }

    /** Volver a la plantilla por defecto. */
    @Transactional
    public void reset(MemberContext ctx, String kind, String locale) {
        ctx.require(Permission.MANAGE_CREDIT);
        jdbc.sql("DELETE FROM message_template WHERE business_id = :b AND kind = :k AND locale = :l").param("b", ctx.businessId()).param("k", kind).param("l", locale).update();
    }
}
