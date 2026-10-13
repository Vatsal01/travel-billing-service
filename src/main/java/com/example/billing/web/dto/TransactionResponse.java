package com.example.billing.web.dto;

import com.example.billing.common.Amounts;
import com.example.billing.transaction.Transaction;
import com.example.billing.transaction.TransactionType;
import com.fasterxml.jackson.annotation.JsonRawValue;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;


public record TransactionResponse(
        String id,
        String externalId,
        TransactionType type,
        String currency,
        Instant occurredAt,
        BigDecimal total,
        List<TaxLineDto> taxLines,
        List<FeeLineDto> feeLines,
        @JsonRawValue String metadata,
        Instant createdAt,
        Instant updatedAt) {

    public static TransactionResponse of(Transaction transaction) {
        return new TransactionResponse(
                transaction.id(),
                transaction.externalId(),
                transaction.type(),
                transaction.currency(),
                transaction.occurredAt(),
                Amounts.money(transaction.total()),
                transaction.taxLines().stream().map(TaxLineDto::of).toList(),
                transaction.feeLines().stream().map(FeeLineDto::of).toList(),
                transaction.metadata(),
                transaction.createdAt(),
                transaction.updatedAt());
    }
}
