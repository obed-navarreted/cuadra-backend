package com.cuadra.api.stock;

import com.cuadra.api.common.ReasonBody;
import com.cuadra.api.common.PageResponse;
import com.cuadra.api.security.Actor;
import com.cuadra.api.tenancy.Access;
import com.cuadra.api.tenancy.MemberContext;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/b/{businessId}")
public class StockController {
    private final StockService stock;
    private final SupplierService suppliers;
    private final PurchaseService purchases;
    private final Access access;

    public StockController(StockService stock, SupplierService suppliers, PurchaseService purchases, Access access) {
        this.stock = stock;
        this.suppliers = suppliers;
        this.purchases = purchases;
        this.access = access;
    }

    private MemberContext ctx(Actor actor, UUID businessId, UUID memberId) {
        return access.member(actor, businessId, memberId);
    }

    // ---------- existencias ----------

    @PutMapping("/stock-movements/{movementId}")
    public ResponseEntity<StockService.MovementView> addMovement(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID movementId,
                                                                  @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId, @RequestBody StockService.MovementInput body) {
        StockService.Result r = stock.add(ctx(actor, businessId, memberId), movementId, body);
        return ResponseEntity.status(r.outcome() == StockService.Outcome.CREATED ? HttpStatus.CREATED : HttpStatus.OK).body(r.movement());
    }

    @GetMapping("/products/{productId}/stock-movements")
    public PageResponse<StockService.MovementView> productMovements(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID productId,
                                                                     @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                                                     @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return stock.list(ctx(actor, businessId, memberId), productId, page, size);
    }

    @GetMapping("/stock/review")
    public List<StockService.LowStock> review(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId) {
        return stock.review(ctx(actor, businessId, memberId));
    }

    // ---------- proveedores ----------

    @GetMapping("/suppliers")
    public List<SupplierService.SupplierView> listSuppliers(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                                            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return suppliers.list(ctx(actor, businessId, memberId), includeInactive);
    }

    @PutMapping("/suppliers/{supplierId}")
    public SupplierService.SupplierView upsertSupplier(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID supplierId,
                                                        @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId, @RequestBody SupplierService.SupplierInput body) {
        return suppliers.upsert(ctx(actor, businessId, memberId), supplierId, body);
    }

    // ---------- compras y pagos ----------

    @GetMapping("/purchases")
    public PageResponse<PurchaseService.PurchaseView> listPurchases(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                                                     @RequestParam(required = false) UUID supplierId, @RequestParam(defaultValue = "false") boolean onlyOwed,
                                                                     @RequestParam(defaultValue = "false") boolean includeVoided, @RequestParam(defaultValue = "0") int page,
                                                                     @RequestParam(defaultValue = "50") int size) {
        return purchases.list(ctx(actor, businessId, memberId), supplierId, onlyOwed, includeVoided, page, size);
    }

    @PutMapping("/purchases/{purchaseId}")
    public ResponseEntity<PurchaseService.PurchaseView> registerPurchase(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID purchaseId,
                                                                          @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId, @RequestBody PurchaseService.PurchaseInput body) {
        PurchaseService.Result r = purchases.register(ctx(actor, businessId, memberId), purchaseId, body);
        return ResponseEntity.status(r.outcome() == PurchaseService.Outcome.CREATED ? HttpStatus.CREATED : HttpStatus.OK).body(r.purchase());
    }

    @PostMapping("/purchases/{purchaseId}/void")
    public PurchaseService.PurchaseView voidPurchase(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID purchaseId,
                                                      @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId, @RequestBody(required = false) ReasonBody body) {
        return purchases.voidPurchase(ctx(actor, businessId, memberId), purchaseId, body == null ? null : body.reason());
    }

    @GetMapping("/purchases/{purchaseId}/payments")
    public List<PurchaseService.PaymentView> payments(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID purchaseId,
                                                       @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId) {
        return purchases.paymentsOf(ctx(actor, businessId, memberId), purchaseId);
    }

    @PutMapping("/supplier-payments/{paymentId}")
    public ResponseEntity<PurchaseService.PaymentView> pay(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID paymentId,
                                                            @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId, @RequestBody PurchaseService.PaymentInput body) {
        PurchaseService.PaymentResult r = purchases.pay(ctx(actor, businessId, memberId), paymentId, body);
        return ResponseEntity.status(r.outcome() == PurchaseService.Outcome.CREATED ? HttpStatus.CREATED : HttpStatus.OK).body(r.payment());
    }

    @PostMapping("/supplier-payments/{paymentId}/void")
    public PurchaseService.PaymentView voidPayment(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID paymentId,
                                                    @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId, @RequestBody(required = false) ReasonBody body) {
        return purchases.voidPayment(ctx(actor, businessId, memberId), paymentId, body == null ? null : body.reason());
    }
}
