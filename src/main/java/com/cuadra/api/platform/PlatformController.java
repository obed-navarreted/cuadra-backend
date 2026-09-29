package com.cuadra.api.platform;

import com.cuadra.api.common.PageResponse;
import com.cuadra.api.common.ReasonBody;
import com.cuadra.api.security.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** La consola de la plataforma. Solo para admins de plataforma (ver `PlatformAccess`); para cualquiera más, esta ruta no existe (404). */
@RestController
@RequestMapping("/api/platform")
public class PlatformController {
    private final PlatformAccess access;
    private final PlatformService service;
    private final AnnouncementService announcements;

    public PlatformController(PlatformAccess access, PlatformService service, AnnouncementService announcements) {
        this.access = access;
        this.service = service;
        this.announcements = announcements;
    }

    public record PlatformExtendTrial(@Min(1) @Max(365) int days, @NotBlank @Size(min = 5, max = 300) String reason) {}
    public record PlatformPlanRequest(@NotBlank String plan, String status, java.time.Instant periodEnd, @Size(max = 300) String note, @NotBlank @Size(min = 5, max = 300) String reason) {}
    public record PlatformFlagRequest(@NotBlank String key, boolean enabled, @NotBlank @Size(min = 5, max = 300) String reason) {}
    public record PlatformTicketStatus(@NotBlank String status) {}
    public record PlatformConfigRequest(@NotBlank String key, String value, @NotBlank @Size(min = 5, max = 300) String reason) {}
    public record PlatformReachRequest(AnnouncementService.AnnouncementSegment segment, @NotNull String audience) {}

    /** Toda acción que cambia algo o mira un negocio pide un motivo de al menos 5 letras: queda en las auditorías. */
    private static String reason(ReasonBody body) { return reason(body == null ? null : body.reason()); }

    private static String reason(PlatformPlanRequest b) { return b.reason(); }
    private static String reason(PlatformExtendTrial b) { return b.reason(); }
    private static String reason(PlatformFlagRequest b) { return b.reason(); }
    private static String reason(PlatformConfigRequest b) { return b.reason(); }

    private static String reason(String reason) {
        if (reason == null || reason.trim().length() < 5) throw com.cuadra.api.common.ApiException.badRequest("REASON_REQUIRED", "A reason of at least 5 characters is required");
        return reason.trim();
    }

    @GetMapping("/metrics")
    public PlatformService.PlatformMetrics metrics(@AuthenticationPrincipal Actor actor) {
        access.requireAdmin(actor);
        return service.metrics();
    }

    @GetMapping("/businesses")
    public PageResponse<PlatformService.PlatformBusinessRow> businesses(@AuthenticationPrincipal Actor actor, @RequestParam(required = false) String q, @RequestParam(required = false) String plan,
                                                                        @RequestParam(required = false) String country, @RequestParam(required = false) String status,
                                                                        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        access.requireAdmin(actor);
        return service.businesses(q, plan, country, status, page, size);
    }

    @GetMapping("/businesses/{id}")
    public PlatformService.PlatformBusinessDetail business(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
        access.requireAdmin(actor);
        return service.business(id);
    }

    @PutMapping("/businesses/{id}/plan")
    public PlatformService.PlatformBusinessDetail changePlan(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @Valid @RequestBody PlatformPlanRequest body) {
        return service.changePlan(access.requireAdmin(actor), id, new PlatformService.PlatformPlanChange(body.plan(), body.status(), body.periodEnd(), body.note()), reason(body));
    }

    @PostMapping("/businesses/{id}/extend-trial")
    public PlatformService.PlatformBusinessDetail extendTrial(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @Valid @RequestBody PlatformExtendTrial body) {
        return service.extendTrial(access.requireAdmin(actor), id, body.days(), reason(body));
    }

    @PostMapping("/businesses/{id}/suspend")
    public PlatformService.PlatformBusinessDetail suspend(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @Valid @RequestBody ReasonBody body) {
        return service.suspend(access.requireAdmin(actor), id, reason(body));
    }

    @PostMapping("/businesses/{id}/unsuspend")
    public PlatformService.PlatformBusinessDetail unsuspend(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @Valid @RequestBody ReasonBody body) {
        return service.unsuspend(access.requireAdmin(actor), id, reason(body));
    }

    @PostMapping("/businesses/{id}/mark-deletion")
    public PlatformService.PlatformBusinessDetail markDeletion(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @Valid @RequestBody ReasonBody body) {
        return service.markDeletion(access.requireAdmin(actor), id, reason(body));
    }

    @PutMapping("/businesses/{id}/flags")
    public PlatformService.PlatformBusinessDetail flag(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @Valid @RequestBody PlatformFlagRequest body) {
        return service.setFlag(access.requireAdmin(actor), id, body.key(), body.enabled(), reason(body));
    }

    @PostMapping("/businesses/{id}/view-as")
    public PlatformService.PlatformViewAs viewAs(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @Valid @RequestBody ReasonBody body) {
        return service.viewAs(access.requireAdmin(actor), id, reason(body));
    }

    @GetMapping("/users")
    public List<PlatformService.PlatformUserRow> users(@AuthenticationPrincipal Actor actor, @RequestParam(required = false) String q) {
        access.requireAdmin(actor);
        return service.users(q);
    }

    @GetMapping("/devices")
    public List<PlatformService.PlatformDeviceRow> devices(@AuthenticationPrincipal Actor actor, @RequestParam(required = false) Integer staleDays, @RequestParam(required = false) String belowVersion) {
        access.requireAdmin(actor);
        return service.devices(staleDays, belowVersion);
    }

    @GetMapping("/tickets")
    public PageResponse<PlatformService.PlatformTicketRow> tickets(@AuthenticationPrincipal Actor actor, @RequestParam(required = false) String status,
                                                                   @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        access.requireAdmin(actor);
        return service.tickets(status, page, size);
    }

    @PutMapping("/tickets/{id}/status")
    public void ticketStatus(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @Valid @RequestBody PlatformTicketStatus body) {
        service.setTicketStatus(access.requireAdmin(actor), id, body.status());
    }

    @GetMapping("/config")
    public Map<String, String> config(@AuthenticationPrincipal Actor actor) {
        access.requireAdmin(actor);
        return service.config();
    }

    @PutMapping("/config")
    public Map<String, String> setConfig(@AuthenticationPrincipal Actor actor, @Valid @RequestBody PlatformConfigRequest body) {
        return service.setConfig(access.requireAdmin(actor), body.key(), body.value(), reason(body));
    }

    @GetMapping("/audit")
    public PageResponse<PlatformService.PlatformAuditEntry> audit(@AuthenticationPrincipal Actor actor, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        access.requireAdmin(actor);
        return service.auditLog(page, size);
    }

    @GetMapping("/announcements")
    public List<AnnouncementService.AnnouncementView> announcements(@AuthenticationPrincipal Actor actor) {
        access.requireAdmin(actor);
        return announcements.list();
    }

    @PostMapping("/announcements")
    public AnnouncementService.AnnouncementView createAnnouncement(@AuthenticationPrincipal Actor actor, @Valid @RequestBody AnnouncementService.AnnouncementInput body) {
        return announcements.create(access.requireAdmin(actor), body);
    }

    @PostMapping("/announcements/reach")
    public AnnouncementService.AnnouncementReach reach(@AuthenticationPrincipal Actor actor, @Valid @RequestBody PlatformReachRequest body) {
        access.requireAdmin(actor);
        return announcements.reach(body.segment(), body.audience());
    }

    @PostMapping("/announcements/{id}/cancel")
    public AnnouncementService.AnnouncementView cancelAnnouncement(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
        return announcements.cancel(access.requireAdmin(actor), id);
    }

    @PostMapping("/announcements/{id}/end-banner")
    public AnnouncementService.AnnouncementView endBanner(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
        return announcements.endBanner(access.requireAdmin(actor), id);
    }
}
