package com.cuadra.api.business;

import com.cuadra.api.common.ApiException;
import com.cuadra.api.security.Actor;
import com.cuadra.api.tenancy.Access;
import com.cuadra.api.tenancy.MemberContext;
import com.cuadra.api.tenancy.Role.Permission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class BusinessController {
    private final BusinessService businesses;
    private final Access access;

    public BusinessController(BusinessService businesses, Access access) {
        this.businesses = businesses;
        this.access = access;
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "CreateBusinessRequest")
    public record CreateRequest(@NotBlank @Size(max = 120) String name, @Size(max = 40) String type,
                                @Size(min = 2, max = 2) String country, @Size(min = 3, max = 3) String currency,
                                String timezone, @Size(max = 8) String locale) {}

    @PostMapping("/businesses")
    @ResponseStatus(HttpStatus.CREATED)
    public BusinessService.BusinessView create(@AuthenticationPrincipal Actor actor, @Valid @RequestBody CreateRequest body) {
        if (!actor.isUser()) throw ApiException.forbidden("GOOGLE_REQUIRED", "Only a Google account can create a business");
        return businesses.create(actor.userId(),
                new BusinessService.CreateBusiness(body.name(), body.type(), body.country(), body.currency(), body.timezone(), body.locale()));
    }

    @GetMapping("/b/{businessId}")
    public BusinessService.BusinessView get(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId,
                                            @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId) {
        access.businessAccess(actor, businessId);
        return businesses.get(businessId);
    }

    @PutMapping("/b/{businessId}")
    public BusinessService.BusinessView update(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId,
                                               @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                               @RequestBody BusinessService.UpdateBusiness body) {
        MemberContext ctx = access.member(actor, businessId, memberId);
        ctx.require(Permission.EDIT_BUSINESS);
        return businesses.update(businessId, ctx.memberId(), ctx.userId(), body);
    }

    public record AccessCodeView(String accessCode) {}

    public record SetAccessCode(@jakarta.validation.constraints.NotBlank String accessCode) {}

    /** Renueva el código del negocio (solo el dueño). */
    @PostMapping("/b/{businessId}/access-code")
    public AccessCodeView regenerateAccessCode(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId,
                                               @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId) {
        MemberContext ctx = access.member(actor, businessId, memberId);
        ctx.require(Permission.MANAGE_ACCESS_CODE);
        return new AccessCodeView(businesses.regenerateAccessCode(businessId, ctx.memberId(), ctx.userId()));
    }

    /** Cambia el código por uno elegido (5 dígitos, libre). */
    @org.springframework.web.bind.annotation.PutMapping("/b/{businessId}/access-code")
    public AccessCodeView setAccessCode(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId,
                                        @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId, @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody SetAccessCode body) {
        MemberContext ctx = access.member(actor, businessId, memberId);
        ctx.require(Permission.MANAGE_ACCESS_CODE);
        return new AccessCodeView(businesses.setAccessCode(businessId, ctx.memberId(), ctx.userId(), body.accessCode()));
    }

    @DeleteMapping("/b/{businessId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void delete(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId,
                       @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId) {
        MemberContext ctx = access.member(actor, businessId, memberId);
        ctx.require(Permission.DELETE_BUSINESS);
        businesses.requestDeletion(businessId, ctx.memberId(), ctx.userId());
    }
}
