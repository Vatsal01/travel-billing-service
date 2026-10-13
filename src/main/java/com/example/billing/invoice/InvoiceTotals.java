package com.example.billing.invoice;

import com.example.billing.common.Amounts;
import com.example.billing.transaction.FeeLine;
import com.example.billing.transaction.TaxLine;
import java.math.BigDecimal;
import java.util.List;

// The money on an invoice, derived from the lines being billed.
public record InvoiceTotals(BigDecimal total, BigDecimal taxTotal, BigDecimal feeTotal) {

    public static InvoiceTotals of(List<InvoiceLine> lines) {
        BigDecimal total = Amounts.sum(lines.stream().map(InvoiceLine::total).toList());
        BigDecimal taxTotal = Amounts.sum(lines.stream()
                .flatMap(line -> line.taxLines().stream())
                .map(TaxLine::amount)
                .toList());
        BigDecimal feeTotal = Amounts.sum(lines.stream()
                .flatMap(line -> line.feeLines().stream())
                .map(FeeLine::amount)
                .toList());
        return new InvoiceTotals(total, taxTotal, feeTotal);
    }
}
