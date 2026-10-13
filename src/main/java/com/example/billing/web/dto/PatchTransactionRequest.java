package com.example.billing.web.dto;

import com.example.billing.common.ApiException;
import com.example.billing.transaction.TransactionPatch;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.NullNode;

public record PatchTransactionRequest(
        Instant occurredAt,
        @Digits(integer = 17, fraction = 2) BigDecimal total,
        @Valid @Size(max = CreateTransactionRequest.MAX_CHARGE_LINES) List<TaxLineDto> taxLines,
        @Valid @Size(max = CreateTransactionRequest.MAX_CHARGE_LINES) List<FeeLineDto> feeLines,
        JsonNode metadata,
        String type,
        String externalId,
        String currency) {

    public TransactionPatch toCommand() {
        rejectImmutableFields();
        return new TransactionPatch(
                occurredAt,
                total,
                taxLines == null ? null : taxLines.stream().map(TaxLineDto::toDomain).toList(),
                feeLines == null ? null : feeLines.stream().map(FeeLineDto::toDomain).toList(),
                metadata instanceof NullNode ? null : metadata);
    }

    private void rejectImmutableFields() {
        if (type != null) {
            throw ApiException.immutableField("type");
        }
        if (externalId != null) {
            throw ApiException.immutableField("external_id");
        }
        if (currency != null) {
            throw ApiException.immutableField("currency");
        }
    }
}
