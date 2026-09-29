package com.cuadra.api.stock;

import com.cuadra.api.common.ApiException;
import com.cuadra.api.common.Audit;
import com.cuadra.api.common.PageResponse;
import com.cuadra.api.tenancy.MemberContext;
import com.cuadra.api.tenancy.Role.Permission;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Existencias. El stock es la suma de los movimientos, que solo se agregan: nada se edita ni se borra, un error se corrige con otro movimiento.
 * `product.stock_milli` es una caché que se actualiza en la misma transacción. Un producto sin `track_stock` no genera movimientos.
 * Se permite el stock negativo (vender nunca se bloquea): aparece en "Revisar".
 */
@Service
public class StockService {
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    private static final long MAX_MILLI = 1_000_000_000_000L;
    /** Lo que una persona puede registrar a mano. Los demás tipos los genera el sistema (ventas y compras). */
    private static final Set<String> MANUAL = Set.of("INITIAL", "ADJUSTMENT", "DAMAGE", "RETURN");

    private final JdbcClient jdbc;
    private final Audit audit;
    private final Clock clock;
    private final com.cuadra.api.notification.NotificationService notifications;

    public StockService(JdbcClient jdbc, Audit audit, Clock clock, com.cuadra.api.notification.NotificationService notifications) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        this.notifications = notifications;
    }

    /**
     * kind: INITIAL y ADJUSTMENT llevan `countedMilli` (lo que hay contado; el sistema calcula la diferencia contra lo que dice el sistema);
     * DAMAGE lleva la cantidad perdida en positivo; RETURN la cantidad devuelta en positivo.
     */
    public record MovementInput(UUID productId, String kind, Long quantityMilli, Long countedMilli, Long unitCostMinor, String note, Instant occurredAt) {}

    public record MovementView(UUID id, UUID productId, String kind, long quantityMilli, Long unitCostMinor, String refType, UUID refId, String note,
                               String createdByName, UUID createdById, Instant occurredAt, long rev) {}

    public enum Outcome { CREATED, UNCHANGED }

    public record Result(MovementView movement, Outcome outcome) {}

    private record Product(UUID id, boolean track, long stock, String name) {}

    @Transactional
    public Result add(MemberContext ctx, UUID id, MovementInput in) {
        ctx.require(Permission.MANAGE_STOCK);
        if (in.kind() == null || !MANUAL.contains(in.kind())) throw ApiException.badRequest("INVALID_KIND", "Invalid movement kind");
        if (in.productId() == null) throw ApiException.badRequest("INVALID_PRODUCT", "Product is required");
        String note = in.note() == null || in.note().isBlank() ? null : in.note().trim();
        if (note != null && note.length() > 200) throw ApiException.badRequest("INVALID_NOTE", "Note too long");
        Optional<MovementView> existing = find(ctx.businessId(), id);
        if (existing.isPresent()) return new Result(existing.get(), Outcome.UNCHANGED);
        if (jdbc.sql("SELECT count(*) FROM stock_movement WHERE id = :id").param("id", id).query(Integer.class).single() > 0) throw ApiException.conflict("ID_TAKEN", "Id already in use");

        Product p = lockProduct(ctx.businessId(), in.productId());
        if (!p.track) throw ApiException.conflict("STOCK_NOT_TRACKED", "This product does not track stock");
        long qty = switch (in.kind()) {
            case "INITIAL", "ADJUSTMENT" -> {
                if (in.countedMilli() == null || in.countedMilli() < 0 || in.countedMilli() > MAX_MILLI) throw ApiException.badRequest("INVALID_QUANTITY", "Counted quantity is required");
                // La diferencia se calcula contra lo que el servidor sabe ahora: dos teléfonos que cuentan lo mismo convergen a ese conteo.
                yield in.countedMilli() - p.stock;
            }
            case "DAMAGE" -> -positive(in.quantityMilli());
            default -> positive(in.quantityMilli());
        };
        Instant now = clock.instant();
        Instant at = in.occurredAt() == null || in.occurredAt().isAfter(now.plus(CLOCK_SKEW)) ? now : in.occurredAt();
        insert(ctx.businessId(), id, p.id, in.kind(), qty, in.unitCostMinor(), null, null, note, ctx.memberId(), ctx.deviceId(), at);
        audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "stock." + in.kind().toLowerCase(), "product", p.id, "qty=" + qty);
        return new Result(get(ctx.businessId(), id), Outcome.CREATED);
    }

    private static long positive(Long q) {
        if (q == null || q <= 0 || q > MAX_MILLI) throw ApiException.badRequest("INVALID_QUANTITY", "Quantity must be positive");
        return q;
    }

    /**
     * Deja el stock de una venta como debe ser ahora: lo que la venta consume hoy menos lo que ya se descontó. Como parte del estado de la venta
     * (no de la operación recibida), reenviar, editar, eliminar o recibir versiones fuera de orden siempre converge al mismo número.
     * Solo cuentan los productos con stock activo y ventas posteriores a que se empezó a contar.
     */
    public void reconcileSale(MemberContext ctx, UUID saleId) {
        Map<UUID, Long> desired = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT i.product_id, sum(i.quantity_milli) AS q
                          FROM sale_item i JOIN sale s ON s.id = i.sale_id JOIN product p ON p.id = i.product_id
                         WHERE i.sale_id = :s AND s.status = 'COMPLETED' AND p.track_stock AND s.completed_at >= p.stock_tracked_since
                         GROUP BY i.product_id""")
                .param("s", saleId).query((rs, n) -> {
                    desired.put(rs.getObject("product_id", UUID.class), rs.getLong("q"));
                    return null;
                }).list();
        Map<UUID, Long> current = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT m.product_id, -sum(m.quantity_milli) AS q
                          FROM stock_movement m JOIN product p ON p.id = m.product_id
                         WHERE m.ref_type = 'SALE' AND m.ref_id = :s AND p.track_stock
                         GROUP BY m.product_id""")
                .param("s", saleId).query((rs, n) -> {
                    current.put(rs.getObject("product_id", UUID.class), rs.getLong("q"));
                    return null;
                }).list();
        // Orden fijo de bloqueo entre productos: dos ventas simultáneas con los mismos productos no se bloquean entre sí.
        Set<UUID> products = new TreeSet<>();
        products.addAll(desired.keySet());
        products.addAll(current.keySet());
        Instant now = clock.instant();
        for (UUID product : products) {
            lockProduct(ctx.businessId(), product);
            long delta = desired.getOrDefault(product, 0L) - current.getOrDefault(product, 0L);
            if (delta == 0) continue;
            Instant at = delta > 0 ? completedAt(saleId, now) : now;
            insert(ctx.businessId(), UUID.randomUUID(), product, delta > 0 ? "SALE" : "SALE_REVERSAL", -delta, null, "SALE", saleId, null, ctx.memberId(), ctx.deviceId(), at);
        }
    }

    private Instant completedAt(UUID saleId, Instant fallback) {
        return jdbc.sql("SELECT completed_at FROM sale WHERE id = :s").param("s", saleId).query((rs, n) -> rs.getTimestamp(1) == null ? fallback : rs.getTimestamp(1).toInstant()).optional().orElse(fallback);
    }

    /** Movimiento generado por el sistema (compras y sus anulaciones). Ignora productos que no llevan stock. */
    public void recordSystem(MemberContext ctx, UUID id, UUID productId, String kind, long quantityMilli, Long unitCostMinor, String refType, UUID refId, Instant at) {
        Product p = lockProduct(ctx.businessId(), productId);
        if (!p.track) return;
        if (jdbc.sql("SELECT count(*) FROM stock_movement WHERE id = :id").param("id", id).query(Integer.class).single() > 0) return;
        insert(ctx.businessId(), id, productId, kind, quantityMilli, unitCostMinor, refType, refId, null, ctx.memberId(), ctx.deviceId(), at);
    }

    private void insert(UUID businessId, UUID id, UUID productId, String kind, long qty, Long unitCost, String refType, UUID refId, String note,
                        UUID memberId, UUID deviceId, Instant at) {
        long before = jdbc.sql("SELECT stock_milli FROM product WHERE id = :p").param("p", productId).query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO stock_movement (id, business_id, product_id, kind, quantity_milli, unit_cost_minor, ref_type, ref_id, note, created_by_member_id, device_id, occurred_at)
                        VALUES (:id, :b, :p, :k, :q, :c, :rt, :ri, :n, :m, :d, :at)""")
                .param("id", id).param("b", businessId).param("p", productId).param("k", kind).param("q", qty).param("c", unitCost, java.sql.Types.BIGINT)
                .param("rt", refType).param("ri", refId, java.sql.Types.OTHER).param("n", note).param("m", memberId, java.sql.Types.OTHER)
                .param("d", deviceId, java.sql.Types.OTHER).param("at", Timestamp.from(at)).update();
        // La caché sale de la suma, no de "más" el movimiento: así nunca se desalinea aunque algo la haya tocado.
        jdbc.sql("""
                        UPDATE product SET stock_milli = (SELECT coalesce(sum(quantity_milli), 0) FROM stock_movement WHERE product_id = :p),
                               updated_at = now(), rev = nextval('change_rev_seq')
                         WHERE id = :p AND business_id = :b""")
                .param("p", productId).param("b", businessId).update();
        notifyIfLow(businessId, productId, before);
    }

    /**
     * Avisa cuando un producto cruza su mínimo o se agota (una vez por producto y día, aunque siga bajando).
     * El aviso es del estado ("ahora está bajo"), no del movimiento: un conteo hacia abajo también avisa.
     */
    private void notifyIfLow(UUID businessId, UUID productId, long before) {
        var p = jdbc.sql("SELECT name, unit, stock_milli, min_stock_milli, track_stock FROM product WHERE id = :p").param("p", productId)
                .query((rs, n) -> new Object[] {rs.getString(1), rs.getString(2), rs.getLong(3), rs.getObject(4), rs.getBoolean(5)}).single();
        if (!(Boolean) p[4]) return;
        long after = (Long) p[2];
        Long min = (Long) p[3];
        String day = java.time.LocalDate.now(clock).toString();
        // La app formatea cantidad y unidad en su idioma; este texto es solo el respaldo del servidor.
        String unitWord = switch ((String) p[1]) { case "LB" -> " lb"; case "KG" -> " kg"; case "L" -> " L"; case "M" -> " m"; default -> ""; };
        String stockText = java.math.BigDecimal.valueOf(after, 3).stripTrailingZeros().toPlainString() + unitWord;
        if (after <= 0 && before > 0) {
            notifications.notify(businessId, com.cuadra.api.notification.NotificationService.Type.OUT_OF_STOCK, java.util.Map.of("productId", productId.toString(), "productName", p[0]), null, null,
                    "OUT_OF_STOCK:" + productId + ":" + day, "cuadra://inventario?filtro=bajo");
        } else if (min != null && after > 0 && after <= min && before > min) {
            notifications.notify(businessId, com.cuadra.api.notification.NotificationService.Type.LOW_STOCK, java.util.Map.of("productId", productId.toString(), "productName", p[0], "stock", stockText, "stockMilli", after, "unit", p[1]), null, null,
                    "LOW_STOCK:" + productId + ":" + day, "cuadra://inventario?filtro=bajo");
        }
    }

    private Product lockProduct(UUID businessId, UUID productId) {
        return jdbc.sql("SELECT id, track_stock, stock_milli, name FROM product WHERE id = :p AND business_id = :b FOR UPDATE").param("p", productId).param("b", businessId)
                .query((rs, n) -> new Product(rs.getObject("id", UUID.class), rs.getBoolean("track_stock"), rs.getLong("stock_milli"), rs.getString("name")))
                .optional().orElseThrow(() -> ApiException.badRequest("INVALID_PRODUCT", "Product not found"));
    }

    // ---------- lectura ----------

    private static final String SELECT = """
            SELECT m.*, mem.display_name AS created_name FROM stock_movement m LEFT JOIN member mem ON mem.id = m.created_by_member_id""";

    public MovementView get(UUID businessId, UUID id) {
        return find(businessId, id).orElseThrow(() -> ApiException.notFound("MOVEMENT_NOT_FOUND", "Movement not found"));
    }

    public Optional<MovementView> find(UUID businessId, UUID id) {
        return jdbc.sql(SELECT + " WHERE m.id = :id AND m.business_id = :b").param("id", id).param("b", businessId).query((rs, n) -> map(rs)).optional();
    }

    public List<MovementView> viewsByIds(UUID businessId, List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.sql(SELECT + " WHERE m.business_id = :b AND m.id IN (:ids)").param("b", businessId).param("ids", ids).query((rs, n) -> map(rs)).list();
    }

    public PageResponse<MovementView> list(MemberContext ctx, UUID productId, int page, int size) {
        ctx.require(Permission.MANAGE_STOCK);
        size = Math.max(1, Math.min(size, 200));
        page = Math.max(0, page);
        long total = jdbc.sql("SELECT count(*) FROM stock_movement WHERE business_id = :b AND product_id = :p").param("b", ctx.businessId()).param("p", productId).query(Long.class).single();
        var items = jdbc.sql(SELECT + " WHERE m.business_id = :b AND m.product_id = :p ORDER BY m.occurred_at DESC, m.created_at DESC LIMIT " + size + " OFFSET " + (long) page * size)
                .param("b", ctx.businessId()).param("p", productId).query((rs, n) -> map(rs)).list();
        return PageResponse.of(items, page, size, total);
    }

    /** Lo que hay que revisar: productos con existencia negativa o por debajo del mínimo. */
    public record LowStock(UUID productId, String name, long stockMilli, Long minStockMilli, boolean negative) {}

    public List<LowStock> review(MemberContext ctx) {
        ctx.require(Permission.MANAGE_STOCK);
        return jdbc.sql("""
                        SELECT id, name, stock_milli, min_stock_milli FROM product
                         WHERE business_id = :b AND active AND track_stock AND (stock_milli < 0 OR (min_stock_milli IS NOT NULL AND stock_milli <= min_stock_milli))
                         ORDER BY stock_milli, lower(name)""")
                .param("b", ctx.businessId()).query((rs, n) -> new LowStock(rs.getObject("id", UUID.class), rs.getString("name"), rs.getLong("stock_milli"),
                        (Long) rs.getObject("min_stock_milli"), rs.getLong("stock_milli") < 0)).list();
    }

    private static MovementView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new MovementView(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class), rs.getString("kind"), rs.getLong("quantity_milli"),
                (Long) rs.getObject("unit_cost_minor"), rs.getString("ref_type"), rs.getObject("ref_id", UUID.class), rs.getString("note"), rs.getString("created_name"),
                rs.getObject("created_by_member_id", UUID.class), rs.getTimestamp("occurred_at").toInstant(), rs.getLong("rev"));
    }
}
