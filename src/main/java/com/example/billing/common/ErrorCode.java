package com.example.billing.common;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    IMMUTABLE_FIELD(HttpStatus.BAD_REQUEST),
    NO_UPDATABLE_FIELDS(HttpStatus.BAD_REQUEST),
    TRANSACTION_NOT_FOUND(HttpStatus.NOT_FOUND),
    INVOICE_NOT_FOUND(HttpStatus.NOT_FOUND),
    DUPLICATE_EXTERNAL_ID(HttpStatus.CONFLICT),
    TRANSACTION_ALREADY_INVOICED(HttpStatus.CONFLICT),
    DUPLICATE_TRANSACTION_ON_INVOICE(HttpStatus.CONFLICT),
    MIXED_CURRENCY(HttpStatus.CONFLICT),
    CONSTRAINT_VIOLATION(HttpStatus.CONFLICT),
    LOCK_TIMEOUT(HttpStatus.CONFLICT),
    DATA_ACCESS_FAILURE(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
