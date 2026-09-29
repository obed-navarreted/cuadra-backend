package com.cuadra.api.notification;

import com.cuadra.api.common.ApiException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Cuándo se repite una programación. Se calcula siempre en la zona horaria del negocio con `java.time` (así un cambio de horario de verano
 * no mueve la hora local). Solo el subconjunto que ofrece la pantalla: una vez, diario, semanal, mensual, cada N días.
 * `time` es "HH:mm"; `at` (solo ONCE) es "yyyy-MM-ddTHH:mm" local; `days` son días ISO (1 = lunes … 7 = domingo).
 */
public record ScheduleRule(String type, String time, String at, List<Integer> days, Integer dayOfMonth, Integer everyDays, String startDate, String endDate) {
    private static final Set<String> TYPES = Set.of("ONCE", "DAILY", "WEEKLY", "MONTHLY", "EVERY_N_DAYS");
    private static final int SEARCH_DAYS = 400;

    /** Valida la regla y devuelve una copia con lo que falte (fecha de inicio = hoy en la zona del negocio). */
    public ScheduleRule validated(ZoneId zone, java.time.Instant now) {
        if (type == null || !TYPES.contains(type)) throw ApiException.badRequest("INVALID_RULE", "Invalid repeat rule");
        try {
            if (type.equals("ONCE")) {
                if (at == null) throw ApiException.badRequest("INVALID_RULE", "Date and time are required");
                LocalDateTime.parse(at);
                return this;
            }
            if (time == null) throw ApiException.badRequest("INVALID_RULE", "Time is required");
            LocalTime.parse(time);
            if (endDate != null) LocalDate.parse(endDate);
            String start = startDate != null ? startDate : now.atZone(zone).toLocalDate().toString();
            LocalDate.parse(start);
            if (type.equals("WEEKLY") && (days == null || days.isEmpty() || days.stream().anyMatch(d -> d == null || d < 1 || d > 7))) throw ApiException.badRequest("INVALID_RULE", "Pick at least one weekday");
            if (type.equals("MONTHLY") && (dayOfMonth == null || dayOfMonth < 1 || dayOfMonth > 31)) throw ApiException.badRequest("INVALID_RULE", "Invalid day of month");
            if (type.equals("EVERY_N_DAYS") && (everyDays == null || everyDays < 1 || everyDays > 365)) throw ApiException.badRequest("INVALID_RULE", "Invalid interval");
            return new ScheduleRule(type, time, null, days, dayOfMonth, everyDays, start, endDate);
        } catch (java.time.format.DateTimeParseException e) {
            throw ApiException.badRequest("INVALID_RULE", "Invalid date or time");
        }
    }

    /** El siguiente envío ESTRICTAMENTE después de `after`, o vacío si la regla ya terminó. */
    public Optional<java.time.Instant> next(ZoneId zone, java.time.Instant after) {
        if (type.equals("ONCE")) {
            java.time.Instant when = LocalDateTime.parse(at).atZone(zone).toInstant();
            return when.isAfter(after) ? Optional.of(when) : Optional.empty();
        }
        LocalTime t = LocalTime.parse(time);
        LocalDate start = startDate == null ? LocalDate.MIN : LocalDate.parse(startDate);
        LocalDate end = endDate == null ? null : LocalDate.parse(endDate);
        LocalDate first = after.atZone(zone).toLocalDate();
        if (first.isBefore(start)) first = start;
        for (int i = 0; i < SEARCH_DAYS; i++) {
            LocalDate day = first.plusDays(i);
            if (end != null && day.isAfter(end)) return Optional.empty();
            if (!matches(day, start)) continue;
            // `ZonedDateTime.of` resuelve horas que no existen (salto de horario) hacia adelante.
            java.time.Instant candidate = ZonedDateTime.of(day, t, zone).toInstant();
            if (candidate.isAfter(after)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    private boolean matches(LocalDate day, LocalDate start) {
        return switch (type) {
            case "DAILY" -> true;
            case "WEEKLY" -> days.contains(day.getDayOfWeek().getValue());
            case "MONTHLY" -> day.getDayOfMonth() == Math.min(dayOfMonth, day.lengthOfMonth());
            case "EVERY_N_DAYS" -> !day.isBefore(start) && ChronoUnit.DAYS.between(start, day) % everyDays == 0;
            default -> false;
        };
    }

}
