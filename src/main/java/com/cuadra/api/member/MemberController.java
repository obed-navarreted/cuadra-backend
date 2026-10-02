package com.cuadra.api.member;

import com.cuadra.api.security.Actor;
import com.cuadra.api.tenancy.Access;
import com.cuadra.api.tenancy.MemberContext;
import com.cuadra.api.tenancy.Role;
import com.cuadra.api.tenancy.Role.Permission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
@RequestMapping("/api/b/{businessId}")
public class MemberController {
    private final MemberService members;
    private final Access access;

    public MemberController(MemberService members, Access access) {
        this.members = members;
        this.access = access;
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "CreateMemberRequest")
    public record CreateRequest(@NotBlank @Size(max = 80) String displayName, @NotNull Role role,
                                @NotBlank String pin, Boolean mustChangePin) {}

    public record PinRequest(@NotBlank String pin, Boolean mustChangePin) {}

    public record TransferRequest(@NotNull UUID memberId) {}

    /** La pantalla de entrada de un teléfono vinculado lista a los miembros: no exige elegir persona todavía. */
    @GetMapping("/members")
    public List<MemberService.MemberView> list(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId) {
        access.businessAccess(actor, businessId);
        return actor.isDevice() ? members.listForDevice(businessId) : members.list(businessId, false);
    }

    @PostMapping("/members")
    @ResponseStatus(HttpStatus.CREATED)
    public MemberService.MemberView create(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId,
                                           @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberHeader,
                                           @Valid @RequestBody CreateRequest body) {
        MemberContext ctx = access.member(actor, businessId, memberHeader);
        return members.createWithPin(ctx, new MemberService.CreatePinMember(body.displayName(), body.role(), body.pin(), Boolean.TRUE.equals(body.mustChangePin())));
    }

    @PutMapping("/members/{memberId}")
    public MemberService.MemberView update(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID memberId,
                                           @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberHeader,
                                           @RequestBody MemberService.UpdateMember body) {
        return members.update(access.member(actor, businessId, memberHeader), memberId, body);
    }

    @PutMapping("/members/{memberId}/pin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPin(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID memberId,
                         @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberHeader,
                         @Valid @RequestBody PinRequest body) {
        members.resetPin(access.member(actor, businessId, memberHeader), memberId, body.pin(), Boolean.TRUE.equals(body.mustChangePin()));
    }

    @PostMapping("/owner-transfer")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void transfer(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId,
                         @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberHeader,
                         @Valid @RequestBody TransferRequest body) {
        MemberContext ctx = access.member(actor, businessId, memberHeader);
        ctx.require(Permission.TRANSFER_OWNERSHIP);
        members.transferOwnership(ctx, body.memberId());
    }
}
