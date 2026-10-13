package com.example.billing.web.dto;

import com.example.billing.common.Amounts;
import com.example.billing.invoice.Invoice;
import java.math.BigDecimal;
import java.time.Instant;

public record InvoiceSummaryResponse(
        String id,
        String currency,
        BigDecimal total,
        BigDecimal taxTotal,
        BigDecimal feeTotal,
        int lineCount,
        Instant createdAt,
        Instant updatedAt) {

    public static InvoiceSummaryResponse of(Invoice invoice) {
        return new InvoiceSummaryResponse(
                invoice.id(),
                invoice.currency(),
                Amounts.money(invoice.total()),
                Amounts.money(invoice.taxTotal()),
                Amounts.money(invoice.feeTotal()),
                invoice.lineCount(),
                invoice.createdAt(),
                invoice.updatedAt());
    }
}
