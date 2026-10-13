package com.example.billing.invoice;

import java.math.BigDecimal;
import java.time.Instant;

public record Invoice(
        String id,
        String currency,
        BigDecimal total,
        BigDecimal taxTotal,
        BigDecimal feeTotal,
        int lineCount,
        Instant createdAt,
        Instant updatedAt) {
}
