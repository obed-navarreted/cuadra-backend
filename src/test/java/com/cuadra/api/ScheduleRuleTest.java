package com.cuadra.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cuadra.api.common.ApiException;
import com.cuadra.api.notification.ScheduleRule;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** El cálculo del siguiente envío es puro: se prueba con instantes y zonas explícitas, sin base de datos. */
class ScheduleRuleTest {
    private static final ZoneId MANAGUA = ZoneId.of("America/Managua");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private static ScheduleRule rule(String type, String time, List<Integer> days, Integer dom, Integer every, String start, String end) {
        return new ScheduleRule(type, time, null, days, dom, every, start, end);
    }

    @Test
    void weeklyMondayAtNineInManaguaIsDeliveredMondaysAtNineLocal() {
        ScheduleRule r = rule("WEEKLY", "09:00", List.of(1), null, null, "2026-09-01", null);
        // Viernes 2026-09-25 12:00 Managua = 18:00Z → el siguiente es el lunes 28 a las 09:00 local = 15:00Z.
        assertEquals(Instant.parse("2026-09-28T15:00:00Z"), r.next(MANAGUA, Instant.parse("2026-09-25T18:00:00Z")).orElseThrow());
        // Justo a las 9:00 del lunes ya salió: el siguiente es el lunes siguiente (estrictamente después).
        assertEquals(Instant.parse("2026-10-05T15:00:00Z"), r.next(MANAGUA, Instant.parse("2026-09-28T15:00:00Z")).orElseThrow());
    }

    @Test
    void dailyKeepsTheLocalHourAcrossADaylightSavingChange() {
        // En Nueva York el horario de verano termina el 2026-11-01 a las 2:00: 09:00 local pasa de 13:00Z a 14:00Z.
        ScheduleRule earlier = rule("DAILY", "09:00", null, null, null, "2026-10-30", null);
        assertEquals(Instant.parse("2026-10-31T13:00:00Z"), earlier.next(NEW_YORK, Instant.parse("2026-10-31T00:00:00Z")).orElseThrow());
        assertEquals(Instant.parse("2026-11-01T14:00:00Z"), earlier.next(NEW_YORK, Instant.parse("2026-10-31T13:00:00Z")).orElseThrow());
    }

    @Test
    void aTimeThatDoesNotExistBecauseOfTheSpringForwardMovesForward() {
        // 2026-03-08 en Nueva York: 02:30 no existe (2:00 salta a 3:00).
        ScheduleRule r = rule("DAILY", "02:30", null, null, null, "2026-03-08", null);
        Instant next = r.next(NEW_YORK, Instant.parse("2026-03-08T00:00:00Z")).orElseThrow();
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), next);
    }

    @Test
    void monthlyOnThe31stFallsOnTheLastDayOfShorterMonths() {
        ScheduleRule r = rule("MONTHLY", "08:00", null, 31, null, "2027-01-01", null);
        assertEquals(Instant.parse("2027-02-28T14:00:00Z"), r.next(MANAGUA, Instant.parse("2027-02-01T00:00:00Z")).orElseThrow());
        assertEquals(Instant.parse("2027-03-31T14:00:00Z"), r.next(MANAGUA, Instant.parse("2027-02-28T14:00:00Z")).orElseThrow());
    }

    @Test
    void everyNDaysCountsFromTheStartDate() {
        ScheduleRule r = rule("EVERY_N_DAYS", "10:00", null, null, 3, "2026-09-01", null);
        // 1, 4, 7, 10 … de septiembre.
        assertEquals(Instant.parse("2026-09-10T16:00:00Z"), r.next(MANAGUA, Instant.parse("2026-09-08T00:00:00Z")).orElseThrow());
    }

    @Test
    void aRuleThatEndedOrAOncePastHasNoNextRun() {
        assertTrue(rule("DAILY", "09:00", null, null, null, "2026-09-01", "2026-09-10").next(MANAGUA, Instant.parse("2026-09-11T00:00:00Z")).isEmpty());
        ScheduleRule once = new ScheduleRule("ONCE", null, "2026-09-20T09:00", null, null, null, null, null);
        assertEquals(Instant.parse("2026-09-20T15:00:00Z"), once.next(MANAGUA, Instant.parse("2026-09-19T00:00:00Z")).orElseThrow());
        assertTrue(once.next(MANAGUA, Instant.parse("2026-09-21T00:00:00Z")).isEmpty());
    }

    @Test
    void invalidRulesAreRejected() {
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        assertThrows(ApiException.class, () -> rule("WEEKLY", "09:00", List.of(), null, null, null, null).validated(MANAGUA, now));
        assertThrows(ApiException.class, () -> rule("WEEKLY", "09:00", List.of(8), null, null, null, null).validated(MANAGUA, now));
        assertThrows(ApiException.class, () -> rule("MONTHLY", "09:00", null, 32, null, null, null).validated(MANAGUA, now));
        assertThrows(ApiException.class, () -> rule("EVERY_N_DAYS", "09:00", null, null, 0, null, null).validated(MANAGUA, now));
        assertThrows(ApiException.class, () -> rule("DAILY", "25:99", null, null, null, null, null).validated(MANAGUA, now));
        assertThrows(ApiException.class, () -> rule("HOURLY", "09:00", null, null, null, null, null).validated(MANAGUA, now));
        assertEquals("2026-08-31", rule("DAILY", "09:00", null, null, null, null, null).validated(MANAGUA, now).startDate());
    }
}
