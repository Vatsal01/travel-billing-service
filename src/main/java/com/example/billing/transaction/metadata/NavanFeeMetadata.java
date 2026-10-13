package com.example.billing.transaction.metadata;

import jakarta.validation.constraints.NotNull;

public record NavanFeeMetadata(
        @NotNull FeeKind feeKind,
        String description,
        String relatedExternalId) implements TransactionMetadata {
}
