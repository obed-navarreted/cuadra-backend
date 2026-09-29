package com.cuadra.api.stock;

import com.cuadra.api.common.ApiException;
import com.cuadra.api.common.Phones;
import com.cuadra.api.tenancy.MemberContext;
import com.cuadra.api.tenancy.Role.Permission;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Proveedores. Son opcionales: una compra puede llevar solo el nombre en texto. */
@Service
public class SupplierService {
    private final JdbcClient jdbc;

    public SupplierService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record SupplierInput(String name, String phone, String notes, Boolean active) {}

    /** `balanceMinor`: lo que se les debe (compras vigentes menos pagos vigentes). El teléfono lo recalcula con lo que conoce. */
    public record SupplierView(UUID id, String name, String phone, String notes, boolean active, long balanceMinor, long rev) {}

    private static final String SELECT = """
            SELECT s.*, coalesce((SELECT sum(greatest(p.total_minor - coalesce((SELECT sum(a.amount_minor) FROM supplier_payment a WHERE a.purchase_id = p.id AND a.voided_at IS NULL), 0), 0))
                                    FROM purchase p WHERE p.supplier_id = s.id AND p.voided_at IS NULL), 0) AS balance
              FROM supplier s""";

    @Transactional
    public SupplierView upsert(MemberContext ctx, UUID id, SupplierInput in) {
        ctx.require(Permission.MANAGE_STOCK);
        String name = in.name() == null ? "" : in.name().trim();
        if (name.isEmpty() || name.length() > 120) throw ApiException.badRequest("INVALID_NAME", "Name is required (max 120)");
        String notes = in.notes() == null || in.notes().isBlank() ? null : in.notes().trim();
        if (notes != null && notes.length() > 500) throw ApiException.badRequest("INVALID_NOTES", "Notes too long");
        String country = jdbc.sql("SELECT country FROM business WHERE id = :b").param("b", ctx.businessId()).query(String.class).single();
        String phone = Phones.normalize(in.phone(), country);
        boolean active = in.active() == null || in.active();
        boolean exists = jdbc.sql("SELECT count(*) FROM supplier WHERE id = :id AND business_id = :b").param("id", id).param("b", ctx.businessId()).query(Integer.class).single() > 0;
        if (!exists) {
            if (jdbc.sql("SELECT count(*) FROM supplier WHERE id = :id").param("id", id).query(Integer.class).single() > 0) throw ApiException.conflict("ID_TAKEN", "Id already in use");
            jdbc.sql("INSERT INTO supplier (id, business_id, name, phone_e164, notes, active) VALUES (:id, :b, :n, :p, :notes, :a)")
                    .param("id", id).param("b", ctx.businessId()).param("n", name).param("p", phone).param("notes", notes).param("a", active).update();
        } else {
            jdbc.sql("UPDATE supplier SET name = :n, phone_e164 = :p, notes = :notes, active = :a, rev = nextval('change_rev_seq') WHERE id = :id AND business_id = :b")
                    .param("id", id).param("b", ctx.businessId()).param("n", name).param("p", phone).param("notes", notes).param("a", active).update();
        }
        return get(ctx.businessId(), id);
    }

    public SupplierView get(UUID businessId, UUID id) {
        return find(businessId, id).orElseThrow(() -> ApiException.notFound("SUPPLIER_NOT_FOUND", "Supplier not found"));
    }

    public Optional<SupplierView> find(UUID businessId, UUID id) {
        return jdbc.sql(SELECT + " WHERE s.id = :id AND s.business_id = :b").param("id", id).param("b", businessId).query((rs, n) -> map(rs)).optional();
    }

    public List<SupplierView> list(MemberContext ctx, boolean includeInactive) {
        ctx.require(Permission.MANAGE_STOCK);
        return jdbc.sql(SELECT + " WHERE s.business_id = :b" + (includeInactive ? "" : " AND s.active") + " ORDER BY lower(s.name)").param("b", ctx.businessId()).query((rs, n) -> map(rs)).list();
    }

    public List<SupplierView> viewsByIds(UUID businessId, List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.sql(SELECT + " WHERE s.business_id = :b AND s.id IN (:ids)").param("b", businessId).param("ids", ids).query((rs, n) -> map(rs)).list();
    }

    private static SupplierView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SupplierView(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("phone_e164"), rs.getString("notes"), rs.getBoolean("active"), rs.getLong("balance"), rs.getLong("rev"));
    }
}
