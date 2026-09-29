package com.cuadra.api.cash;

import com.cuadra.api.common.ApiException;
import com.cuadra.api.common.Audit;
import com.cuadra.api.common.PageResponse;
import com.cuadra.api.business.BusinessDayService;
import com.cuadra.api.tenancy.MemberContext;
import com.cuadra.api.tenancy.Role;
import com.cuadra.api.tenancy.Role.Permission;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Todo el dinero que sale queda registrado. Solo un gasto pagado DEL CAJÓN (`CASH_DRAWER`) afecta el cierre; los demás (banco, tarjeta,
 * "puso el dueño") se anotan para saber cuánto se gasta. Un gasto no se edita: se anula (con motivo) y se registra de nuevo.
 */
@Service
public class ExpenseService {
    private static final long MAX_MINOR = 1_000_000_000_000L;
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    private static final Set<String> SOURCES = Set.of("CASH_DRAWER", "BANK", "CARD", "OWNER", "OTHER");

    private final JdbcClient jdbc;
    private final Audit audit;
    private final Clock clock;
    private final RegisterResolver registers;
    private final BusinessDayService days;

    public ExpenseService(JdbcClient jdbc, Audit audit, Clock clock, RegisterResolver registers, BusinessDayService days) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        this.registers = registers;
        this.days = days;
    }

    public record ExpenseInput(UUID categoryId, String description, Long amountMinor, String source, UUID cashRegisterId, Instant occurredAt) {}

    public record ExpenseView(UUID id, UUID categoryId, String categoryKey, String categoryName, String description, long amountMinor, String source, UUID cashRegisterId,
                              String createdByName, UUID createdById, Instant occurredAt, boolean voided, String voidReason, long rev) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "ExpenseCategoryView")
    public record CategoryView(UUID id, String key, String name, boolean active, long rev) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "ExpenseCategoryInput")
    public record CategoryInput(String name, Boolean active) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "ExpenseSummary")
    public record Summary(LocalDate from, LocalDate to, long cashDrawerMinor, long otherMinor, long count, List<ByCategory> byCategory) {}

    public record ByCategory(UUID categoryId, String key, String name, long amountMinor) {}

    public enum Outcome { CREATED, UNCHANGED }

    public record Result(ExpenseView expense, Outcome outcome) {}

    @Transactional
    public Result upsert(MemberContext ctx, UUID id, ExpenseInput in) {
        if (in.amountMinor() == null || in.amountMinor() <= 0 || in.amountMinor() > MAX_MINOR) throw ApiException.badRequest("INVALID_AMOUNT", "Invalid amount");
        if (in.source() == null || !SOURCES.contains(in.source())) throw ApiException.badRequest("INVALID_SOURCE", "Invalid source");
        String description = in.description() == null || in.description().isBlank() ? null : in.description().trim();
        if (description != null && description.length() > 200) throw ApiException.badRequest("INVALID_DESCRIPTION", "Description too long");
        // El cajero registra lo que sale del cajón; lo que sale de otro lado lo registra quien administra.
        ctx.require("CASH_DRAWER".equals(in.source()) ? Permission.SELL : Permission.MANAGE_EXPENSES);
        if (in.categoryId() != null && jdbc.sql("SELECT count(*) FROM expense_category WHERE id = :c AND business_id = :b AND active").param("c", in.categoryId()).param("b", ctx.businessId())
                .query(Integer.class).single() == 0) {
            throw ApiException.badRequest("INVALID_CATEGORY", "Category not found");
        }
        Instant now = clock.instant();
        Instant at = in.occurredAt() == null || in.occurredAt().isAfter(now.plus(CLOCK_SKEW)) ? now : in.occurredAt();

        Optional<ExpenseView> existing = find(ctx.businessId(), id);
        if (existing.isPresent()) {
            ExpenseView e = existing.get();
            boolean same = e.amountMinor() == in.amountMinor() && e.source().equals(in.source()) && java.util.Objects.equals(e.description(), description) && java.util.Objects.equals(e.categoryId(), in.categoryId());
            if (same) return new Result(e, Outcome.UNCHANGED);
            throw ApiException.conflict("EXPENSE_IMMUTABLE", "An expense is not edited: void it and register it again");
        }
        if (jdbc.sql("SELECT count(*) FROM expense WHERE id = :id").param("id", id).query(Integer.class).single() > 0) throw ApiException.conflict("ID_TAKEN", "Id already in use");
        UUID register = "CASH_DRAWER".equals(in.source()) ? registers.resolve(ctx, in.cashRegisterId()) : (in.cashRegisterId() == null ? null : registers.resolve(ctx, in.cashRegisterId()));
        jdbc.sql("""
                        INSERT INTO expense (id, business_id, category_id, description, amount_minor, source, cash_register_id, created_by_member_id, device_id, occurred_at)
                        VALUES (:id, :b, :c, :d, :a, :s, :r, :m, :dev, :at)
                        """)
                .param("id", id).param("b", ctx.businessId()).param("c", in.categoryId(), java.sql.Types.OTHER).param("d", description).param("a", in.amountMinor())
                .param("s", in.source()).param("r", register, java.sql.Types.OTHER).param("m", ctx.memberId()).param("dev", ctx.deviceId(), java.sql.Types.OTHER)
                .param("at", Timestamp.from(at)).update();
        audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "expense.create", "expense", id, in.source() + " " + in.amountMinor());
        return new Result(get(ctx.businessId(), id), Outcome.CREATED);
    }

    @Transactional
    public ExpenseView voidExpense(MemberContext ctx, UUID id, String reason) {
        ctx.require(Permission.MANAGE_EXPENSES);
        ExpenseView e = get(ctx.businessId(), id);
        if (e.voided()) return e;
        // Un gasto nacido de un pago a proveedor se anula anulando el pago; si no, la cuenta por pagar y el cierre dirían cosas distintas.
        if (jdbc.sql("SELECT count(*) FROM expense WHERE id = :id AND ref_type IS NOT NULL").param("id", id).query(Integer.class).single() > 0) {
            throw ApiException.conflict("EXPENSE_LINKED", "Void the supplier payment instead");
        }
        jdbc.sql("UPDATE expense SET voided_by_member_id = :m, voided_at = :now, void_reason = :r, rev = nextval('change_rev_seq') WHERE id = :id AND business_id = :b")
                .param("m", ctx.memberId()).param("now", Timestamp.from(clock.instant())).param("r", reason == null || reason.isBlank() ? null : reason.trim()).param("id", id).param("b", ctx.businessId()).update();
        audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "expense.void", "expense", id, reason);
        return get(ctx.businessId(), id);
    }

    public ExpenseView get(UUID businessId, UUID id) {
        return find(businessId, id).orElseThrow(() -> ApiException.notFound("EXPENSE_NOT_FOUND", "Expense not found"));
    }

    private static final String SELECT = """
            SELECT e.*, c.key AS category_key, c.name AS category_name, m.display_name AS created_name
              FROM expense e LEFT JOIN expense_category c ON c.id = e.category_id JOIN member m ON m.id = e.created_by_member_id""";

    public Optional<ExpenseView> find(UUID businessId, UUID id) {
        return jdbc.sql(SELECT + " WHERE e.id = :id AND e.business_id = :b").param("id", id).param("b", businessId).query((rs, n) -> map(rs)).optional();
    }

    public List<ExpenseView> viewsByIds(UUID businessId, List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.sql(SELECT + " WHERE e.business_id = :b AND e.id IN (:ids)").param("b", businessId).param("ids", ids).query((rs, n) -> map(rs)).list();
    }

    /** Un cajero ve solo los gastos que él registró; dueño y admins ven todos. */
    public PageResponse<ExpenseView> list(MemberContext ctx, LocalDate from, LocalDate to, String source, UUID categoryId, boolean includeVoided, int page, int size) {
        size = Math.max(1, Math.min(size, 200));
        page = Math.max(0, page);
        BusinessDayService.Info info = days.info(ctx.businessId());
        StringBuilder where = new StringBuilder("e.business_id = :b");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("b", ctx.businessId());
        if (from != null) { where.append(" AND e.occurred_at >= :from"); params.put("from", Timestamp.from(info.startOf(from))); }
        if (to != null) { where.append(" AND e.occurred_at < :to"); params.put("to", Timestamp.from(info.endOf(to))); }
        if (source != null) { where.append(" AND e.source = :src"); params.put("src", source); }
        if (categoryId != null) { where.append(" AND e.category_id = :cat"); params.put("cat", categoryId); }
        if (!includeVoided) where.append(" AND e.voided_at IS NULL");
        if (ctx.role() == Role.CASHIER) { where.append(" AND e.created_by_member_id = :me"); params.put("me", ctx.memberId()); }
        var count = jdbc.sql("SELECT count(*) FROM expense e WHERE " + where);
        var rows = jdbc.sql(SELECT + " WHERE " + where + " ORDER BY e.occurred_at DESC, e.id LIMIT " + size + " OFFSET " + (long) page * size);
        for (var e : params.entrySet()) { count = count.param(e.getKey(), e.getValue()); rows = rows.param(e.getKey(), e.getValue()); }
        return PageResponse.of(rows.query((rs, n) -> map(rs)).list(), page, size, count.query(Long.class).single());
    }

    /** Cuánto salió del cajón y cuánto de otros medios en un rango de jornadas, por categoría. */
    public Summary summary(MemberContext ctx, LocalDate from, LocalDate to) {
        BusinessDayService.Info info = days.info(ctx.businessId());
        LocalDate f = from != null ? from : info.dateOf(clock.instant());
        LocalDate t = to != null ? to : f;
        boolean own = ctx.role() == Role.CASHIER;
        var q = jdbc.sql("""
                        SELECT e.category_id, c.key, c.name, e.source, sum(e.amount_minor) AS total, count(*) AS n
                          FROM expense e LEFT JOIN expense_category c ON c.id = e.category_id
                         WHERE e.business_id = :b AND e.voided_at IS NULL AND e.occurred_at >= :from AND e.occurred_at < :to""" + (own ? " AND e.created_by_member_id = :me" : "")
                + " GROUP BY e.category_id, c.key, c.name, e.source")
                .param("b", ctx.businessId()).param("from", Timestamp.from(info.startOf(f))).param("to", Timestamp.from(info.endOf(t)));
        if (own) q = q.param("me", ctx.memberId());
        long[] totals = new long[3];
        Map<UUID, ByCategory> cats = new LinkedHashMap<>();
        q.query((rs, n) -> {
            long total = rs.getLong("total");
            totals[rs.getString("source").equals("CASH_DRAWER") ? 0 : 1] += total;
            totals[2] += rs.getLong("n");
            UUID cid = rs.getObject("category_id", UUID.class);
            UUID key = cid == null ? new UUID(0, 0) : cid;
            ByCategory prev = cats.get(key);
            cats.put(key, new ByCategory(cid, rs.getString("key"), rs.getString("name"), (prev == null ? 0 : prev.amountMinor()) + total));
            return null;
        }).list();
        return new Summary(f, t, totals[0], totals[1], totals[2], cats.values().stream().sorted((a, b) -> Long.compare(b.amountMinor(), a.amountMinor())).toList());
    }

    // ---------- categorías ----------

    public List<CategoryView> categories(UUID businessId) {
        return jdbc.sql("SELECT id, key, name, active, rev FROM expense_category WHERE business_id = :b ORDER BY coalesce(name, key)").param("b", businessId)
                .query((rs, n) -> new CategoryView(rs.getObject("id", UUID.class), rs.getString("key"), rs.getString("name"), rs.getBoolean("active"), rs.getLong("rev"))).list();
    }

    public List<CategoryView> categoriesByIds(UUID businessId, List<UUID> ids) {
        return categories(businessId).stream().filter(c -> ids.contains(c.id())).toList();
    }

    /** Crear o renombrar una categoría. Una de fábrica renombrada conserva su `key` y gana `name`. */
    @Transactional
    public CategoryView upsertCategory(MemberContext ctx, UUID id, CategoryInput in) {
        ctx.require(Permission.MANAGE_EXPENSES);
        String name = in.name() == null ? "" : in.name().trim();
        boolean exists = jdbc.sql("SELECT count(*) FROM expense_category WHERE id = :id AND business_id = :b").param("id", id).param("b", ctx.businessId()).query(Integer.class).single() > 0;
        if (!exists && jdbc.sql("SELECT count(*) FROM expense_category WHERE id = :id").param("id", id).query(Integer.class).single() > 0) throw ApiException.conflict("ID_TAKEN", "Id already in use");
        boolean active = in.active() == null || in.active();
        if (exists) {
            if (!name.isEmpty() && name.length() > 60) throw ApiException.badRequest("INVALID_NAME", "Name too long");
            jdbc.sql("UPDATE expense_category SET name = CASE WHEN :n = '' THEN name ELSE :n END, active = :a, rev = nextval('change_rev_seq') WHERE id = :id AND business_id = :b")
                    .param("n", name).param("a", active).param("id", id).param("b", ctx.businessId()).update();
        } else {
            if (name.isEmpty() || name.length() > 60) throw ApiException.badRequest("INVALID_NAME", "Name is required (max 60)");
            jdbc.sql("INSERT INTO expense_category (id, business_id, name, active) VALUES (:id, :b, :n, :a)").param("id", id).param("b", ctx.businessId()).param("n", name).param("a", active).update();
        }
        return categories(ctx.businessId()).stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    private static ExpenseView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ExpenseView(rs.getObject("id", UUID.class), rs.getObject("category_id", UUID.class), rs.getString("category_key"), rs.getString("category_name"), rs.getString("description"),
                rs.getLong("amount_minor"), rs.getString("source"), rs.getObject("cash_register_id", UUID.class), rs.getString("created_name"), rs.getObject("created_by_member_id", UUID.class),
                rs.getTimestamp("occurred_at").toInstant(), rs.getTimestamp("voided_at") != null, rs.getString("void_reason"), rs.getLong("rev"));
    }
}
