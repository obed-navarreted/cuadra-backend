package com.cuadra.api.sale;

import com.cuadra.api.common.ApiException;
import java.math.BigInteger;

/** Aritmética de dinero sin Double: enteros en unidad menor y cantidades en milésimas. */
public final class SaleMath {
    public static final long MAX_MINOR = 1_000_000_000_000L;
    private static final BigInteger THOUSAND = BigInteger.valueOf(1000);

    private SaleMath() {}

    /** round_half_up(precio × cantidad / 1000) − descuento. Nunca negativo ni desbordado. */
    public static long lineTotal(long unitPriceMinor, long quantityMilli, long discountMinor) {
        BigInteger[] qr = BigInteger.valueOf(unitPriceMinor).multiply(BigInteger.valueOf(quantityMilli)).divideAndRemainder(THOUSAND);
        BigInteger gross = qr[0];
        if (qr[1].shiftLeft(1).compareTo(THOUSAND) >= 0) gross = gross.add(BigInteger.ONE);
        BigInteger net = gross.subtract(BigInteger.valueOf(discountMinor));
        if (net.signum() < 0) throw ApiException.badRequest("INVALID_DISCOUNT", "Discount exceeds the line amount");
        if (net.compareTo(BigInteger.valueOf(MAX_MINOR)) > 0) throw ApiException.badRequest("AMOUNT_TOO_LARGE", "Amount too large");
        return net.longValueExact();
    }
}
