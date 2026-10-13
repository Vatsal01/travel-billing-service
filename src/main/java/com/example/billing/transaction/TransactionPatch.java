package com.example.billing.transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

public record TransactionPatch(
        Instant occurredAt,
        BigDecimal total,
        List<TaxLine> taxLines,
        List<FeeLine> feeLines,
        JsonNode metadata) {

    public boolean isEmpty() {
        return occurredAt == null && total == null && taxLines == null && feeLines == null && metadata == null;
    }
}
