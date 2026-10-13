package com.example.billing.common;

import java.util.List;
import java.util.Map;


public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> properties;

    private ApiException(ErrorCode code, String detail, Map<String, Object> properties) {
        super(detail);
        this.code = code;
        this.properties = Map.copyOf(properties);
    }

    private ApiException(ErrorCode code, String detail) {
        this(code, detail, Map.of());
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> properties() {
        return properties;
    }

    public static ApiException transactionNotFound(String id) {
        return new ApiException(ErrorCode.TRANSACTION_NOT_FOUND, "No transaction with id '" + id + "'");
    }

    public static ApiException invoiceNotFound(String id) {
        return new ApiException(ErrorCode.INVOICE_NOT_FOUND, "No invoice with id '" + id + "'");
    }

    public static ApiException duplicateExternalId(String externalId, String existingId) {
        return new ApiException(ErrorCode.DUPLICATE_EXTERNAL_ID,
                "A transaction with external_id '" + externalId + "' already exists",
                Map.of("external_id", externalId, "existing_id", existingId));
    }

    public static ApiException transactionAlreadyInvoiced(String id, List<String> invoiceIds) {
        return new ApiException(ErrorCode.TRANSACTION_ALREADY_INVOICED,
                "Transaction '" + id + "' is already billed on an invoice and cannot be changed or deleted",
                Map.of("transaction_id", id, "invoice_ids", invoiceIds));
    }

    public static ApiException duplicateTransactionOnInvoice(List<String> duplicateIds) {
        return new ApiException(ErrorCode.DUPLICATE_TRANSACTION_ON_INVOICE,
                "The same transaction id was listed more than once: " + duplicateIds,
                Map.of("transaction_ids", duplicateIds));
    }

    public static ApiException mixedCurrency(List<String> currencies) {
        return new ApiException(ErrorCode.MIXED_CURRENCY,
                "All transactions on an invoice must share one currency, got " + currencies,
                Map.of("currencies", currencies));
    }

    public static ApiException validationFailed(String detail) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, detail);
    }

    public static ApiException validationFailed(String detail, List<String> errors) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, detail, Map.of("errors", errors));
    }

    public static ApiException immutableField(String field) {
        return new ApiException(ErrorCode.IMMUTABLE_FIELD,
                "Field '" + field + "' cannot be changed after creation", Map.of("field", field));
    }

    public static ApiException noUpdatableFields(List<String> supported) {
        return new ApiException(ErrorCode.NO_UPDATABLE_FIELDS,
                "Request body contained none of the updatable fields", Map.of("updatable_fields", supported));
    }
}
