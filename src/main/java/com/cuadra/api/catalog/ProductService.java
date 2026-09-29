package com.cuadra.api.catalog;

import com.cuadra.api.common.ApiException;
import com.cuadra.api.common.Audit;
import com.cuadra.api.common.PageResponse;
import com.cuadra.api.tenancy.MemberContext;
import com.cuadra.api.tenancy.Role.Permission;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {
    private static final Set<String> UNITS = Set.of("UNIT", "LB", "KG", "L", "M");
    private static final long MAX_MINOR = 1_000_000_000_000L;

    private final JdbcClient jdbc;
    private final Audit audit;
    private final tools.jackson.databind.json.JsonMapper mapper;

    public ProductService(JdbcClient jdbc, Audit audit, tools.jackson.databind.json.JsonMapper mapper) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.mapper = mapper;
    }

    public record ProductInput(String barcode, String shortCode, String name, String variant, UUID categoryId, String unit,
                               String pricing, Long priceMinor, Long costMinor, Boolean isQuick, Integer quickPosition,
                               String color, Boolean trackStock, Long minStockMilli, Boolean active) {}

    public record ProductView(UUID id, String barcode, String shortCode, String name, String variant, UUID categoryId, String unit,
                              String pricing, long priceMinor, Long costMinor, boolean isQuick, Integer quickPosition, String color,
                              boolean trackStock, long stockMilli, Long minStockMilli, boolean active, Instant updatedAt, long rev) {}

    public enum Outcome { CREATED, UPDATED, UNCHANGED }

    public record Result(ProductView product, Outcome outcome) {}

    /**
     * Crea o reemplaza con el id del dispositivo (idempotente). Vender basta para crear desde la caja;
     * editar o dar de baja un producto existente exige gestionar el catálogo.
     */
    @Transactional
    public Result upsert(MemberContext ctx, UUID id, ProductInput in) {
        ProductInput p = normalize(in);
        Optional<ProductView> existing = find(ctx.businessId(), id);
        if (existing.isEmpty() && jdbc.sql("SELECT count(*) FROM product WHERE id = :id").param("id", id).query(Integer.class).single() > 0) {
            throw ApiException.conflict("ID_TAKEN", "Id already in use");
        }
        // Agregar y modificar lo hace quien atiende la caja (con historial); dar de baja o reactivar, solo admin y dueño.
        ctx.require(existing.isPresent() ? Permission.EDIT_PRODUCTS : Permission.SELL);
        if (existing.isPresent() && existing.get().active() != (p.active() == null || p.active())) ctx.require(Permission.MANAGE_CATALOG);
        if (p.categoryId() != null && jdbc.sql("SELECT count(*) FROM category WHERE id = :c AND business_id = :b")
                .param("c", p.categoryId()).param("b", ctx.businessId()).query(Integer.class).single() == 0) {
            throw ApiException.badRequest("INVALID_CATEGORY", "Category not found");
        }
        boolean active = p.active() == null || p.active();
        if (active) checkCodes(ctx.businessId(), id, p);

        try {
            if (existing.isEmpty()) {
                jdbc.sql("""
                                INSERT INTO product (id, business_id, barcode, short_code, name, variant, category_id, unit, pricing, price_minor,
                                                     cost_minor, is_quick, quick_position, color, track_stock, stock_tracked_since, min_stock_milli, active)
                                VALUES (:id, :b, :barcode, :sc, :name, :variant, :cat, :unit, :pricing, :price, :cost, :quick, :qpos, :color, :track, CASE WHEN :track THEN now() END, :min, :active)
                                """)
                        .param("id", id).param("b", ctx.businessId()).param("barcode", p.barcode()).param("sc", p.shortCode())
                        .param("name", p.name()).param("variant", p.variant()).param("cat", p.categoryId(), java.sql.Types.OTHER)
                        .param("unit", p.unit()).param("pricing", p.pricing()).param("price", p.priceMinor())
                        .param("cost", p.costMinor(), java.sql.Types.BIGINT).param("quick", Boolean.TRUE.equals(p.isQuick()))
                        .param("qpos", p.quickPosition(), java.sql.Types.INTEGER).param("color", p.color())
                        .param("track", Boolean.TRUE.equals(p.trackStock())).param("min", p.minStockMilli(), java.sql.Types.BIGINT)
                        .param("active", active).update();
                priceHistory(ctx, id, p.priceMinor(), p.costMinor());
                audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "product.create", "product", id, createdDetail(p));
                return new Result(get(ctx.businessId(), id), Outcome.CREATED);
            }
            ProductView cur = existing.get();
            if (same(cur, p, active)) return new Result(cur, Outcome.UNCHANGED);
            jdbc.sql("""
                            UPDATE product SET barcode = :barcode, short_code = :sc, name = :name, variant = :variant, category_id = :cat,
                                   unit = :unit, pricing = :pricing, price_minor = :price, cost_minor = :cost, is_quick = :quick,
                                   quick_position = :qpos, color = :color, track_stock = :track,
                                   stock_tracked_since = CASE WHEN :track AND NOT track_stock THEN now() WHEN :track THEN stock_tracked_since END,
                                   min_stock_milli = :min, active = :active, updated_at = now(), rev = nextval('change_rev_seq')
                             WHERE id = :id AND business_id = :b
                            """)
                    .param("id", id).param("b", ctx.businessId()).param("barcode", p.barcode()).param("sc", p.shortCode())
                    .param("name", p.name()).param("variant", p.variant()).param("cat", p.categoryId(), java.sql.Types.OTHER)
                    .param("unit", p.unit()).param("pricing", p.pricing()).param("price", p.priceMinor())
                    .param("cost", p.costMinor(), java.sql.Types.BIGINT).param("quick", Boolean.TRUE.equals(p.isQuick()))
                    .param("qpos", p.quickPosition(), java.sql.Types.INTEGER).param("color", p.color())
                    .param("track", Boolean.TRUE.equals(p.trackStock())).param("min", p.minStockMilli(), java.sql.Types.BIGINT)
                    .param("active", active).update();
            if (cur.priceMinor() != p.priceMinor() || !java.util.Objects.equals(cur.costMinor(), p.costMinor())) {
                priceHistory(ctx, id, p.priceMinor(), p.costMinor());
            }
            audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "product.update", "product", id, changesDetail(cur, p, active));
            return new Result(get(ctx.businessId(), id), Outcome.UPDATED);
        } catch (DuplicateKeyException e) {
            if (com.cuadra.api.common.Constraints.isPrimaryKey(e)) throw ApiException.conflict("ID_TAKEN", "Id already in use"); // id de otro negocio (invisible por RLS)
            // Carrera entre dos teléfonos con el mismo código: el índice único es la última barrera.
            throw ApiException.conflict("BARCODE_IN_USE", "Code already in use");
        }
    }

    @Transactional
    public void deactivate(MemberContext ctx, UUID id) {
        ctx.require(Permission.MANAGE_CATALOG);
        int n = jdbc.sql("UPDATE product SET active = false, updated_at = now(), rev = nextval('change_rev_seq') WHERE id = :id AND business_id = :b AND active")
                .param("id", id).param("b", ctx.businessId()).update();
        if (n == 0 && find(ctx.businessId(), id).isEmpty()) throw ApiException.notFound("PRODUCT_NOT_FOUND", "Product not found");
        audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "product.deactivate", "product", id, "{\"changes\":{\"active\":{\"from\":true,\"to\":false}}}");
    }

    public ProductView get(UUID businessId, UUID id) {
        return find(businessId, id).orElseThrow(() -> ApiException.notFound("PRODUCT_NOT_FOUND", "Product not found"));
    }

    public Optional<ProductView> find(UUID businessId, UUID id) {
        return jdbc.sql("SELECT * FROM product WHERE id = :id AND business_id = :b").param("id", id).param("b", businessId)
                .query((rs, n) -> map(rs)).optional();
    }

    public PageResponse<ProductView> search(UUID businessId, String q, Boolean quick, boolean includeInactive, Long updatedSince, int page, int size) {
        size = Math.max(1, Math.min(size, 200));
        page = Math.max(0, page);
        StringBuilder where = new StringBuilder("business_id = :b");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("b", businessId);
        if (!includeInactive) where.append(" AND active");
        if (Boolean.TRUE.equals(quick)) where.append(" AND is_quick");
        if (updatedSince != null) {
            where.append(" AND rev > :since");
            params.put("since", updatedSince);
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND (lower(name) LIKE :like OR lower(coalesce(variant, '')) LIKE :like OR barcode = :exact OR short_code = :exact)");
            params.put("like", "%" + q.trim().toLowerCase().replace("%", "\\%").replace("_", "\\_") + "%");
            params.put("exact", q.trim());
        }
        long total = bind(jdbc.sql("SELECT count(*) FROM product WHERE " + where), params).query(Long.class).single();
        List<ProductView> items = bind(jdbc.sql("SELECT * FROM product WHERE " + where
                        + " ORDER BY is_quick DESC, quick_position NULLS LAST, lower(name), lower(coalesce(variant, '')) LIMIT " + size + " OFFSET " + (long) page * size), params)
                .query((rs, n) -> map(rs)).list();
        return PageResponse.of(items, page, size, total);
    }

    /** Un EAN-13 con cero inicial y un UPC-A de 12 dígitos son el mismo código: se prueban ambas formas. */
    public Optional<ProductView> byBarcode(UUID businessId, String code) {
        for (String candidate : barcodeForms(code)) {
            Optional<ProductView> hit = jdbc.sql("SELECT * FROM product WHERE business_id = :b AND barcode = :c AND active")
                    .param("b", businessId).param("c", candidate).query((rs, n) -> map(rs)).optional();
            if (hit.isPresent()) return hit;
        }
        return Optional.empty();
    }

    public static List<String> barcodeForms(String raw) {
        String code = raw == null ? "" : raw.trim();
        List<String> forms = new ArrayList<>();
        forms.add(code);
        if (code.matches("\\d{12}")) forms.add("0" + code);
        if (code.matches("0\\d{12}")) forms.add(code.substring(1));
        return forms;
    }

    // ---------- internos ----------

    private void checkCodes(UUID businessId, UUID id, ProductInput p) {
        if (p.barcode() != null) {
            for (String form : barcodeForms(p.barcode())) {
                Optional<UUID> other = jdbc.sql("SELECT id FROM product WHERE business_id = :b AND barcode = :c AND active AND id <> :id")
                        .param("b", businessId).param("c", form).param("id", id).query(UUID.class).optional();
                if (other.isPresent()) throw new CodeInUse("BARCODE_IN_USE", other.get());
            }
        }
        if (p.shortCode() != null) {
            Optional<UUID> other = jdbc.sql("SELECT id FROM product WHERE business_id = :b AND short_code = :c AND active AND id <> :id")
                    .param("b", businessId).param("c", p.shortCode()).param("id", id).query(UUID.class).optional();
            if (other.isPresent()) throw new CodeInUse("SHORT_CODE_IN_USE", other.get());
        }
    }

    /** 409 que además dice qué producto ya usa el código, para que la app lo agregue a la venta en vez de duplicarlo. */
    public static class CodeInUse extends ApiException {
        private final UUID existingId;

        CodeInUse(String code, UUID existingId) {
            super(org.springframework.http.HttpStatus.CONFLICT, code, "Code already in use by product " + existingId);
            this.existingId = existingId;
        }

        public UUID existingId() { return existingId; }
    }

    private void priceHistory(MemberContext ctx, UUID id, long price, Long cost) {
        jdbc.sql("INSERT INTO product_price_history (business_id, product_id, price_minor, cost_minor, changed_by_member_id) VALUES (:b, :p, :price, :cost, :m)")
                .param("b", ctx.businessId()).param("p", id).param("price", price).param("cost", cost, java.sql.Types.BIGINT)
                .param("m", ctx.memberId()).update();
    }

    // ---------- historial (quién cambió qué) ----------

    public record ProductFieldChange(Object from, Object to) {}

    public record ProductHistoryEntry(long id, String action, UUID actorMemberId, String actorName, String actorRole, Instant at,
                                      Map<String, ProductFieldChange> changes, String name) {}

    private String createdDetail(ProductInput p) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", p.name());
        d.put("pricing", p.pricing());
        d.put("priceMinor", p.priceMinor());
        if (p.costMinor() != null) d.put("costMinor", p.costMinor());
        return mapper.writeValueAsString(d);
    }

    /** Solo los campos que cambiaron, con su valor antes y después: es lo que muestra el historial. */
    private String changesDetail(ProductView c, ProductInput p, boolean active) {
        Map<String, Map<String, Object>> ch = new LinkedHashMap<>();
        diff(ch, "name", c.name(), p.name());
        diff(ch, "variant", c.variant(), p.variant());
        diff(ch, "barcode", c.barcode(), p.barcode());
        diff(ch, "shortCode", c.shortCode(), p.shortCode());
        diff(ch, "categoryId", c.categoryId() == null ? null : c.categoryId().toString(), p.categoryId() == null ? null : p.categoryId().toString());
        diff(ch, "unit", c.unit(), p.unit());
        diff(ch, "pricing", c.pricing(), p.pricing());
        diff(ch, "priceMinor", c.priceMinor(), p.priceMinor());
        diff(ch, "costMinor", c.costMinor(), p.costMinor());
        diff(ch, "isQuick", c.isQuick(), Boolean.TRUE.equals(p.isQuick()));
        diff(ch, "quickPosition", c.quickPosition(), p.quickPosition());
        diff(ch, "color", c.color(), p.color());
        diff(ch, "trackStock", c.trackStock(), Boolean.TRUE.equals(p.trackStock()));
        diff(ch, "minStockMilli", c.minStockMilli(), p.minStockMilli());
        diff(ch, "active", c.active(), active);
        return mapper.writeValueAsString(Map.of("name", p.name(), "changes", ch));
    }

    private static void diff(Map<String, Map<String, Object>> out, String field, Object from, Object to) {
        if (java.util.Objects.equals(from, to)) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", from);
        m.put("to", to);
        out.put(field, m);
    }

    /** Historial de un producto, del más nuevo al más viejo (últimos 200). Lo puede ver cualquiera que atiende la caja. */
    public List<ProductHistoryEntry> history(MemberContext ctx, UUID productId) {
        ctx.require(Permission.SELL);
        get(ctx.businessId(), productId);
        return jdbc.sql("""
                        SELECT a.id, a.action, a.actor_member_id, a.detail, a.at, m.display_name, m.role
                          FROM audit_log a LEFT JOIN member m ON m.id = a.actor_member_id
                         WHERE a.business_id = :b AND a.entity = 'product' AND a.entity_id = :p ORDER BY a.id DESC LIMIT 200
                        """)
                .param("b", ctx.businessId()).param("p", productId.toString())
                .query((rs, n) -> {
                    Map<String, ProductFieldChange> changes = new LinkedHashMap<>();
                    String name = null;
                    String detail = rs.getString("detail");
                    if (detail != null && detail.startsWith("{")) {
                        try {
                            tools.jackson.databind.JsonNode root = mapper.readTree(detail);
                            if (root.hasNonNull("name")) name = root.get("name").asString();
                            if (root.has("changes")) {
                                root.get("changes").properties().forEach(e -> changes.put(e.getKey(), new ProductFieldChange(plain(e.getValue().get("from")), plain(e.getValue().get("to")))));
                            } else {
                                // Alta: lo que se guardó al crear.
                                root.properties().forEach(e -> { if (!e.getKey().equals("name")) changes.put(e.getKey(), new ProductFieldChange(null, plain(e.getValue()))); });
                            }
                        } catch (RuntimeException ignored) {
                            // detalle viejo (solo el nombre) o ilegible: se muestra la acción sin campos
                        }
                    } else {
                        name = detail;
                    }
                    return new ProductHistoryEntry(rs.getLong("id"), rs.getString("action"), rs.getObject("actor_member_id", UUID.class), rs.getString("display_name"),
                            rs.getString("role"), rs.getTimestamp("at").toInstant(), changes, name);
                }).list();
    }

    private static Object plain(tools.jackson.databind.JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (n.isBoolean()) return n.asBoolean();
        if (n.isIntegralNumber()) return n.asLong();
        if (n.isNumber()) return n.asDouble();
        return n.asString();
    }

    private static boolean same(ProductView c, ProductInput p, boolean active) {
        return java.util.Objects.equals(c.barcode(), p.barcode()) && java.util.Objects.equals(c.shortCode(), p.shortCode())
                && c.name().equals(p.name()) && java.util.Objects.equals(c.variant(), p.variant())
                && java.util.Objects.equals(c.categoryId(), p.categoryId()) && c.unit().equals(p.unit()) && c.pricing().equals(p.pricing())
                && c.priceMinor() == p.priceMinor() && java.util.Objects.equals(c.costMinor(), p.costMinor())
                && c.isQuick() == Boolean.TRUE.equals(p.isQuick()) && java.util.Objects.equals(c.quickPosition(), p.quickPosition())
                && java.util.Objects.equals(c.color(), p.color()) && c.trackStock() == Boolean.TRUE.equals(p.trackStock())
                && java.util.Objects.equals(c.minStockMilli(), p.minStockMilli()) && c.active() == active;
    }

    private static ProductInput normalize(ProductInput in) {
        String name = blankToNull(in.name());
        if (name == null || name.length() > 200) throw ApiException.badRequest("INVALID_NAME", "Name is required (max 200)");
        String unit = in.unit() == null ? "UNIT" : in.unit();
        if (!UNITS.contains(unit)) throw ApiException.badRequest("INVALID_UNIT", "Invalid unit");
        String pricing = in.pricing() == null ? "FIXED" : in.pricing();
        if (!pricing.equals("FIXED") && !pricing.equals("BY_WEIGHT") && !pricing.equals("OPEN")) throw ApiException.badRequest("INVALID_PRICING", "Invalid pricing");
        // Con precio abierto ("OPEN") el precio es opcional: se pregunta al vender y, si se da, es solo una sugerencia.
        long price = in.priceMinor() == null && pricing.equals("OPEN") ? 0L : in.priceMinor() == null ? -1L : in.priceMinor();
        if (price < 0 || price > MAX_MINOR) throw ApiException.badRequest("INVALID_PRICE", "Invalid price");
        if (in.costMinor() != null && (in.costMinor() < 0 || in.costMinor() > MAX_MINOR)) throw ApiException.badRequest("INVALID_COST", "Invalid cost");
        String barcode = blankToNull(in.barcode());
        if (barcode != null && barcode.length() > 64) throw ApiException.badRequest("INVALID_BARCODE", "Code too long");
        String shortCode = blankToNull(in.shortCode());
        if (shortCode != null && shortCode.length() > 16) throw ApiException.badRequest("INVALID_SHORT_CODE", "Short code too long");
        return new ProductInput(barcode, shortCode, name, blankToNull(in.variant()), in.categoryId(), unit, pricing, price,
                in.costMinor(), in.isQuick(), in.quickPosition(), blankToNull(in.color()), in.trackStock(), in.minStockMilli(), in.active());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Map<String, Object> params) {
        for (var e : params.entrySet()) spec = spec.param(e.getKey(), e.getValue());
        return spec;
    }

    private static ProductView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp updated = rs.getTimestamp("updated_at");
        return new ProductView(rs.getObject("id", UUID.class), rs.getString("barcode"), rs.getString("short_code"), rs.getString("name"),
                rs.getString("variant"), rs.getObject("category_id", UUID.class), rs.getString("unit"), rs.getString("pricing"),
                rs.getLong("price_minor"), (Long) rs.getObject("cost_minor"), rs.getBoolean("is_quick"),
                (Integer) rs.getObject("quick_position"), rs.getString("color"), rs.getBoolean("track_stock"), rs.getLong("stock_milli"),
                (Long) rs.getObject("min_stock_milli"), rs.getBoolean("active"), updated.toInstant(), rs.getLong("rev"));
    }
}
