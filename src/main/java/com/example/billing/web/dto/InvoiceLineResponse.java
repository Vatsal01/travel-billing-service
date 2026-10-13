package com.example.billing.web.dto;

import com.example.billing.invoice.InvoiceLine;
import com.example.billing.common.Amounts;
import com.example.billing.transaction.TransactionType;
import com.fasterxml.jackson.annotation.JsonRawValue;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record InvoiceLineResponse(
        String transactionId,
        String externalId,
        TransactionType type,
        String currency,
        Instant occurredAt,
        BigDecimal total,
        List<TaxLineDto> taxLines,
        List<FeeLineDto> feeLines,
        @JsonRawValue String metadata) {

    public static InvoiceLineResponse of(InvoiceLine line) {
        return new InvoiceLineResponse(
                line.transactionId(),
                line.externalId(),
                line.type(),
                line.currency(),
                line.occurredAt(),
                Amounts.money(line.total()),
                line.taxLines().stream().map(TaxLineDto::of).toList(),
                line.feeLines().stream().map(FeeLineDto::of).toList(),
                line.metadata());
    }
}
