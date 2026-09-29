package com.cuadra.api.device;

import com.cuadra.api.security.Actor;
import com.cuadra.api.tenancy.Access;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
public class DeviceController {
    private final DeviceService devices;
    private final Access access;

    public DeviceController(DeviceService devices, Access access) {
        this.devices = devices;
        this.access = access;
    }

    public record LinkRequestBody(@NotBlank @Size(max = 80) String deviceName, @Size(max = 80) String model,
                                  @Size(max = 40) String osVersion, @Size(max = 40) String appVersion) {}

    public record ClaimBody(@NotBlank String code, @Size(max = 80) String name, UUID cashRegisterId) {}

    /** El teléfono nuevo pide un código (sin sesión: todavía no pertenece a ningún negocio). */
    @PostMapping("/devices/link-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceService.LinkRequestCreated createLinkRequest(@Valid @RequestBody LinkRequestBody body) {
        return devices.createLinkRequest(new DeviceService.LinkRequestInfo(body.deviceName(), body.model(), body.osVersion(), body.appVersion()));
    }

    /** El teléfono pregunta si ya lo reclamaron; el secreto solo lo conoce él. */
    @GetMapping("/devices/link-requests/{code}")
    public DeviceService.LinkStatus poll(@PathVariable String code, @RequestHeader(value = "X-Poll-Secret", required = false) String secret) {
        return devices.poll(code, secret);
    }

    @PostMapping("/b/{businessId}/devices/claim")
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceService.DeviceView claim(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId,
                                          @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberHeader,
                                          @Valid @RequestBody ClaimBody body) {
        return devices.claim(access.member(actor, businessId, memberHeader), body.code(), body.name(), body.cashRegisterId());
    }

    @PostMapping("/b/{businessId}/devices/self")
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceService.SelfLinked selfLink(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId,
                                             @Valid @RequestBody LinkRequestBody body) {
        if (actor.isDevice()) throw com.cuadra.api.common.ApiException.forbidden("GOOGLE_REQUIRED", "A linked phone cannot link other phones without a code");
        return devices.selfLink(access.member(actor, businessId, null),
                new DeviceService.LinkRequestInfo(body.deviceName(), body.model(), body.osVersion(), body.appVersion()));
    }

    @GetMapping("/b/{businessId}/devices")
    public List<DeviceService.DeviceView> list(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId,
                                               @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberHeader) {
        access.member(actor, businessId, memberHeader).require(com.cuadra.api.tenancy.Role.Permission.MANAGE_DEVICES);
        return devices.list(businessId);
    }

    @DeleteMapping("/b/{businessId}/devices/{deviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID deviceId,
                       @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberHeader) {
        devices.revoke(access.member(actor, businessId, memberHeader), deviceId);
    }
}
