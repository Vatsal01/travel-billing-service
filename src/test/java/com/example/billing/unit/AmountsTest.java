package com.example.billing.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.billing.common.Amounts;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AmountsTest {

    @Test
    @DisplayName("money is normalised to two decimal places")
    void normalisesMoneyToTwoPlaces() {
        assertThat(Amounts.money(new BigDecimal("220"))).hasToString("220.00");
        assertThat(Amounts.money(new BigDecimal("220.5"))).hasToString("220.50");
        assertThat(Amounts.money(new BigDecimal("0"))).hasToString("0.00");
    }

    @Test
    @DisplayName("sums are exact, which binary floating point would not be")
    void sumsExactly() {
        List<BigDecimal> amounts = List.of(
                new BigDecimal("0.10"), new BigDecimal("0.20"), new BigDecimal("0.30"));

        assertThat(Amounts.sum(amounts)).isEqualByComparingTo("0.60");
        assertThat(0.1 + 0.2 + 0.3).isNotEqualTo(0.6);
    }

    @Test
    @DisplayName("the published trip figures add up to the published grand total")
    void sumsTheTripToTheExpectedGrandTotal() {
        List<BigDecimal> trip = List.of(
                new BigDecimal("220.00"), new BigDecimal("331.70"), new BigDecimal("94.00"),
                new BigDecimal("25.00"), new BigDecimal("15.00"));

        assertThat(Amounts.sum(trip)).isEqualByComparingTo("685.70");
    }

    @Test
    @DisplayName("an empty list sums to zero rather than to null")
    void sumsAnEmptyListToZero() {
        assertThat(Amounts.sum(List.of())).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("a rate keeps its value without the trailing zeros the column adds")
    void rendersRatesWithoutPaddingZeros() {
        assertThat(Amounts.rate(new BigDecimal("0.070000"))).hasToString("0.07");
        assertThat(Amounts.rate(new BigDecimal("0.000000"))).hasToString("0");
        assertThat(Amounts.rate(new BigDecimal("20.000000"))).hasToString("20");
        assertThat(Amounts.rate(null)).isNull();
    }
}
