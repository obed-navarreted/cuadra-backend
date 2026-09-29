package com.cuadra.api.common;

/** Cuerpo de las acciones que piden un motivo (anular, condonar, reabrir…). Un solo tipo para toda la API. */
public record ReasonBody(String reason) {}
