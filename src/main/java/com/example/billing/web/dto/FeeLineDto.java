package com.example.billing.web.dto;

import com.example.billing.common.Amounts;
import com.example.billing.transaction.FeeLine;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record FeeLineDto(
        @NotBlank @Size(max = 120) String name,
        @NotNull @Digits(integer = 17, fraction = 2) BigDecimal amount) {

    public FeeLine toDomain() {
        return new FeeLine(name, amount);
    }

    
    public static FeeLineDto of(FeeLine line) {
        return new FeeLineDto(line.name(), Amounts.money(line.amount()));
    }
}
