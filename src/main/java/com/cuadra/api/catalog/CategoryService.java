package com.cuadra.api.catalog;

import com.cuadra.api.common.ApiException;
import com.cuadra.api.tenancy.MemberContext;
import com.cuadra.api.tenancy.Role.Permission;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryService {
    private final JdbcClient jdbc;

    public CategoryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record CategoryView(UUID id, String name, boolean active, long rev) {}

    public record CategoryInput(String name, Boolean active) {}

    public List<CategoryView> list(UUID businessId) {
        return jdbc.sql("SELECT id, name, active, rev FROM category WHERE business_id = :b ORDER BY lower(name)")
                .param("b", businessId).query((rs, n) -> new CategoryView(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getBoolean("active"), rs.getLong("rev"))).list();
    }

    public CategoryView get(UUID businessId, UUID id) {
        return list(businessId).stream().filter(c -> c.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("CATEGORY_NOT_FOUND", "Category not found"));
    }

    @Transactional
    public CategoryView upsert(MemberContext ctx, UUID id, CategoryInput in) {
        ctx.require(Permission.MANAGE_CATALOG);
        if (in.name() == null || in.name().isBlank() || in.name().length() > 80) throw ApiException.badRequest("INVALID_NAME", "Name is required (max 80)");
        boolean exists = jdbc.sql("SELECT count(*) FROM category WHERE id = :id AND business_id = :b").param("id", id).param("b", ctx.businessId())
                .query(Integer.class).single() > 0;
        if (!exists && jdbc.sql("SELECT count(*) FROM category WHERE id = :id").param("id", id).query(Integer.class).single() > 0) {
            throw ApiException.conflict("ID_TAKEN", "Id already in use");
        }
        boolean active = in.active() == null || in.active();
        if (exists) {
            jdbc.sql("UPDATE category SET name = :n, active = :a, rev = nextval('change_rev_seq') WHERE id = :id AND business_id = :b")
                    .param("n", in.name().trim()).param("a", active).param("id", id).param("b", ctx.businessId()).update();
        } else {
            jdbc.sql("INSERT INTO category (id, business_id, name, active) VALUES (:id, :b, :n, :a)")
                    .param("id", id).param("b", ctx.businessId()).param("n", in.name().trim()).param("a", active).update();
        }
        return get(ctx.businessId(), id);
    }
}
