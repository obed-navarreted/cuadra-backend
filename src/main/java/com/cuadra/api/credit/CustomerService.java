package com.cuadra.api.credit;

import com.cuadra.api.common.ApiException;
import com.cuadra.api.common.Audit;
import com.cuadra.api.common.PageResponse;
import com.cuadra.api.common.Phones;
import com.cuadra.api.tenancy.MemberContext;
import com.cuadra.api.tenancy.Role.Permission;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerService {
    private final JdbcClient jdbc;
    private final Audit audit;

    public CustomerService(JdbcClient jdbc, Audit audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record CustomerInput(String name, String phone, String notes, Long creditLimitMinor, Boolean archived) {}

    public record CustomerView(UUID id, String name, String phone, String notes, Long creditLimitMinor, Instant lastReminderAt, boolean archived,
                               long balanceMinor, Instant oldestOpenAt, long rev) {}

    public enum Outcome { CREATED, UPDATED, UNCHANGED }

    public record Result(CustomerView customer, Outcome outcome) {}

    /** Cualquiera que venda puede crear un cliente (desde el cobro); cambiar sus datos o su límite es de quien gestiona el crédito. */
    @Transactional
    public Result upsert(MemberContext ctx, UUID id, CustomerInput in) {
        String name = in.name() == null ? "" : in.name().trim();
        if (name.isEmpty() || name.length() > 120) throw ApiException.badRequest("INVALID_NAME", "Name is required (max 120)");
        if (in.creditLimitMinor() != null && (in.creditLimitMinor() < 0 || in.creditLimitMinor() > 1_000_000_000_000L)) {
            throw ApiException.badRequest("INVALID_LIMIT", "Invalid credit limit");
        }
        String phone = Phones.normalize(in.phone(), country(ctx.businessId()));
        String notes = in.notes() == null || in.notes().isBlank() ? null : in.notes().trim();
        boolean archived = Boolean.TRUE.equals(in.archived());

        Optional<CustomerView> existing = find(ctx.businessId(), id);
        if (existing.isEmpty()) {
            if (jdbc.sql("SELECT count(*) FROM customer WHERE id = :id").param("id", id).query(Integer.class).single() > 0) {
                throw ApiException.conflict("ID_TAKEN", "Id already in use");
            }
            ctx.require(Permission.SELL);
            jdbc.sql("""
                            INSERT INTO customer (id, business_id, name, phone_e164, notes, credit_limit_minor, archived, created_by_member_id)
                            VALUES (:id, :b, :n, :p, :notes, :lim, :arch, :m)
                            """)
                    .param("id", id).param("b", ctx.businessId()).param("n", name).param("p", phone).param("notes", notes)
                    .param("lim", in.creditLimitMinor(), java.sql.Types.BIGINT).param("arch", archived).param("m", ctx.memberId()).update();
            audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "customer.create", "customer", id, name);
            return new Result(get(ctx.businessId(), id), Outcome.CREATED);
        }
        CustomerView c = existing.get();
        if (name.equals(c.name()) && java.util.Objects.equals(phone, c.phone()) && java.util.Objects.equals(notes, c.notes())
                && java.util.Objects.equals(in.creditLimitMinor(), c.creditLimitMinor()) && archived == c.archived()) {
            return new Result(c, Outcome.UNCHANGED);
        }
        ctx.require(Permission.MANAGE_CREDIT);
        jdbc.sql("""
                        UPDATE customer SET name = :n, phone_e164 = :p, notes = :notes, credit_limit_minor = :lim, archived = :arch,
                               rev = nextval('change_rev_seq') WHERE id = :id AND business_id = :b
                        """)
                .param("n", name).param("p", phone).param("notes", notes).param("lim", in.creditLimitMinor(), java.sql.Types.BIGINT)
                .param("arch", archived).param("id", id).param("b", ctx.businessId()).update();
        audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "customer.update", "customer", id, name);
        return new Result(get(ctx.businessId(), id), Outcome.UPDATED);
    }

    public PageResponse<CustomerView> search(UUID businessId, String q, boolean includeArchived, boolean withDebtOnly, int page, int size) {
        size = Math.max(1, Math.min(size, 200));
        page = Math.max(0, page);
        StringBuilder where = new StringBuilder("business_id = :b");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("b", businessId);
        if (!includeArchived) where.append(" AND NOT archived");
        if (withDebtOnly) where.append(" AND balance_minor > 0");
        if (q != null && !q.isBlank()) {
            where.append(" AND (lower(name) LIKE :like OR phone_e164 LIKE :digits)");
            params.put("like", "%" + q.trim().toLowerCase().replace("%", "\\%").replace("_", "\\_") + "%");
            params.put("digits", "%" + q.replaceAll("\\D", "") + "%");
        }
        var count = jdbc.sql("SELECT count(*) FROM customer WHERE " + where);
        var list = jdbc.sql("SELECT * FROM customer WHERE " + where + " ORDER BY balance_minor DESC, lower(name) LIMIT " + size + " OFFSET " + (long) page * size);
        for (var e : params.entrySet()) {
            count = count.param(e.getKey(), e.getValue());
            list = list.param(e.getKey(), e.getValue());
        }
        return PageResponse.of(list.query((rs, n) -> map(rs)).list(), page, size, count.query(Long.class).single());
    }

    public CustomerView get(UUID businessId, UUID id) {
        return find(businessId, id).orElseThrow(() -> ApiException.notFound("CUSTOMER_NOT_FOUND", "Customer not found"));
    }

    public Optional<CustomerView> find(UUID businessId, UUID id) {
        return jdbc.sql("SELECT * FROM customer WHERE id = :id AND business_id = :b").param("id", id).param("b", businessId).query((rs, n) -> map(rs)).optional();
    }

    public List<CustomerView> viewsByIds(UUID businessId, List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.sql("SELECT * FROM customer WHERE business_id = :b AND id IN (:ids)").param("b", businessId).param("ids", ids).query((rs, n) -> map(rs)).list();
    }

    /**
     * El saldo del cliente es la suma de los saldos de SUS fiados: siempre se recalcula desde ellos, nunca se edita.
     * (saldo = fiados − abonos − condonaciones; ver CreditService.recompute).
     */
    void recompute(UUID customerId) {
        if (customerId == null) return;
        jdbc.sql("""
                        UPDATE customer c SET
                               balance_minor = COALESCE((SELECT sum(balance_minor) FROM credit WHERE customer_id = c.id), 0),
                               oldest_open_at = (SELECT min(created_at) FROM credit WHERE customer_id = c.id AND balance_minor > 0),
                               rev = nextval('change_rev_seq')
                         WHERE c.id = :id
                        """)
                .param("id", customerId).update();
    }

    String country(UUID businessId) {
        return jdbc.sql("SELECT country FROM business WHERE id = :b").param("b", businessId).query(String.class).optional().orElse(null);
    }

    private static CustomerView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp last = rs.getTimestamp("last_reminder_at");
        Timestamp oldest = rs.getTimestamp("oldest_open_at");
        return new CustomerView(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("phone_e164"), rs.getString("notes"),
                (Long) rs.getObject("credit_limit_minor"), last == null ? null : last.toInstant(), rs.getBoolean("archived"),
                rs.getLong("balance_minor"), oldest == null ? null : oldest.toInstant(), rs.getLong("rev"));
    }
}
