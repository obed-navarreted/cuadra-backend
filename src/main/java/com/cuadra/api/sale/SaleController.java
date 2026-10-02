package com.cuadra.api.sale;

import com.cuadra.api.common.PageResponse;
import com.cuadra.api.security.Actor;
import com.cuadra.api.tenancy.Access;
import com.cuadra.api.tenancy.MemberContext;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/b/{businessId}/sales")
public class SaleController {
    private final SaleService sales;
    private final ReturnService returns;
    private final Access access;

    public SaleController(SaleService sales, ReturnService returns, Access access) {
        this.sales = sales;
        this.returns = returns;
        this.access = access;
    }

    /** Devolver productos de una venta cobrada. Idempotente por `returnId` (lo genera quien la hace): repetirla devuelve la misma. */
    @PutMapping("/{saleId}/returns/{returnId}")
    public ResponseEntity<ReturnService.ReturnView> createReturn(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID saleId, @PathVariable UUID returnId,
                                                                 @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                                                 @RequestBody ReturnService.ReturnInput body) {
        MemberContext ctx = access.member(actor, businessId, memberId);
        var in = new ReturnService.ReturnInput(saleId, body.items(), body.reason(), body.refundMethod(), body.occurredAt());
        ReturnService.Result r = returns.create(ctx, returnId, in);
        return ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK).body(r.ret());
    }

    public record CancelBody(String reason) {}

    @PutMapping("/{saleId}")
    public ResponseEntity<SaleService.SaleView> upsert(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID saleId,
                                                       @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                                       @RequestBody SaleService.SaleInput body) {
        MemberContext ctx = access.member(actor, businessId, memberId);
        SaleService.Result r = sales.upsert(ctx, saleId, body);
        return ResponseEntity.status(r.outcome() == SaleService.Outcome.CREATED ? HttpStatus.CREATED : HttpStatus.OK).body(r.sale());
    }

    @GetMapping("/{saleId}")
    public SaleService.SaleView get(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID saleId,
                                    @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId) {
        return sales.get(access.member(actor, businessId, memberId), saleId);
    }

    @GetMapping
    public PageResponse<SaleService.SaleView> list(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId,
                                                   @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                   @RequestParam(required = false) Integer hourFrom, @RequestParam(required = false) Integer hourTo,
                                                   @RequestParam(required = false) UUID byMember, @RequestParam(required = false) String method,
                                                   @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        MemberContext ctx = access.member(actor, businessId, memberId);
        return sales.list(ctx, new SaleService.Filter(status, from, to, hourFrom, hourTo, byMember, method), page, size);
    }

    /** «Por cobrar en caja» (ADR 0015): cuentas enviadas a caja que esperan su cobro. Cualquier persona del negocio las ve. */
    @GetMapping("/register-queue")
    public java.util.List<SaleService.SaleView> registerQueue(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId,
                                                             @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId) {
        return sales.registerQueue(access.member(actor, businessId, memberId));
    }

    @GetMapping("/summary")
    public SaleService.Summary summary(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId,
                                       @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return sales.summary(access.member(actor, businessId, memberId), date);
    }

    @PostMapping("/{saleId}/cancel")
    public SaleService.SaleView cancel(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID saleId,
                                       @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                       @RequestBody(required = false) CancelBody body) {
        return sales.cancel(access.member(actor, businessId, memberId), saleId, body == null ? null : body.reason());
    }

    @PostMapping("/{saleId}/lock")
    public SaleService.SaleView lock(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID saleId,
                                     @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId) {
        return sales.lock(access.member(actor, businessId, memberId), saleId);
    }

    @DeleteMapping("/{saleId}/lock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlock(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID saleId,
                       @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId) {
        sales.unlock(access.member(actor, businessId, memberId), saleId);
    }
}
