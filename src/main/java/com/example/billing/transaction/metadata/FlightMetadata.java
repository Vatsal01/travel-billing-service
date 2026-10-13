package com.example.billing.transaction.metadata;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record FlightMetadata(
        @NotBlank String origin,
        @NotBlank String destination,
        @NotNull @Digits(integer = 17, fraction = 2) BigDecimal fare,
        @NotNull @Valid List<Coupon> coupons) implements TransactionMetadata {

    public record Coupon(
            @NotBlank String flightNumber,
            String carrier,
            @NotBlank String from,
            @NotBlank String to,
            @NotNull Instant departure,
            @NotNull Instant arrival) {
    }
}
