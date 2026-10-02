package com.cuadra.api.catalog;

import com.cuadra.api.business.BusinessDayService;
import com.cuadra.api.common.ApiException;
import com.cuadra.api.common.Audit;
import com.cuadra.api.push.PushDispatcher;
import com.cuadra.api.tenancy.MemberContext;
import com.cuadra.api.tenancy.Role.Permission;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Promociones por cantidad («3 por C$ 100»). Solo dueño y admins las crean o cambian (MANAGE_CATALOG); cualquiera del negocio las lee (la caja las aplica
 * sola, sin conexión). El precio de una venta lo calcula el TELÉFONO con las promociones que tiene: el servidor nunca recalcula una venta que llega.
 * Las fechas son jornadas del negocio (ADR 0011). Cada cambio sube la `rev` (los teléfonos la bajan) y pide un «sincroniza ya» por Firebase.
 */
@Service
public class PromotionService {
    public static final int MAX_PRODUCTS = 500;
    public static final int MAX_QUANTITY = 999;
    private static final long MAX_MINOR = 1_000_000_000_000L;

    private final JdbcClient jdbc;
    private final Audit audit;
    private final JsonMapper mapper;
    private final Clock clock;
    private final BusinessDayService days;
    private final PushDispatcher push;

    public PromotionService(JdbcClient jdbc, Audit audit, JsonMapper mapper, Clock clock, BusinessDayService days, PushDispatcher push) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.mapper = mapper;
        this.clock = clock;
        this.days = days;
        this.push = push;
    }

    /** `startsOn`/`endsOn`: jornadas del negocio, opcionales (`yyyy-MM-dd`). `active` ausente = activa. */
    public record PromotionInput(String name, List<UUID> productIds, Integer quantity, Long priceMinor, Boolean active, LocalDate startsOn, LocalDate endsOn) {}

    /**
     * `state`: ACTIVE (vale hoy) | PAUSED | SCHEDULED (empieza más adelante) | ENDED (ya terminó) | DELETED, según la jornada de hoy del negocio.
     * `deleted`: borrada (los teléfonos la quitan al sincronizar).
     */
    public record PromotionView(UUID id, String name, List<UUID> productIds, int quantity, long priceMinor, boolean active, LocalDate startsOn, LocalDate endsOn,
                                boolean deleted, String state, Instant updatedAt, long rev) {}

    public enum Outcome { CREATED, UPDATED, UNCHANGED }

    public record Result(PromotionView promotion, Outcome outcome) {}

    public List<PromotionView> list(MemberContext ctx) {
        return load(ctx.businessId(), "p.deleted_at IS NULL", Map.of(), " ORDER BY lower(p.name), p.id");
    }

    public PromotionView get(MemberContext ctx, UUID id) {
        return find(ctx.businessId(), id).filter(v -> !v.deleted()).orElseThrow(() -> ApiException.notFound("PROMOTION_NOT_FOUND", "Promotion not found"));
    }

    /** Para la sincronización: incluye las borradas. */
    public List<PromotionView> viewsByIds(UUID businessId, List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return load(businessId, "p.id IN (:ids)", Map.of("ids", ids), "");
    }

    private Optional<PromotionView> find(UUID businessId, UUID id) {
        return load(businessId, "p.id = :id", Map.of("id", id), "").stream().findFirst();
    }

    /** Crea o reemplaza con el id que elige quien la crea (idempotente: repetir lo mismo no cambia nada). */
    @Transactional
    public Result upsert(MemberContext ctx, UUID id, PromotionInput raw) {
        ctx.require(Permission.MANAGE_CATALOG);
        if (id == null) throw ApiException.badRequest("INVALID_PROMOTION", "A promotion needs an id");
        PromotionInput in = normalize(raw);
        List<UUID> products = in.productIds();
        if (!products.isEmpty()) {
            List<Object[]> found = jdbc.sql("SELECT id, pricing, active FROM product WHERE business_id = :b AND id IN (:ids)").param("b", ctx.businessId()).param("ids", products)
                    .query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2), rs.getBoolean(3)}).list();
            if (found.size() != products.size()) throw ApiException.badRequest("INVALID_PRODUCT", "Some products do not exist");
            // Por peso y de precio abierto no entran en una promoción por cantidad (no hay «unidades» iguales que contar).
            for (Object[] f : found) {
                if (!"FIXED".equals(f[1])) throw ApiException.badRequest("PRODUCT_NOT_ELIGIBLE", "Products sold by weight or with an open price cannot be in a promotion").with("productId", f[0].toString());
            }
        }
        Optional<PromotionView> existing = find(ctx.businessId(), id);
        if (existing.isEmpty() && jdbc.sql("SELECT count(*) FROM promotion WHERE id = :id").param("id", id).query(Integer.class).single() > 0) {
            throw ApiException.conflict("ID_TAKEN", "Id already in use");
        }
        boolean active = in.active() == null || in.active();
        Instant now = clock.instant();
        if (existing.isEmpty()) {
            jdbc.sql("""
                            INSERT INTO promotion (id, business_id, name, quantity, price_minor, active, starts_on, ends_on, created_at, updated_at)
                            VALUES (:id, :b, :n, :q, :p, :a, :s, :e, :now, :now)""")
                    .param("id", id).param("b", ctx.businessId()).param("n", in.name()).param("q", in.quantity()).param("p", in.priceMinor()).param("a", active)
                    .param("s", in.startsOn(), java.sql.Types.DATE).param("e", in.endsOn(), java.sql.Types.DATE).param("now", Timestamp.from(now)).update();
            replaceProducts(ctx.businessId(), id, products);
            audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "promotion.create", "promotion", id, detail(in, active, null));
            push.requestSync(ctx.businessId());
            return new Result(find(ctx.businessId(), id).orElseThrow(), Outcome.CREATED);
        }
        PromotionView cur = existing.get();
        if (cur.deleted()) throw ApiException.conflict("PROMOTION_DELETED", "This promotion was deleted");
        if (same(cur, in, active)) return new Result(cur, Outcome.UNCHANGED);
        jdbc.sql("""
                        UPDATE promotion SET name = :n, quantity = :q, price_minor = :p, active = :a, starts_on = :s, ends_on = :e, updated_at = :now,
                               rev = nextval('change_rev_seq') WHERE id = :id AND business_id = :b""")
                .param("id", id).param("b", ctx.businessId()).param("n", in.name()).param("q", in.quantity()).param("p", in.priceMinor()).param("a", active)
                .param("s", in.startsOn(), java.sql.Types.DATE).param("e", in.endsOn(), java.sql.Types.DATE).param("now", Timestamp.from(now)).update();
        replaceProducts(ctx.businessId(), id, products);
        String action = cur.active() != active && same(cur, new PromotionInput(in.name(), in.productIds(), in.quantity(), in.priceMinor(), cur.active(), in.startsOn(), in.endsOn()), cur.active())
                ? (active ? "promotion.resume" : "promotion.pause") : "promotion.update";
        audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), action, "promotion", id, detail(in, active, cur));
        push.requestSync(ctx.businessId());
        return new Result(find(ctx.businessId(), id).orElseThrow(), Outcome.UPDATED);
    }

    /** Pausar o reanudar sin tocar lo demás. */
    @Transactional
    public PromotionView setActive(MemberContext ctx, UUID id, boolean active) {
        PromotionView cur = get(ctx, id);
        return upsert(ctx, id, new PromotionInput(cur.name(), cur.productIds(), cur.quantity(), cur.priceMinor(), active, cur.startsOn(), cur.endsOn())).promotion();
    }

    /** Borrar: queda guardada como borrada (las ventas la nombran) y los teléfonos la quitan. Repetirlo no cambia nada. */
    @Transactional
    public void delete(MemberContext ctx, UUID id) {
        ctx.require(Permission.MANAGE_CATALOG);
        PromotionView cur = find(ctx.businessId(), id).orElseThrow(() -> ApiException.notFound("PROMOTION_NOT_FOUND", "Promotion not found"));
        if (cur.deleted()) return;
        jdbc.sql("UPDATE promotion SET deleted_at = :now, active = false, updated_at = :now, rev = nextval('change_rev_seq') WHERE id = :id AND business_id = :b")
                .param("now", Timestamp.from(clock.instant())).param("id", id).param("b", ctx.businessId()).update();
        audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "promotion.delete", "promotion", id, mapper.writeValueAsString(Map.of("name", cur.name())));
        push.requestSync(ctx.businessId());
    }

    // ---------- internos ----------

    private static PromotionInput normalize(PromotionInput in) {
        if (in == null) throw ApiException.badRequest("INVALID_PROMOTION", "Missing body");
        String name = in.name() == null ? "" : in.name().trim();
        if (name.isEmpty() || name.length() > 80) throw ApiException.badRequest("INVALID_NAME", "Name is required (max 80)");
        if (in.quantity() == null || in.quantity() < 2 || in.quantity() > MAX_QUANTITY) throw ApiException.badRequest("INVALID_QUANTITY", "Quantity must be between 2 and " + MAX_QUANTITY);
        if (in.priceMinor() == null || in.priceMinor() <= 0 || in.priceMinor() > MAX_MINOR) throw ApiException.badRequest("INVALID_PRICE", "Invalid price");
        List<UUID> ids = in.productIds() == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(in.productIds().stream().filter(Objects::nonNull).toList()));
        if (ids.isEmpty()) throw ApiException.badRequest("PRODUCTS_REQUIRED", "Choose at least one product");
        if (ids.size() > MAX_PRODUCTS) throw ApiException.badRequest("TOO_MANY_PRODUCTS", "At most " + MAX_PRODUCTS + " products");
        if (in.startsOn() != null && in.endsOn() != null && in.endsOn().isBefore(in.startsOn())) throw ApiException.badRequest("INVALID_DATES", "The end date is before the start date");
        return new PromotionInput(name, ids, in.quantity(), in.priceMinor(), in.active(), in.startsOn(), in.endsOn());
    }

    private static boolean same(PromotionView c, PromotionInput in, boolean active) {
        return c.name().equals(in.name()) && c.quantity() == in.quantity() && c.priceMinor() == in.priceMinor() && c.active() == active
                && Objects.equals(c.startsOn(), in.startsOn()) && Objects.equals(c.endsOn(), in.endsOn())
                && new LinkedHashSet<>(c.productIds()).equals(new LinkedHashSet<>(in.productIds()));
    }

    private void replaceProducts(UUID businessId, UUID id, List<UUID> products) {
        jdbc.sql("DELETE FROM promotion_product WHERE promotion_id = :id").param("id", id).update();
        for (UUID p : products) {
            jdbc.sql("INSERT INTO promotion_product (promotion_id, product_id, business_id) VALUES (:id, :p, :b)").param("id", id).param("p", p).param("b", businessId).update();
        }
    }

    private String detail(PromotionInput in, boolean active, PromotionView before) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", in.name());
        d.put("quantity", in.quantity());
        d.put("priceMinor", in.priceMinor());
        d.put("active", active);
        d.put("products", in.productIds().size());
        if (in.startsOn() != null) d.put("startsOn", in.startsOn().toString());
        if (in.endsOn() != null) d.put("endsOn", in.endsOn().toString());
        if (before != null) {
            Map<String, Object> was = new LinkedHashMap<>();
            was.put("name", before.name());
            was.put("quantity", before.quantity());
            was.put("priceMinor", before.priceMinor());
            was.put("active", before.active());
            was.put("products", before.productIds().size());
            d.put("before", was);
        }
        return mapper.writeValueAsString(d);
    }

    private List<PromotionView> load(UUID businessId, String clause, Map<String, Object> params, String tail) {
        LocalDate today = days.info(businessId).dateOf(clock.instant());
        var q = jdbc.sql("SELECT p.* FROM promotion p WHERE p.business_id = :b AND " + clause + tail).param("b", businessId);
        for (var e : params.entrySet()) q = q.param(e.getKey(), e.getValue());
        record Head(UUID id, String name, int qty, long price, boolean active, LocalDate starts, LocalDate ends, boolean deleted, Instant updated, long rev) {}
        List<Head> heads = q.query((rs, n) -> new Head(rs.getObject("id", UUID.class), rs.getString("name"), rs.getInt("quantity"), rs.getLong("price_minor"),
                rs.getBoolean("active"), rs.getObject("starts_on", LocalDate.class), rs.getObject("ends_on", LocalDate.class), rs.getTimestamp("deleted_at") != null,
                rs.getTimestamp("updated_at").toInstant(), rs.getLong("rev"))).list();
        if (heads.isEmpty()) return List.of();
        Map<UUID, List<UUID>> products = new LinkedHashMap<>();
        jdbc.sql("SELECT promotion_id, product_id FROM promotion_product WHERE business_id = :b AND promotion_id IN (:ids) ORDER BY product_id")
                .param("b", businessId).param("ids", heads.stream().map(Head::id).toList())
                .query((rs, n) -> products.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>()).add(rs.getObject(2, UUID.class))).list();
        return heads.stream().map(h -> new PromotionView(h.id, h.name, products.getOrDefault(h.id, List.of()), h.qty, h.price, h.active, h.starts, h.ends, h.deleted,
                state(h.deleted, h.active, h.starts, h.ends, today), h.updated, h.rev)).toList();
    }

    /** El estado que se muestra, con la jornada de hoy del negocio. */
    public static String state(boolean deleted, boolean active, LocalDate startsOn, LocalDate endsOn, LocalDate today) {
        if (deleted) return "DELETED";
        if (endsOn != null && today.isAfter(endsOn)) return "ENDED";
        if (!active) return "PAUSED";
        if (startsOn != null && today.isBefore(startsOn)) return "SCHEDULED";
        return "ACTIVE";
    }
}
