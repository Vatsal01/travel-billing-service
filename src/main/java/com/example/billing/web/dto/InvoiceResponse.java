package com.example.billing.web.dto;

import com.example.billing.common.Amounts;
import com.example.billing.invoice.InvoiceDetail;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;


public record InvoiceResponse(
        String id,
        String currency,
        BigDecimal total,
        BigDecimal taxTotal,
        BigDecimal feeTotal,
        int lineCount,
        Instant createdAt,
        Instant updatedAt,
        List<InvoiceLineResponse> transactions,
        List<TaxLineDto> taxRollup,
        List<FeeLineDto> feeRollup) {

    public static InvoiceResponse of(InvoiceDetail detail) {
        return new InvoiceResponse(
                detail.invoice().id(),
                detail.invoice().currency(),
                Amounts.money(detail.invoice().total()),
                Amounts.money(detail.invoice().taxTotal()),
                Amounts.money(detail.invoice().feeTotal()),
                detail.invoice().lineCount(),
                detail.invoice().createdAt(),
                detail.invoice().updatedAt(),
                detail.lines().stream().map(InvoiceLineResponse::of).toList(),
                detail.taxRollup().stream().map(TaxLineDto::of).toList(),
                detail.feeRollup().stream().map(FeeLineDto::of).toList());
    }
}
