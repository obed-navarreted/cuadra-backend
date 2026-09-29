package com.cuadra.api.notification;

import java.util.Map;

/**
 * Texto de respaldo, ya localizado por el servidor, para las notificaciones automáticas. La app arma el suyo con `type` y `args` en el idioma
 * del teléfono; esto se usa cuando la app no puede (push sin abrir la app, versiones viejas).
 */
final class NotificationText {
    record Text(String title, String body) {}

    private NotificationText() {}

    static Text render(NotificationService.Type type, Map<String, Object> a, String locale, String currency) {
        boolean es = !"en".equalsIgnoreCase(locale);
        String money = money(a, "differenceMinor", currency);
        return switch (type) {
            case LOW_STOCK -> es ? new Text("Queda poco " + s(a, "productName"), "Quedan " + s(a, "stock") + ". Conviene comprar más.")
                    : new Text("Running low: " + s(a, "productName"), s(a, "stock") + " left. Time to restock.");
            case OUT_OF_STOCK -> es ? new Text("Se agotó " + s(a, "productName"), "Ya no hay existencia registrada.")
                    : new Text(s(a, "productName") + " is out of stock", "No stock is left on record.");
            case SHIFT_CLOSED -> {
                long diff = l(a, "differenceMinor");
                String result = diff == 0 ? (es ? "Cuadró" : "Balanced") : diff < 0 ? (es ? "Faltó " : "Short ") + money(Math.abs(diff), currency) : (es ? "Sobró " : "Over ") + money(diff, currency);
                yield es ? new Text("Cierre de caja", s(a, "memberName") + " cerró la caja. " + result + ".") : new Text("Cash closing", s(a, "memberName") + " closed the register. " + result + ".");
            }
            case SHIFT_DIFFERENCE -> es ? new Text("Diferencia en el cierre", s(a, "memberName") + " cerró con una diferencia de " + money(Math.abs(l(a, "differenceMinor")), currency) + ".")
                    : new Text("Closing difference", s(a, "memberName") + " closed with a difference of " + money(Math.abs(l(a, "differenceMinor")), currency) + ".");
            case SHIFT_NOT_CLOSED -> es ? new Text("Caja sin cerrar", "El turno de " + s(a, "memberName") + " sigue abierto.") : new Text("Register not closed", s(a, "memberName") + "'s shift is still open.");
            case SALE_DELETED -> es ? new Text("Venta eliminada", s(a, "memberName") + " eliminó una venta de " + money(l(a, "totalMinor"), currency) + ".")
                    : new Text("Sale deleted", s(a, "memberName") + " deleted a sale of " + money(l(a, "totalMinor"), currency) + ".");
            case DEVICE_STALE -> es ? new Text("Un teléfono no ha sincronizado", s(a, "deviceName") + " tiene " + s(a, "pending") + " operaciones sin enviar.")
                    : new Text("A phone has not synced", s(a, "deviceName") + " has " + s(a, "pending") + " operations waiting.");
            case PIN_LOCKOUT -> es ? new Text("PIN bloqueado", s(a, "memberName") + " se equivocó varias veces con el PIN.") : new Text("PIN locked", s(a, "memberName") + " entered the wrong PIN several times.");
            case MEMBER_JOINED -> es ? new Text("Se unió alguien al equipo", s(a, "memberName") + " aceptó tu invitación.") : new Text("Someone joined the team", s(a, "memberName") + " accepted your invitation.");
            case DAILY_SUMMARY -> es ? new Text("Resumen del día", s(a, "salesCount") + " ventas por " + money(l(a, "totalMinor"), currency) + ". Gastos: " + money(l(a, "expensesMinor"), currency) + ".")
                    : new Text("Daily summary", s(a, "salesCount") + " sales for " + money(l(a, "totalMinor"), currency) + ". Expenses: " + money(l(a, "expensesMinor"), currency) + ".");
            case SCHEDULED, PLATFORM_ANNOUNCEMENT -> new Text(s(a, "title"), s(a, "body"));
        };
    }

    private static String s(Map<String, Object> a, String k) {
        Object v = a.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    private static long l(Map<String, Object> a, String k) {
        Object v = a.get(k);
        return v instanceof Number n ? n.longValue() : 0;
    }

    private static String money(Map<String, Object> a, String key, String currency) {
        return money(Math.abs(l(a, key)), currency);
    }

    /** Solo para el texto de respaldo: la app formatea el dinero con la moneda y el país del negocio. */
    static String money(long minor, String currency) {
        int decimals = switch (currency == null ? "" : currency) { case "CRC", "COP", "CLP", "PYG" -> 0; default -> 2; };
        return currency + " " + new java.math.BigDecimal(minor).movePointLeft(decimals).setScale(decimals).toPlainString();
    }
}
