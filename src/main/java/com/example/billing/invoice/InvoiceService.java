package com.example.billing.invoice;

import com.example.billing.common.ApiException;
import com.example.billing.common.Ids;
import com.example.billing.common.Page;
import com.example.billing.transaction.Transaction;
import com.example.billing.transaction.TransactionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
public class InvoiceService {

    private final InvoiceRepository invoices;
    private final InvoiceSnapshotRepository snapshots;
    private final TransactionRepository transactions;
    private final Clock clock;

    public InvoiceService(InvoiceRepository invoices,
                          InvoiceSnapshotRepository snapshots,
                          TransactionRepository transactions,
                          Clock clock) {
        this.invoices = invoices;
        this.snapshots = snapshots;
        this.transactions = transactions;
        this.clock = clock;
    }

    // Builds a new invoice from the current state of the given transactions.

    @Transactional
    public InvoiceDetail create(List<String> transactionIds) {
        List<InvoiceLine> lines = snapshotOf(transactionIds);
        InvoiceTotals totals = InvoiceTotals.of(lines);
        Instant now = clock.instant();

        Invoice invoice = new Invoice(
                Ids.invoiceId(),
                lines.getFirst().currency(),
                totals.total(),
                totals.taxTotal(),
                totals.feeTotal(),
                lines.size(),
                now,
                now);
        invoices.insert(invoice);
        snapshots.insertLines(invoice.id(), lines);
        return detailOf(invoice);
    }

    public InvoiceDetail get(String id) {
        return detailOf(invoices.findById(id).orElseThrow(() -> ApiException.invoiceNotFound(id)));
    }

    public Page<Invoice> list(int limit, int offset) {
        return invoices.findPage(limit, offset);
    }

    // Replaces the set of billed transactions and rebuilds the snapshot from what is stored now.
    
    @Transactional
    public InvoiceDetail replaceTransactions(String id, List<String> transactionIds) {
        Invoice existing = invoices.lockById(id).orElseThrow(() -> ApiException.invoiceNotFound(id));

        List<InvoiceLine> lines = snapshotOf(transactionIds);
        InvoiceTotals totals = InvoiceTotals.of(lines);

        Invoice rebuilt = new Invoice(
                existing.id(),
                lines.getFirst().currency(),
                totals.total(),
                totals.taxTotal(),
                totals.feeTotal(),
                lines.size(),
                existing.createdAt(),
                clock.instant());
        snapshots.deleteLines(id);
        snapshots.insertLines(id, lines);
        invoices.update(rebuilt);
        return detailOf(rebuilt);
    }

    //Deletes the invoice and its snapshot. The billed transactions stay.
    @Transactional
    public void delete(String id) {
        invoices.lockById(id).orElseThrow(() -> ApiException.invoiceNotFound(id));
        invoices.deleteById(id);
    }

    // Loads the requested transactions and copies them into invoice lines.
    
     
    private List<InvoiceLine> snapshotOf(List<String> requestedIds) {
        if (requestedIds.isEmpty()) {
            throw ApiException.validationFailed("an invoice needs at least one transaction id");
        }
        rejectDuplicates(requestedIds);

        Map<String, Transaction> locked = new LinkedHashMap<>();
        for (String id : new TreeSet<>(requestedIds)) {
            locked.put(id, transactions.lockById(id)
                    .orElseThrow(() -> ApiException.transactionNotFound(id)));
        }
        rejectMixedCurrency(locked.values());

        return requestedIds.stream().map(id -> toLine(locked.get(id))).toList();
    }

    private static void rejectDuplicates(List<String> requestedIds) {
        Set<String> seen = new HashSet<>();
        List<String> duplicates = new ArrayList<>();
        for (String id : requestedIds) {
            if (!seen.add(id) && !duplicates.contains(id)) {
                duplicates.add(id);
            }
        }
        if (!duplicates.isEmpty()) {
            throw ApiException.duplicateTransactionOnInvoice(duplicates);
        }
    }

    private static void rejectMixedCurrency(Iterable<Transaction> transactions) {
        Set<String> currencies = new TreeSet<>();
        transactions.forEach(transaction -> currencies.add(transaction.currency()));
        if (currencies.size() > 1) {
            throw ApiException.mixedCurrency(List.copyOf(currencies));
        }
    }

    private static InvoiceLine toLine(Transaction transaction) {
        return new InvoiceLine(
                transaction.id(),
                transaction.externalId(),
                transaction.type(),
                transaction.currency(),
                transaction.occurredAt(),
                transaction.total(),
                transaction.metadata(),
                transaction.taxLines(),
                transaction.feeLines());
    }

    private InvoiceDetail detailOf(Invoice invoice) {
        return new InvoiceDetail(
                invoice,
                snapshots.findLines(invoice.id()),
                snapshots.taxRollup(invoice.id()),
                snapshots.feeRollup(invoice.id()));
    }
}
