package com.cuadra.api.credit;

import com.cuadra.api.common.ReasonBody;
import com.cuadra.api.common.PageResponse;
import com.cuadra.api.security.Actor;
import com.cuadra.api.tenancy.Access;
import com.cuadra.api.tenancy.MemberContext;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
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
@RequestMapping("/api/b/{businessId}")
public class CreditController {
    private final CustomerService customers;
    private final CreditService credits;
    private final TemplateService templates;
    private final Access access;

    public CreditController(CustomerService customers, CreditService credits, TemplateService templates, Access access) {
        this.customers = customers;
        this.credits = credits;
        this.templates = templates;
        this.access = access;
    }

    public record LinkBody(@NotNull UUID customerId) {}

    public record TemplateBody(String body) {}

    private MemberContext ctx(Actor actor, UUID businessId, UUID memberId) {
        return access.member(actor, businessId, memberId);
    }

    // ---------- clientes ----------

    @GetMapping("/customers")
    public PageResponse<CustomerService.CustomerView> customers(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId,
                                                                 @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                                                 @RequestParam(required = false) String q, @RequestParam(defaultValue = "false") boolean includeArchived,
                                                                 @RequestParam(defaultValue = "false") boolean withDebtOnly,
                                                                 @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        ctx(actor, businessId, memberId);
        return customers.search(businessId, q, includeArchived, withDebtOnly, page, size);
    }

    @GetMapping("/customers/{customerId}")
    public CustomerService.CustomerView customer(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID customerId,
                                                 @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId) {
        ctx(actor, businessId, memberId);
        return customers.get(businessId, customerId);
    }

    @PutMapping("/customers/{customerId}")
    public ResponseEntity<CustomerService.CustomerView> upsertCustomer(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID customerId,
                                                                        @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                                                        @RequestBody CustomerService.CustomerInput body) {
        CustomerService.Result r = customers.upsert(ctx(actor, businessId, memberId), customerId, body);
        return ResponseEntity.status(r.outcome() == CustomerService.Outcome.CREATED ? HttpStatus.CREATED : HttpStatus.OK).body(r.customer());
    }

    @GetMapping("/customers/{customerId}/statement")
    public CreditService.Statement statement(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID customerId,
                                             @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId) {
        ctx(actor, businessId, memberId);
        return credits.statement(businessId, customerId);
    }

    // ---------- fiados ----------

    @GetMapping("/credits")
    public PageResponse<CreditService.CreditView> list(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId,
                                                       @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                                       @RequestParam(required = false) String status, @RequestParam(required = false) String linked,
                                                       @RequestParam(required = false) UUID customerId, @RequestParam(required = false) Integer minDays,
                                                       @RequestParam(required = false) String q, @RequestParam(required = false) String sort,
                                                       @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        ctx(actor, businessId, memberId);
        return credits.list(businessId, new CreditService.Filter(status, linked, customerId, minDays, q, sort), page, size);
    }

    @GetMapping("/credits/summary")
    public CreditService.Summary summary(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId,
                                         @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId) {
        ctx(actor, businessId, memberId);
        return credits.summary(businessId);
    }

    @GetMapping("/credits/{creditId}")
    public CreditService.CreditView credit(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID creditId,
                                           @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId) {
        ctx(actor, businessId, memberId);
        return credits.get(businessId, creditId);
    }

    @PutMapping("/credits/{creditId}")
    public ResponseEntity<CreditService.CreditView> upsertCredit(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID creditId,
                                                                  @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                                                  @RequestBody CreditService.ManualCreditInput body) {
        CreditService.Result r = credits.upsertManual(ctx(actor, businessId, memberId), creditId, body);
        return ResponseEntity.status(r.outcome() == CreditService.Outcome.CREATED ? HttpStatus.CREATED : HttpStatus.OK).body(r.credit());
    }

    @PostMapping("/credits/{creditId}/link-customer")
    public CreditService.CreditView link(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID creditId,
                                         @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId, @RequestBody LinkBody body) {
        return credits.linkCustomer(ctx(actor, businessId, memberId), creditId, body.customerId());
    }

    @PostMapping("/credits/{creditId}/write-off")
    public CreditService.CreditView writeOff(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID creditId,
                                             @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId, @RequestBody ReasonBody body) {
        return credits.writeOff(ctx(actor, businessId, memberId), creditId, body.reason());
    }

    // ---------- abonos y eventos ----------

    @PutMapping("/credit-payments/{paymentId}")
    public ResponseEntity<CreditService.PayResult> pay(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID paymentId,
                                                       @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId, @RequestBody CreditService.PayInput body) {
        CreditService.PayResult r = credits.pay(ctx(actor, businessId, memberId), paymentId, body);
        return ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK).body(r);
    }

    @PostMapping("/credit-payments/{paymentId}/void")
    public CreditService.PayResult voidPayment(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID paymentId,
                                               @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId, @RequestBody(required = false) ReasonBody body) {
        return credits.voidPayment(ctx(actor, businessId, memberId), paymentId, body == null ? null : body.reason());
    }

    @PostMapping("/credit-events")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void event(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                      @RequestBody CreditService.EventInput body) {
        credits.recordEvent(ctx(actor, businessId, memberId), body);
    }

    // ---------- plantillas ----------

    @GetMapping("/message-templates")
    public List<TemplateService.TemplateView> templates(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId,
                                                        @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId) {
        ctx(actor, businessId, memberId);
        return templates.list(businessId);
    }

    @PutMapping("/message-templates/{kind}/{locale}")
    public TemplateService.TemplateView putTemplate(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable String kind, @PathVariable String locale,
                                                    @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId, @RequestBody TemplateBody body) {
        return templates.upsert(ctx(actor, businessId, memberId), kind, locale, body.body());
    }

    @DeleteMapping("/message-templates/{kind}/{locale}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetTemplate(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable String kind, @PathVariable String locale,
                              @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId) {
        templates.reset(ctx(actor, businessId, memberId), kind, locale);
    }
}
