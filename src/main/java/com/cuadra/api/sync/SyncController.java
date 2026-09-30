package com.cuadra.api.sync;

import com.cuadra.api.security.Actor;
import com.cuadra.api.tenancy.Access;
import com.cuadra.api.tenancy.MemberContext;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/b/{businessId}/sync")
public class SyncController {
    private final SyncService sync;
    private final Access access;

    public SyncController(SyncService sync, Access access) {
        this.sync = sync;
        this.access = access;
    }

    public record PushRequest(List<SyncService.OpInput> ops, Integer pendingOps) {}

    public record PushResponse(List<SyncService.OpResult> results) {}

    @PostMapping("/push")
    public PushResponse push(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId,
                             @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId, @RequestBody PushRequest body) {
        return new PushResponse(sync.push(access.pusher(actor, businessId, memberId), body.ops(), body.pendingOps()));
    }

    @GetMapping("/pull")
    public SyncService.PullResult pull(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId,
                                       @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                       @RequestParam(defaultValue = "0") long since, @RequestParam(defaultValue = "200") int limit,
                                       @RequestParam(required = false) Integer pendingOps) {
        MemberContext ctx = access.member(actor, businessId, memberId);
        return sync.pull(ctx, actor.isDevice() ? Access.deviceTrust(actor) : null, since, limit, pendingOps == null ? null : Math.max(0, pendingOps));
    }
}
