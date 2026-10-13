package com.example.billing.invoice;

import com.example.billing.transaction.FeeLine;
import com.example.billing.transaction.TaxLine;
import com.example.billing.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record InvoiceLine(
        String transactionId,
        String externalId,
        TransactionType type,
        String currency,
        Instant occurredAt,
        BigDecimal total,
        String metadata,
        List<TaxLine> taxLines,
        List<FeeLine> feeLines) {
}
