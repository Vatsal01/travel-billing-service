package com.example.billing.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

public final class Amounts {

    // Scale of every monetary column and every monetary value in a response.
    public static final int MONEY_SCALE = 2;

    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(MONEY_SCALE);

    private Amounts() {
    }

    /** Normalises a monetary value to the stored scale. */
    public static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal sum(Collection<BigDecimal> values) {
        return values.stream().map(Amounts::money).reduce(ZERO, BigDecimal::add);
    }


    public static BigDecimal rate(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
