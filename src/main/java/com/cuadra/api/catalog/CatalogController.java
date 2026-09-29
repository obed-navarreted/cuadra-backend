package com.cuadra.api.catalog;

import com.cuadra.api.common.ApiException;
import com.cuadra.api.common.PageResponse;
import com.cuadra.api.security.Actor;
import com.cuadra.api.tenancy.Access;
import com.cuadra.api.tenancy.MemberContext;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/b/{businessId}")
public class CatalogController {
    private final ProductService products;
    private final CategoryService categories;
    private final ProductImportService imports;
    private final Access access;

    public CatalogController(ProductService products, CategoryService categories, ProductImportService imports, Access access) {
        this.products = products;
        this.categories = categories;
        this.imports = imports;
        this.access = access;
    }

    public record ImportBody(java.util.List<ProductImportService.ImportRow> rows) {}

    /** Importar desde CSV: `dryRun=true` (por defecto) solo muestra qué pasaría; `dryRun=false` lo aplica. */
    @org.springframework.web.bind.annotation.PostMapping("/products/import")
    public ProductImportService.ImportResult importProducts(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                                      @RequestParam(defaultValue = "true") boolean dryRun, @org.springframework.web.bind.annotation.RequestBody ImportBody body) {
        return imports.run(access.member(actor, businessId, memberId), body.rows(), dryRun);
    }

    @GetMapping("/products")
    public PageResponse<ProductService.ProductView> search(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId,
                                                           @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                                           @RequestParam(required = false) String q, @RequestParam(required = false) Boolean quick,
                                                           @RequestParam(defaultValue = "false") boolean includeInactive,
                                                           @RequestParam(required = false) Long updatedSince,
                                                           @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        access.member(actor, businessId, memberId);
        return products.search(businessId, q, quick, includeInactive, updatedSince, page, size);
    }

    @GetMapping("/products/{productId}")
    public ProductService.ProductView get(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID productId,
                                          @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId) {
        access.member(actor, businessId, memberId);
        return products.get(businessId, productId);
    }

    @GetMapping("/products/barcode/{code}")
    public ProductService.ProductView byBarcode(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable String code,
                                                @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId) {
        access.member(actor, businessId, memberId);
        return products.byBarcode(businessId, code).orElseThrow(() -> ApiException.notFound("PRODUCT_NOT_FOUND", "Product not found"));
    }

    @PutMapping("/products/{productId}")
    public ResponseEntity<ProductService.ProductView> upsert(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId,
                                                             @PathVariable UUID productId,
                                                             @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                                             @RequestBody ProductService.ProductInput body) {
        MemberContext ctx = access.member(actor, businessId, memberId);
        ProductService.Result r = products.upsert(ctx, productId, body);
        return ResponseEntity.status(r.outcome() == ProductService.Outcome.CREATED ? HttpStatus.CREATED : HttpStatus.OK).body(r.product());
    }

    /** Quién cambió qué en un producto (precio, nombre, baja…), del más nuevo al más viejo. */
    @GetMapping("/products/{productId}/history")
    public List<ProductService.ProductHistoryEntry> history(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID productId,
                                                            @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId) {
        return products.history(access.member(actor, businessId, memberId), productId);
    }

    @DeleteMapping("/products/{productId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID productId,
                           @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId) {
        products.deactivate(access.member(actor, businessId, memberId), productId);
    }

    @GetMapping("/categories")
    public List<CategoryService.CategoryView> categories(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId,
                                                         @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId) {
        access.member(actor, businessId, memberId);
        return categories.list(businessId);
    }

    @PutMapping("/categories/{categoryId}")
    public CategoryService.CategoryView upsertCategory(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID categoryId,
                                                       @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                                       @RequestBody CategoryService.CategoryInput body) {
        return categories.upsert(access.member(actor, businessId, memberId), categoryId, body);
    }
}
