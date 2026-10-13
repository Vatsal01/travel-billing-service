package com.example.billing.transaction;

import com.example.billing.common.ApiException;
import com.example.billing.common.Ids;
import com.example.billing.common.Page;
import com.example.billing.transaction.metadata.MetadataCodec;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);
    private static final List<String> UPDATABLE_FIELDS =
            List.of("occurred_at", "total", "tax_lines", "fee_lines", "metadata");

    private final TransactionRepository repository;
    private final BilledTransactions billedTransactions;
    private final MetadataCodec metadataCodec;
    private final Clock clock;

    public TransactionService(TransactionRepository repository,
                              BilledTransactions billedTransactions,
                              MetadataCodec metadataCodec,
                              Clock clock) {
        this.repository = repository;
        this.billedTransactions = billedTransactions;
        this.metadataCodec = metadataCodec;
        this.clock = clock;
    }


    public Transaction create(NewTransaction request) {
        String metadata = metadataCodec.validateAndSerialise(request.type(), request.metadata());

        repository.findByExternalId(request.externalId())
                .ifPresent(existing -> {
                    throw ApiException.duplicateExternalId(request.externalId(), existing.id());
                });

        Instant now = clock.instant();
        Transaction transaction = new Transaction(
                Ids.transactionId(),
                request.externalId(),
                request.type(),
                request.currency(),
                request.occurredAt(),
                request.total(),
                metadata,
                List.copyOf(request.taxLines()),
                List.copyOf(request.feeLines()),
                now,
                now);
        try {
            repository.insert(transaction);
            return transaction;
        } catch (DuplicateKeyException lostTheRace) {
            Transaction winner = repository.findByExternalId(request.externalId())
                    .orElseThrow(() -> lostTheRace);
            log.debug("Concurrent create for external_id={} resolved to existing id={}",
                    request.externalId(), winner.id());
            throw ApiException.duplicateExternalId(request.externalId(), winner.id());
        }
    }

    public Transaction get(String id) {
        return repository.findById(id).orElseThrow(() -> ApiException.transactionNotFound(id));
    }

    public Page<Transaction> list(int limit, int offset) {
        return repository.findPage(limit, offset);
    }

    /**
     * Applies a partial update. Nothing is written if the transaction is already on an invoice:
     * the invoice holds billed money and must not be rewritten from underneath.
     */
    @Transactional
    public Transaction patch(String id, TransactionPatch patch) {
        if (patch.isEmpty()) {
            throw ApiException.noUpdatableFields(UPDATABLE_FIELDS);
        }
        Transaction stored = lockOrNotFound(id);
        refuseIfBilled(id);

        String metadata = patch.metadata() == null
                ? stored.metadata()
                : metadataCodec.validateAndSerialise(stored.type(), patch.metadata());

        Transaction updated = new Transaction(
                stored.id(),
                stored.externalId(),
                stored.type(),
                stored.currency(),
                Objects.requireNonNullElse(patch.occurredAt(), stored.occurredAt()),
                Objects.requireNonNullElse(patch.total(), stored.total()),
                metadata,
                patch.taxLines() == null ? stored.taxLines() : List.copyOf(patch.taxLines()),
                patch.feeLines() == null ? stored.feeLines() : List.copyOf(patch.feeLines()),
                stored.createdAt(),
                clock.instant());
        repository.update(updated);
        return updated;
    }

    // Deletes the transaction and its charge lines, unless an invoice already bills it.
    @Transactional
    public void delete(String id) {
        lockOrNotFound(id);
        refuseIfBilled(id);
        repository.deleteById(id);
    }

    private Transaction lockOrNotFound(String id) {
        return repository.lockById(id).orElseThrow(() -> ApiException.transactionNotFound(id));
    }

    private void refuseIfBilled(String id) {
        List<String> invoiceIds = billedTransactions.invoiceIdsFor(id);
        if (!invoiceIds.isEmpty()) {
            throw ApiException.transactionAlreadyInvoiced(id, invoiceIds);
        }
    }
}
