package com.example.billing.transaction;

import com.example.billing.common.Amounts;
import com.example.billing.common.Page;
import com.example.billing.common.SqlTime;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;


@Repository
public class TransactionRepository {

    private static final String COLUMNS =
            "id, external_id, type, currency, occurred_at, total, metadata, created_at, updated_at";

    private static final String SELECT = "SELECT " + COLUMNS + " FROM transactions ";

    private final JdbcClient db;

    public TransactionRepository(JdbcClient db) {
        this.db = db;
    }


    @Transactional
    public void insert(Transaction transaction) {
        db.sql("""
                INSERT INTO transactions (id, external_id, type, currency, occurred_at, total, metadata,
                                          created_at, updated_at)
                VALUES (:id, :externalId, :type, :currency, :occurredAt, :total, :metadata,
                        :createdAt, :updatedAt)
                """)
                .param("id", transaction.id())
                .param("externalId", transaction.externalId())
                .param("type", transaction.type().wireValue())
                .param("currency", transaction.currency())
                .param("occurredAt", SqlTime.toDatabase(transaction.occurredAt()))
                .param("total", Amounts.money(transaction.total()))
                .param("metadata", transaction.metadata())
                .param("createdAt", SqlTime.toDatabase(transaction.createdAt()))
                .param("updatedAt", SqlTime.toDatabase(transaction.updatedAt()))
                .update();
        insertChargeLines(transaction);
    }

    //Replaces the header fields and both charge-line lists. Must run inside a transaction. 
    public void update(Transaction transaction) {
        db.sql("""
                UPDATE transactions
                   SET occurred_at = :occurredAt,
                       total       = :total,
                       metadata    = :metadata,
                       updated_at  = :updatedAt
                 WHERE id = :id
                """)
                .param("id", transaction.id())
                .param("occurredAt", SqlTime.toDatabase(transaction.occurredAt()))
                .param("total", Amounts.money(transaction.total()))
                .param("metadata", transaction.metadata())
                .param("updatedAt", SqlTime.toDatabase(transaction.updatedAt()))
                .update();
        deleteChargeLines(transaction.id());
        insertChargeLines(transaction);
    }

    /** @return true if a row was deleted; charge lines go with it by {@code ON DELETE CASCADE} */
    public boolean deleteById(String id) {
        return db.sql("DELETE FROM transactions WHERE id = :id").param("id", id).update() > 0;
    }

    public Optional<Transaction> findById(String id) {
        return findHeader("WHERE id = :id", Map.of("id", id)).map(this::withChargeLines);
    }

    public Optional<Transaction> findByExternalId(String externalId) {
        return findHeader("WHERE external_id = :externalId", Map.of("externalId", externalId))
                .map(this::withChargeLines);
    }

    /**
     * Reads the row and holds a write lock on it until the surrounding transaction ends, so a
     * concurrent PATCH, DELETE or invoice build of the same transaction waits instead of
     * interleaving.
     */
    public Optional<Transaction> lockById(String id) {
        return findHeader("WHERE id = :id FOR UPDATE", Map.of("id", id)).map(this::withChargeLines);
    }

    /**
     * One bounded page, newest first. Reads {@code limit + 1} rows to tell the client whether more
     * exist without paying for a {@code COUNT(*)}, then loads the charge lines for the whole page
     * in two further queries rather than two per row.
     */
    public Page<Transaction> findPage(int limit, int offset) {
        List<Transaction> headers = db.sql(SELECT + """
                 ORDER BY created_at DESC, id DESC
                 LIMIT :limit OFFSET :offset
                """)
                .param("limit", limit + 1)
                .param("offset", offset)
                .query(TransactionRepository::mapHeader)
                .list();

        boolean hasMore = headers.size() > limit;
        List<Transaction> page = hasMore ? headers.subList(0, limit) : headers;
        return new Page<>(withChargeLines(page), hasMore);
    }

    private Optional<Transaction> findHeader(String where, Map<String, Object> params) {
        JdbcClient.StatementSpec statement = db.sql(SELECT + where);
        for (Map.Entry<String, Object> param : params.entrySet()) {
            statement = statement.param(param.getKey(), param.getValue());
        }
        return statement.query(TransactionRepository::mapHeader).optional();
    }

    private Transaction withChargeLines(Transaction header) {
        return withChargeLines(List.of(header)).getFirst();
    }

    private List<Transaction> withChargeLines(List<Transaction> headers) {
        if (headers.isEmpty()) {
            return List.of();
        }
        List<String> ids = headers.stream().map(Transaction::id).toList();
        Map<String, List<TaxLine>> taxLines = taxLinesByTransaction(ids);
        Map<String, List<FeeLine>> feeLines = feeLinesByTransaction(ids);
        return headers.stream()
                .map(header -> new Transaction(
                        header.id(), header.externalId(), header.type(), header.currency(),
                        header.occurredAt(), header.total(), header.metadata(),
                        taxLines.getOrDefault(header.id(), List.of()),
                        feeLines.getOrDefault(header.id(), List.of()),
                        header.createdAt(), header.updatedAt()))
                .toList();
    }

    private Map<String, List<TaxLine>> taxLinesByTransaction(Collection<String> transactionIds) {
        Map<String, List<TaxLine>> byTransaction = new HashMap<>();
        db.sql("""
                SELECT transaction_id, name, rate, amount
                  FROM transaction_tax_lines
                 WHERE transaction_id IN (:ids)
                 ORDER BY transaction_id, line_no
                """)
                .param("ids", transactionIds)
                .query((ResultSet row, int index) -> Map.entry(
                        row.getString("transaction_id"),
                        new TaxLine(row.getString("name"),
                                row.getBigDecimal("rate"),
                                row.getBigDecimal("amount"))))
                .list()
                .forEach(entry -> byTransaction
                        .computeIfAbsent(entry.getKey(), key -> new ArrayList<>())
                        .add(entry.getValue()));
        return byTransaction;
    }

    private Map<String, List<FeeLine>> feeLinesByTransaction(Collection<String> transactionIds) {
        Map<String, List<FeeLine>> byTransaction = new HashMap<>();
        db.sql("""
                SELECT transaction_id, name, amount
                  FROM transaction_fee_lines
                 WHERE transaction_id IN (:ids)
                 ORDER BY transaction_id, line_no
                """)
                .param("ids", transactionIds)
                .query((ResultSet row, int index) -> Map.entry(
                        row.getString("transaction_id"),
                        new FeeLine(row.getString("name"), row.getBigDecimal("amount"))))
                .list()
                .forEach(entry -> byTransaction
                        .computeIfAbsent(entry.getKey(), key -> new ArrayList<>())
                        .add(entry.getValue()));
        return byTransaction;
    }

    private void insertChargeLines(Transaction transaction) {
        // Charge lines per transaction are single digits, so a loop inside the transaction is both
        // clearer and no slower in practice than a JDBC batch.
        List<TaxLine> taxLines = transaction.taxLines();
        for (int lineNo = 0; lineNo < taxLines.size(); lineNo++) {
            TaxLine line = taxLines.get(lineNo);
            db.sql("""
                    INSERT INTO transaction_tax_lines (transaction_id, line_no, name, rate, amount)
                    VALUES (:transactionId, :lineNo, :name, :rate, :amount)
                    """)
                    .param("transactionId", transaction.id())
                    .param("lineNo", lineNo)
                    .param("name", line.name())
                    .param("rate", line.rate())
                    .param("amount", Amounts.money(line.amount()))
                    .update();
        }
        List<FeeLine> feeLines = transaction.feeLines();
        for (int lineNo = 0; lineNo < feeLines.size(); lineNo++) {
            FeeLine line = feeLines.get(lineNo);
            db.sql("""
                    INSERT INTO transaction_fee_lines (transaction_id, line_no, name, amount)
                    VALUES (:transactionId, :lineNo, :name, :amount)
                    """)
                    .param("transactionId", transaction.id())
                    .param("lineNo", lineNo)
                    .param("name", line.name())
                    .param("amount", Amounts.money(line.amount()))
                    .update();
        }
    }

    private void deleteChargeLines(String transactionId) {
        db.sql("DELETE FROM transaction_tax_lines WHERE transaction_id = :id").param("id", transactionId).update();
        db.sql("DELETE FROM transaction_fee_lines WHERE transaction_id = :id").param("id", transactionId).update();
    }

    private static Transaction mapHeader(ResultSet row, int index) throws SQLException {
        return new Transaction(
                row.getString("id"),
                row.getString("external_id"),
                TransactionType.fromWire(row.getString("type")),
                row.getString("currency"),
                SqlTime.fromDatabase(row, "occurred_at"),
                row.getBigDecimal("total"),
                row.getString("metadata"),
                List.of(),
                List.of(),
                SqlTime.fromDatabase(row, "created_at"),
                SqlTime.fromDatabase(row, "updated_at"));
    }
}
