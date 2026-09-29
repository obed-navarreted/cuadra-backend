package com.cuadra.api.plan;

import com.cuadra.api.security.Actor;
import com.cuadra.api.tenancy.Access;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/b/{businessId}")
public class PlanController {
    private final PlanService plans;
    private final Access access;

    public PlanController(PlanService plans, Access access) {
        this.plans = plans;
        this.access = access;
    }

    /** El plan que rige hoy, sus límites y cuánto se usa (para mostrar "2 de 3 teléfonos" y avisar antes de toparse con el tope). */
    @GetMapping("/plan")
    public PlanService.PlanView plan(@AuthenticationPrincipal Actor actor, @PathVariable UUID businessId, @RequestHeader(value = Access.MEMBER_HEADER, required = false) UUID memberId) {
        return plans.view(access.member(actor, businessId, memberId));
    }
}
