package com.example.billing.invoice;

import com.example.billing.transaction.FeeLine;
import com.example.billing.transaction.TaxLine;
import java.util.List;

public record InvoiceDetail(
        Invoice invoice,
        List<InvoiceLine> lines,
        List<TaxLine> taxRollup,
        List<FeeLine> feeRollup) {
}
