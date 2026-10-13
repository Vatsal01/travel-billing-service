package com.example.billing.web.dto;

import com.example.billing.common.ApiException;
import java.util.List;

public record InvoiceRequest(String transactionId, List<String> transactionIds) {

    public static final int MAX_TRANSACTIONS_PER_INVOICE = 200;

    public List<String> ids() {
        boolean hasSingle = transactionId != null && !transactionId.isBlank();
        boolean hasMany = transactionIds != null && !transactionIds.isEmpty();
        if (hasSingle == hasMany) {
            throw ApiException.validationFailed(
                    "provide exactly one of 'transaction_id' or a non-empty 'transaction_ids'");
        }
        if (hasSingle) {
            return List.of(transactionId);
        }
        if (transactionIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw ApiException.validationFailed("'transaction_ids' must not contain blank ids");
        }
        if (transactionIds.size() > MAX_TRANSACTIONS_PER_INVOICE) {
            throw ApiException.validationFailed("an invoice may bill at most "
                    + MAX_TRANSACTIONS_PER_INVOICE + " transactions, got " + transactionIds.size());
        }
        return List.copyOf(transactionIds);
    }
}
