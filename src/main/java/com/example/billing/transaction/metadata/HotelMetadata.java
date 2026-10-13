package com.example.billing.transaction.metadata;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

// Hotel: the property's name and postal address, the stay, and the nightly rate.
public record HotelMetadata(
        @NotBlank String name,
        @NotNull @Valid Address address,
        @NotNull LocalDate checkIn,
        @NotNull LocalDate checkOut,
        @NotNull @Positive Integer nights,
        @NotNull @Digits(integer = 17, fraction = 2) BigDecimal roomRatePerNight,
        @Valid List<Extra> extras) implements TransactionMetadata {

    public record Address(
            @NotBlank String street,
            @NotBlank String city,
            @NotBlank String country) {
    }

    // Priced extras on the stay (breakfast, parking). Not a tax and not a carrier fee.
    public record Extra(
            @NotBlank String name,
            @NotNull @Digits(integer = 17, fraction = 2) BigDecimal amount) {
    }

    @AssertTrue(message = "check_out must be after check_in")
    public boolean isStayOrdered() {
        return checkIn == null || checkOut == null || checkOut.isAfter(checkIn);
    }
}
