package com.cuadra.api.catalog;

import com.cuadra.api.common.ApiException;
import com.cuadra.api.common.Audit;
import com.cuadra.api.stock.StockService;
import com.cuadra.api.tenancy.MemberContext;
import com.cuadra.api.tenancy.Role.Permission;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Importación de productos y existencias desde CSV. El navegador lee el archivo y manda las filas como texto; el servidor las valida con las MISMAS
 * reglas que un producto normal (códigos únicos, unidades, montos) y decide qué crea y qué actualiza.
 *
 * Con `dryRun` no se guarda nada (cada fila se procesa dentro de una transacción que siempre se revierte): la persona ve el resultado exacto antes de confirmar.
 * Una fila con error se salta y se informa; las demás se aplican. Reimportar el mismo archivo no duplica nada: lo que ya existe se actualiza.
 */
@Service
public class ProductImportService {
    public static final int MAX_ROWS = 2000;
    private static final Set<String> UNITS = Set.of("UNIT", "LB", "KG", "L", "M");
    private static final Set<String> YES = Set.of("si", "sí", "yes", "true", "1", "x", "y", "s");

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ProductService products;
    private final CategoryService categories;
    private final StockService stock;
    private final Audit audit;
    private final Clock clock;

    public ProductImportService(JdbcClient jdbc, PlatformTransactionManager tm, ProductService products, CategoryService categories, StockService stock, Audit audit, Clock clock) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tm);
        this.products = products;
        this.categories = categories;
        this.stock = stock;
        this.audit = audit;
        this.clock = clock;
    }

    /** Todo llega como texto: el servidor interpreta los montos con los decimales de la moneda del negocio. `line` es la línea del archivo (para señalar errores). */
    public record ImportRow(Integer line, String name, String variant, String barcode, String shortCode, String price, String cost, String unit, String category, String trackStock, String stock, String minStock) {}

    public record ImportRowResult(Integer line, String status, UUID productId, String name, String code) {}

    public record ImportSummary(int total, int created, int updated, int failed) {}

    public record ImportResult(boolean dryRun, ImportSummary summary, List<ImportRowResult> rows) {}

    public ImportResult run(MemberContext ctx, List<ImportRow> rows, boolean dryRun) {
        ctx.require(Permission.MANAGE_CATALOG);
        ctx.require(Permission.MANAGE_STOCK);
        if (rows == null || rows.isEmpty()) throw ApiException.badRequest("EMPTY_FILE", "The file has no rows");
        if (rows.size() > MAX_ROWS) throw ApiException.badRequest("TOO_MANY_ROWS", "At most " + MAX_ROWS + " rows per import");
        int decimals = decimals(ctx.businessId());
        List<ImportRowResult> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int created = 0, updated = 0, failed = 0;
        for (ImportRow row : rows) {
            ImportRowResult r;
            try {
                r = one(ctx, row, decimals, dryRun, seen);
            } catch (ApiException e) {
                r = new ImportRowResult(row.line(), "ERROR", null, row.name(), e.code());
            } catch (RuntimeException e) {
                r = new ImportRowResult(row.line(), "ERROR", null, row.name(), "INTERNAL_ERROR");
            }
            switch (r.status()) {
                case "CREATE" -> created++;
                case "UPDATE" -> updated++;
                default -> failed++;
            }
            out.add(r);
        }
        if (!dryRun) audit.log(ctx.businessId(), ctx.memberId(), ctx.userId(), ctx.deviceId(), "product.import", "product", null, "created=" + created + " updated=" + updated + " failed=" + failed);
        return new ImportResult(dryRun, new ImportSummary(rows.size(), created, updated, failed), out);
    }

    private ImportRowResult one(MemberContext ctx, ImportRow row, int decimals, boolean dryRun, Set<String> seen) {
        String name = blank(row.name());
        if (name == null || name.length() > 200) throw ApiException.badRequest("INVALID_NAME", "Name is required");
        String variant = blank(row.variant());
        String barcode = blank(row.barcode());
        // Dos filas del mismo archivo con el mismo código o el mismo nombre serían un error de captura: la segunda no se aplica.
        String key = barcode != null ? "b:" + barcode : "n:" + name.toLowerCase(Locale.ROOT) + "|" + (variant == null ? "" : variant.toLowerCase(Locale.ROOT));
        if (!seen.add(key)) throw ApiException.conflict("DUPLICATE_IN_FILE", "Repeated in the file");

        Long price = money(row.price(), decimals, "INVALID_PRICE");
        Long cost = money(row.cost(), decimals, "INVALID_COST");
        String unit = blank(row.unit()) == null ? null : row.unit().trim().toUpperCase(Locale.ROOT);
        if (unit != null && !UNITS.contains(unit)) throw ApiException.badRequest("INVALID_UNIT", "Invalid unit");
        Long stockMilli = quantity(row.stock(), "INVALID_STOCK");
        Long minMilli = quantity(row.minStock(), "INVALID_MIN_STOCK");
        Boolean track = blank(row.trackStock()) == null ? null : YES.contains(row.trackStock().trim().toLowerCase(Locale.ROOT));

        Optional<ProductService.ProductView> existing = find(ctx.businessId(), barcode, name, variant);
        if (existing.isEmpty() && price == null) throw ApiException.badRequest("INVALID_PRICE", "Price is required for a new product");

        ImportRowResult[] result = new ImportRowResult[1];
        tx.executeWithoutResult(status -> {
            UUID categoryId = category(ctx, row.category());
            UUID id = existing.map(ProductService.ProductView::id).orElseGet(UUID::randomUUID);
            var cur = existing.orElse(null);
            boolean wantsStock = stockMilli != null;
            boolean nowTracked = track != null ? track : (wantsStock || (cur != null && cur.trackStock()));
            ProductService.ProductInput input = new ProductService.ProductInput(
                    barcode != null ? barcode : (cur == null ? null : cur.barcode()), blank(row.shortCode()) != null ? row.shortCode().trim() : (cur == null ? null : cur.shortCode()), name,
                    variant != null ? variant : (cur == null ? null : cur.variant()), categoryId != null ? categoryId : (cur == null ? null : cur.categoryId()), unit != null ? unit : (cur == null ? "UNIT" : cur.unit()),
                    cur == null ? "FIXED" : cur.pricing(), price != null ? price : cur.priceMinor(), cost != null ? cost : (cur == null ? null : cur.costMinor()), cur != null && cur.isQuick(),
                    cur == null ? null : cur.quickPosition(), cur == null ? null : cur.color(), nowTracked, minMilli != null ? minMilli : (cur == null ? null : cur.minStockMilli()), cur == null || cur.active());
            products.upsert(ctx, id, input);
            // Una cantidad en el archivo es un CONTEO: el primero (al empezar a llevar control) es inicial, los siguientes ajustan al valor contado.
            if (wantsStock && nowTracked) {
                stock.add(ctx, UUID.randomUUID(), new StockService.MovementInput(id, cur != null && cur.trackStock() ? "ADJUSTMENT" : "INITIAL", null, stockMilli, null, "CSV", clock.instant()));
            }
            result[0] = new ImportRowResult(row.line(), cur == null ? "CREATE" : "UPDATE", id, name, null);
            // La vista previa deshace todo: lo que se muestra es lo que pasaría, sin haber pasado.
            if (dryRun) status.setRollbackOnly();
        });
        return result[0];
    }

    private Optional<ProductService.ProductView> find(UUID business, String barcode, String name, String variant) {
        if (barcode != null) {
            Optional<ProductService.ProductView> byCode = products.byBarcode(business, barcode);
            if (byCode.isPresent()) return byCode;
        }
        return jdbc.sql("SELECT id FROM product WHERE business_id = :b AND active AND lower(name) = lower(:n) AND lower(coalesce(variant, '')) = lower(:v) LIMIT 1")
                .param("b", business).param("n", name).param("v", variant == null ? "" : variant).query(UUID.class).optional().flatMap(id -> products.find(business, id));
    }

    /** Busca la categoría por nombre (sin importar mayúsculas) y la crea si no existe. */
    private UUID category(MemberContext ctx, String raw) {
        String name = blank(raw);
        if (name == null) return null;
        Optional<UUID> found = jdbc.sql("SELECT id FROM category WHERE business_id = :b AND lower(name) = lower(:n) LIMIT 1").param("b", ctx.businessId()).param("n", name).query(UUID.class).optional();
        if (found.isPresent()) return found.get();
        UUID id = UUID.randomUUID();
        categories.upsert(ctx, id, new CategoryService.CategoryInput(name, true));
        return id;
    }

    private int decimals(UUID business) {
        String currency = jdbc.sql("SELECT currency FROM business WHERE id = :b").param("b", business).query(String.class).single();
        return switch (currency) { case "CRC", "COP", "CLP", "PYG" -> 0; default -> 2; };
    }

    /**
     * Número escrito en una hoja de cálculo → forma canónica ("1234.56"). El separador decimal es el ÚLTIMO de los dos que aparezca: "1,000.00" y
     * "1.000,00" son mil. Con un solo tipo de separador: si se repite ("1.000.000") es de miles; si aparece una vez seguido de exactamente 3 cifras y el
     * campo admite menos de 3 decimales (dinero: "1,000"), también es de miles; si no, es el decimal ("12,50", "1,5"). Los grupos de miles deben ser de
     * 3 cifras; si no, se devuelve tal cual y la validación lo rechaza (mejor un error que un precio 1000 veces más chico).
     */
    static String canonicalNumber(String raw, int maxDecimals) {
        String s = raw.replace(" ", "").replace("\u00a0", "");
        int comma = s.lastIndexOf(',');
        int dot = s.lastIndexOf('.');
        if (comma < 0 && dot < 0) return s;
        Character dec;
        char group;
        if (comma >= 0 && dot >= 0) {
            dec = comma > dot ? ',' : '.';
            group = dec == ',' ? '.' : ',';
        } else {
            char sep = comma >= 0 ? ',' : '.';
            long count = s.chars().filter(ch -> ch == sep).count();
            int after = s.length() - s.lastIndexOf(sep) - 1;
            boolean thousands = count > 1 || (after == 3 && maxDecimals < 3);
            dec = thousands ? null : sep;
            group = sep;
        }
        String intPart = s;
        String frac = null;
        if (dec != null) {
            int at = s.lastIndexOf(dec);
            intPart = s.substring(0, at);
            frac = s.substring(at + 1);
        }
        if (intPart.indexOf(group) >= 0) {
            if (!intPart.matches("\\d{1,3}(" + java.util.regex.Pattern.quote(String.valueOf(group)) + "\\d{3})+")) return raw;
            intPart = intPart.replace(String.valueOf(group), "");
        }
        return frac == null ? intPart : intPart + "." + frac;
    }

    /** "12.50", "12,50", "1,000.00" o "1.000,00" → unidad menor. Más decimales que la moneda o basura → error. Vacío → null. */
    static Long money(String raw, int decimals, String code) {
        String s = blank(raw);
        if (s == null) return null;
        s = canonicalNumber(s, decimals);
        if (!s.matches("\\d{1,12}(\\.\\d+)?")) throw ApiException.badRequest(code, "Invalid amount");
        BigDecimal v = new BigDecimal(s);
        if (v.stripTrailingZeros().scale() > decimals) throw ApiException.badRequest(code, "Too many decimals");
        return v.movePointRight(decimals).longValueExact();
    }

    /** Cantidad con hasta 3 decimales → milésimas. */
    static Long quantity(String raw, String code) {
        String s = blank(raw);
        if (s == null) return null;
        s = canonicalNumber(s, 3);
        if (!s.matches("\\d{1,9}(\\.\\d+)?")) throw ApiException.badRequest(code, "Invalid quantity");
        BigDecimal v = new BigDecimal(s);
        if (v.stripTrailingZeros().scale() > 3) throw ApiException.badRequest(code, "Too many decimals");
        return v.movePointRight(3).longValueExact();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
