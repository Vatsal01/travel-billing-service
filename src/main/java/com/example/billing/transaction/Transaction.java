package com.example.billing.transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record Transaction(
        String id,
        String externalId,
        TransactionType type,
        String currency,
        Instant occurredAt,
        BigDecimal total,
        String metadata,
        List<TaxLine> taxLines,
        List<FeeLine> feeLines,
        Instant createdAt,
        Instant updatedAt) {
}
