package com.cuadra.api.notification;

import com.cuadra.api.common.PageResponse;
import com.cuadra.api.security.Actor;
import com.cuadra.api.tenancy.Access;
import com.cuadra.api.tenancy.MemberContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/b/{businessId}")
public class NotificationController {
    private final NotificationService notifications;
    private final ScheduleService schedules;
    private final Access access;

    public NotificationController(NotificationService notifications, ScheduleService schedules, Access access) {
        this.notifications = notifications;
        this.schedules = schedules;
        this.access = access;
    }

    public record PreferenceBody(String type, Boolean enabled) {}

    public record ActiveBody(Boolean active) {}

    private MemberContext ctx(Actor actor, UUID businessId, UUID memberId) {
        return access.member(actor, businessId, memberId);
    }

    // ---------- bandeja ----------

    @GetMapping("/notifications")
    public PageResponse<NotificationService.NotificationView> list(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                                                    @RequestParam(defaultValue = "false") boolean unreadOnly, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "30") int size) {
        return notifications.list(ctx(actor, businessId, memberId), unreadOnly, page, size);
    }

    @GetMapping("/notifications/unread-count")
    public Map<String, Long> unread(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId) {
        return Map.of("unread", notifications.unreadCount(ctx(actor, businessId, memberId)));
    }

    @PostMapping("/notifications/read-all")
    public Map<String, Integer> readAll(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId) {
        return Map.of("marked", notifications.markAllRead(ctx(actor, businessId, memberId)));
    }

    @PostMapping("/notifications/{id}/read")
    public NotificationService.NotificationView read(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID id, @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId) {
        return notifications.markRead(ctx(actor, businessId, memberId), id);
    }

    // ---------- preferencias, ajustes y tokens ----------

    @GetMapping("/notification-preferences")
    public Map<String, Boolean> preferences(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId) {
        return notifications.preferences(ctx(actor, businessId, memberId));
    }

    @PutMapping("/notification-preferences")
    public Map<String, Boolean> setPreference(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId, @RequestBody PreferenceBody body) {
        return notifications.setPreference(ctx(actor, businessId, memberId), body.type(), !Boolean.FALSE.equals(body.enabled()));
    }

    @GetMapping("/notification-settings")
    public NotificationService.Settings settings(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId) {
        ctx(actor, businessId, memberId);
        return notifications.settings(businessId);
    }

    @PutMapping("/notification-settings")
    public NotificationService.Settings updateSettings(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                                       @RequestBody NotificationService.Settings body) {
        return notifications.updateSettings(ctx(actor, businessId, memberId), body);
    }

    @PutMapping("/push-tokens")
    public ResponseEntity<Void> registerToken(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                              @RequestBody NotificationService.TokenInput body) {
        notifications.registerToken(ctx(actor, businessId, memberId), body);
        return ResponseEntity.noContent().build();
    }

    // ---------- programadas ----------

    @GetMapping("/notification-schedules")
    public List<ScheduleService.ScheduleView> listSchedules(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId) {
        return schedules.list(ctx(actor, businessId, memberId));
    }

    @PutMapping("/notification-schedules/{id}")
    public ScheduleService.ScheduleView upsertSchedule(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID id, @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                                       @RequestBody ScheduleService.ScheduleInput body) {
        return schedules.upsert(ctx(actor, businessId, memberId), id, body);
    }

    @PostMapping("/notification-schedules/{id}/active")
    public ScheduleService.ScheduleView setActive(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID id, @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                                  @RequestBody ActiveBody body) {
        return schedules.setActive(ctx(actor, businessId, memberId), id, !Boolean.FALSE.equals(body.active()));
    }

    @PostMapping("/notification-schedules/{id}/send-now")
    public ScheduleService.ScheduleView sendNow(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID id, @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                                @RequestBody ScheduleService.ScheduleInput body) {
        return schedules.sendNow(ctx(actor, businessId, memberId), id, body);
    }

    @DeleteMapping("/notification-schedules/{id}")
    public ResponseEntity<Void> deleteSchedule(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID id, @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId) {
        schedules.delete(ctx(actor, businessId, memberId), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/notification-schedules/{id}/runs")
    public List<ScheduleService.RunView> runs(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID id, @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId) {
        return schedules.runs(ctx(actor, businessId, memberId), id);
    }
}
