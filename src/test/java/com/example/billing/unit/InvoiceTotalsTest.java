package com.example.billing.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.billing.invoice.InvoiceLine;
import com.example.billing.invoice.InvoiceTotals;
import com.example.billing.transaction.FeeLine;
import com.example.billing.transaction.TaxLine;
import com.example.billing.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InvoiceTotalsTest {

    @Test
    @DisplayName("totals are the sum of the billed lines, with taxes and fees reported separately")
    void sumsTheBilledLines() {
        List<InvoiceLine> lines = List.of(
                line(TransactionType.FLIGHT, "220.00",
                        List.of(new TaxLine("Airport tax", null, new BigDecimal("15.00"))),
                        List.of(new FeeLine("YQ", new BigDecimal("25.00")))),
                line(TransactionType.HOTEL, "331.70",
                        List.of(new TaxLine("VAT", new BigDecimal("0.07"), new BigDecimal("21.70"))),
                        List.of()),
                line(TransactionType.RAIL, "94.00", List.of(),
                        List.of(new FeeLine("Booking fee", new BigDecimal("4.00")))),
                line(TransactionType.NAVAN_FEE, "25.00", List.of(), List.of()),
                line(TransactionType.NAVAN_FEE, "15.00", List.of(), List.of()));

        InvoiceTotals totals = InvoiceTotals.of(lines);

        assertThat(totals.total()).isEqualByComparingTo("685.70");
        assertThat(totals.taxTotal()).isEqualByComparingTo("36.70");
        assertThat(totals.feeTotal()).isEqualByComparingTo("29.00");
    }

    @Test
    @DisplayName("a line with neither tax nor fee contributes only its total")
    void handlesLinesWithoutCharges() {
        InvoiceTotals totals = InvoiceTotals.of(
                List.of(line(TransactionType.NAVAN_FEE, "25.00", List.of(), List.of())));

        assertThat(totals.total()).isEqualByComparingTo("25.00");
        assertThat(totals.taxTotal()).isEqualByComparingTo("0.00");
        assertThat(totals.feeTotal()).isEqualByComparingTo("0.00");
    }

    private static InvoiceLine line(TransactionType type, String total,
                                    List<TaxLine> taxLines, List<FeeLine> feeLines) {
        return new InvoiceLine("txn_" + type, "ext-" + type, type, "EUR", Instant.EPOCH,
                new BigDecimal(total), "{}", taxLines, feeLines);
    }
}
