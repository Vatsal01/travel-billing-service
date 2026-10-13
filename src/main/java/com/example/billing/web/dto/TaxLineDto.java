package com.example.billing.web.dto;

import com.example.billing.common.Amounts;
import com.example.billing.transaction.TaxLine;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record TaxLineDto(
        @NotBlank @Size(max = 120) String name,
        @Digits(integer = 3, fraction = 6) BigDecimal rate,
        @NotNull @Digits(integer = 17, fraction = 2) BigDecimal amount) {

    public TaxLine toDomain() {
        return new TaxLine(name, rate, amount);
    }

    public static TaxLineDto of(TaxLine line) {
        return new TaxLineDto(line.name(), Amounts.rate(line.rate()), Amounts.money(line.amount()));
    }
}
