package com.example.billing.transaction;

import java.util.List;

public interface BilledTransactions {

    // Ids of the invoices that already include this transaction; empty when it is not billed.
    List<String> invoiceIdsFor(String transactionId);
}
