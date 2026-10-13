package com.example.billing.web.dto;

import com.example.billing.transaction.NewTransaction;
import com.example.billing.transaction.TransactionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

public record CreateTransactionRequest(
        @NotNull TransactionType type,
        @NotBlank @Size(max = 190) String externalId,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a three-letter ISO 4217 code")
        String currency,
        @NotNull Instant occurredAt,
        @NotNull @Digits(integer = 17, fraction = 2) BigDecimal total,
        @NotNull @Valid @Size(max = MAX_CHARGE_LINES) List<TaxLineDto> taxLines,
        @NotNull @Valid @Size(max = MAX_CHARGE_LINES) List<FeeLineDto> feeLines,
        @NotNull JsonNode metadata) {

    // Upper bound on the tax or fee lines of one transaction: a list bounded by rows is not
    // bounded at all if one row can be arbitrarily large.
    public static final int MAX_CHARGE_LINES = 50;

    public NewTransaction toCommand() {
        return new NewTransaction(
                externalId,
                type,
                currency,
                occurredAt,
                total,
                taxLines.stream().map(TaxLineDto::toDomain).toList(),
                feeLines.stream().map(FeeLineDto::toDomain).toList(),
                metadata);
    }
}
