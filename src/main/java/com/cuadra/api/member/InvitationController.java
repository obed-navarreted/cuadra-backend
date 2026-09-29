package com.cuadra.api.member;

import com.cuadra.api.common.ApiException;
import com.cuadra.api.security.Actor;
import com.cuadra.api.tenancy.Access;
import com.cuadra.api.tenancy.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class InvitationController {
    private final InvitationService invitations;
    private final Access access;

    public InvitationController(InvitationService invitations, Access access) {
        this.invitations = invitations;
        this.access = access;
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "CreateInvitationRequest")
    public record CreateRequest(@NotNull Role role, @Min(1) @Max(50) Integer maxUses, @Min(1) @Max(30) Integer expiresInDays, String email) {}

    public record AcceptResponse(UUID businessId) {}

    @PostMapping("/b/{businessId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public InvitationService.InvitationView create(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId,
                                                   @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberHeader,
                                                   @Valid @RequestBody CreateRequest body) {
        return invitations.create(access.member(actor, businessId, memberHeader), body.role(),
                body.maxUses() == null ? 1 : body.maxUses(), body.expiresInDays() == null ? 7 : body.expiresInDays(), body.email());
    }

    @GetMapping("/b/{businessId}/invitations")
    public List<InvitationService.InvitationView> list(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId,
                                                       @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberHeader) {
        return invitations.listActive(access.member(actor, businessId, memberHeader));
    }

    @DeleteMapping("/b/{businessId}/invitations/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID invitationId,
                       @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberHeader) {
        invitations.revoke(access.member(actor, businessId, memberHeader), invitationId);
    }

    @GetMapping("/invitations/{code}")
    public InvitationService.Preview preview(@PathVariable String code) {
        return invitations.preview(code);
    }

    @PostMapping("/invitations/{code}/accept")
    public AcceptResponse accept(@AuthenticationPrincipal Actor actor, @PathVariable String code) {
        if (!actor.isUser()) throw ApiException.forbidden("GOOGLE_REQUIRED", "Accepting an invitation needs a Google session");
        return new AcceptResponse(invitations.accept(actor.userId(), code));
    }
}
