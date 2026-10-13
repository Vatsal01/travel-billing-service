package com.example.billing.transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

public record NewTransaction(
        String externalId,
        TransactionType type,
        String currency,
        Instant occurredAt,
        BigDecimal total,
        List<TaxLine> taxLines,
        List<FeeLine> feeLines,
        JsonNode metadata) {
}
