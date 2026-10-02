package com.cuadra.api.catalog;

import com.cuadra.api.security.Actor;
import com.cuadra.api.tenancy.Access;
import com.cuadra.api.tenancy.MemberContext;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Promociones por cantidad. Leer: cualquiera del negocio. Crear, cambiar, pausar y borrar: dueño y admins. */
@RestController
@RequestMapping("/api/b/{businessId}/promotions")
public class PromotionController {
    private final PromotionService promotions;
    private final Access access;

    public PromotionController(PromotionService promotions, Access access) {
        this.promotions = promotions;
        this.access = access;
    }

    public record PromotionActiveBody(Boolean active) {}

    @GetMapping
    public List<PromotionService.PromotionView> list(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId,
                                                     @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId) {
        return promotions.list(access.member(actor, businessId, memberId));
    }

    @GetMapping("/{id}")
    public PromotionService.PromotionView get(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID id,
                                              @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId) {
        return promotions.get(access.member(actor, businessId, memberId), id);
    }

    /** Crea (201) o reemplaza (200) con el id que elige el cliente. */
    @PutMapping("/{id}")
    public ResponseEntity<PromotionService.PromotionView> upsert(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID id,
                                                                 @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId,
                                                                 @RequestBody PromotionService.PromotionInput body) {
        MemberContext ctx = access.member(actor, businessId, memberId);
        PromotionService.Result r = promotions.upsert(ctx, id, body);
        return ResponseEntity.status(r.outcome() == PromotionService.Outcome.CREATED ? HttpStatus.CREATED : HttpStatus.OK).body(r.promotion());
    }

    /** Pausar (`false`) o reanudar (`true`). */
    @PostMapping("/{id}/active")
    public PromotionService.PromotionView setActive(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID id,
                                                    @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId, @RequestBody PromotionActiveBody body) {
        return promotions.setActive(access.member(actor, businessId, memberId), id, body == null || body.active() == null || body.active());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @PathVariable UUID id,
                       @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId) {
        promotions.delete(access.member(actor, businessId, memberId), id);
    }
}
