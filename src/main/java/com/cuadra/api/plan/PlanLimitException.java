package com.cuadra.api.plan;

import com.cuadra.api.common.ApiException;
import org.springframework.http.HttpStatus;

/** Se llegó al límite del plan (solo para AGREGAR cosas: lo que ya existe sigue funcionando; la caja nunca se bloquea). */
public class PlanLimitException extends ApiException {
    private final PlanService.Feature feature;
    private final int limit;

    public PlanLimitException(PlanService.Feature feature, int limit) {
        super(HttpStatus.FORBIDDEN, "PLAN_LIMIT", "Plan limit reached: " + feature);
        this.feature = feature;
        this.limit = limit;
    }

    public PlanService.Feature feature() { return feature; }

    public int limit() { return limit; }
}
