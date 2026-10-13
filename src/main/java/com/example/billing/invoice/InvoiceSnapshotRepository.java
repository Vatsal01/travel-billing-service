package com.example.billing.invoice;

import com.example.billing.common.Amounts;
import com.example.billing.common.SqlTime;
import com.example.billing.transaction.BilledTransactions;
import com.example.billing.transaction.FeeLine;
import com.example.billing.transaction.TaxLine;
import com.example.billing.transaction.TransactionType;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;


@Repository
public class InvoiceSnapshotRepository implements BilledTransactions {

    private final JdbcClient db;

    public InvoiceSnapshotRepository(JdbcClient db) {
        this.db = db;
    }

    // Must run inside the caller's transaction, together with the header write.
    public void insertLines(String invoiceId, List<InvoiceLine> lines) {
        for (int lineNo = 0; lineNo < lines.size(); lineNo++) {
            insertLine(invoiceId, lineNo, lines.get(lineNo));
        }
    }

    public void deleteLines(String invoiceId) {
        
        // Cascades to invoice_line_tax_lines and invoice_line_fee_lines.

        db.sql("DELETE FROM invoice_lines WHERE invoice_id = :invoiceId")
                .param("invoiceId", invoiceId)
                .update();
    }

    public List<InvoiceLine> findLines(String invoiceId) {
        Map<Integer, List<TaxLine>> taxLines = taxLinesByLine(invoiceId);
        Map<Integer, List<FeeLine>> feeLines = feeLinesByLine(invoiceId);
        return db.sql("""
                SELECT line_no, transaction_id, external_id, type, currency, occurred_at, total, metadata
                  FROM invoice_lines
                 WHERE invoice_id = :invoiceId
                 ORDER BY line_no
                """)
                .param("invoiceId", invoiceId)
                .query((ResultSet row, int index) -> {
                    int lineNo = row.getInt("line_no");
                    return new InvoiceLine(
                            row.getString("transaction_id"),
                            row.getString("external_id"),
                            TransactionType.fromWire(row.getString("type")),
                            row.getString("currency"),
                            SqlTime.fromDatabase(row, "occurred_at"),
                            row.getBigDecimal("total"),
                            row.getString("metadata"),
                            taxLines.getOrDefault(lineNo, List.of()),
                            feeLines.getOrDefault(lineNo, List.of()));
                })
                .list();
    }


    public List<TaxLine> taxRollup(String invoiceId) {
        return db.sql("""
                SELECT name, rate, SUM(amount) AS amount
                  FROM invoice_line_tax_lines
                 WHERE invoice_id = :invoiceId
                 GROUP BY name, rate
                 ORDER BY name, rate
                """)
                .param("invoiceId", invoiceId)
                .query((ResultSet row, int index) -> new TaxLine(
                        row.getString("name"),
                        row.getBigDecimal("rate"),
                        row.getBigDecimal("amount")))
                .list();
    }

    // Fee rollup, grouped by name. A fee has no rate, so there is nothing else to group on.
    
    public List<FeeLine> feeRollup(String invoiceId) {
        return db.sql("""
                SELECT name, SUM(amount) AS amount
                  FROM invoice_line_fee_lines
                 WHERE invoice_id = :invoiceId
                 GROUP BY name
                 ORDER BY name
                """)
                .param("invoiceId", invoiceId)
                .query((ResultSet row, int index) ->
                        new FeeLine(row.getString("name"), row.getBigDecimal("amount")))
                .list();
    }

    @Override
    public List<String> invoiceIdsFor(String transactionId) {
        return db.sql("""
                SELECT DISTINCT invoice_id
                  FROM invoice_lines
                 WHERE transaction_id = :transactionId
                 ORDER BY invoice_id
                """)
                .param("transactionId", transactionId)
                .query(String.class)
                .list();
    }

    private void insertLine(String invoiceId, int lineNo, InvoiceLine line) {
        db.sql("""
                INSERT INTO invoice_lines (invoice_id, line_no, transaction_id, external_id, type,
                                           currency, occurred_at, total, metadata)
                VALUES (:invoiceId, :lineNo, :transactionId, :externalId, :type,
                        :currency, :occurredAt, :total, :metadata)
                """)
                .param("invoiceId", invoiceId)
                .param("lineNo", lineNo)
                .param("transactionId", line.transactionId())
                .param("externalId", line.externalId())
                .param("type", line.type().wireValue())
                .param("currency", line.currency())
                .param("occurredAt", SqlTime.toDatabase(line.occurredAt()))
                .param("total", Amounts.money(line.total()))
                .param("metadata", line.metadata())
                .update();

        List<TaxLine> taxLines = line.taxLines();
        for (int chargeNo = 0; chargeNo < taxLines.size(); chargeNo++) {
            TaxLine tax = taxLines.get(chargeNo);
            db.sql("""
                    INSERT INTO invoice_line_tax_lines (invoice_id, line_no, charge_no, name, rate, amount)
                    VALUES (:invoiceId, :lineNo, :chargeNo, :name, :rate, :amount)
                    """)
                    .param("invoiceId", invoiceId)
                    .param("lineNo", lineNo)
                    .param("chargeNo", chargeNo)
                    .param("name", tax.name())
                    .param("rate", tax.rate())
                    .param("amount", Amounts.money(tax.amount()))
                    .update();
        }

        List<FeeLine> feeLines = line.feeLines();
        for (int chargeNo = 0; chargeNo < feeLines.size(); chargeNo++) {
            FeeLine fee = feeLines.get(chargeNo);
            db.sql("""
                    INSERT INTO invoice_line_fee_lines (invoice_id, line_no, charge_no, name, amount)
                    VALUES (:invoiceId, :lineNo, :chargeNo, :name, :amount)
                    """)
                    .param("invoiceId", invoiceId)
                    .param("lineNo", lineNo)
                    .param("chargeNo", chargeNo)
                    .param("name", fee.name())
                    .param("amount", Amounts.money(fee.amount()))
                    .update();
        }
    }

    private Map<Integer, List<TaxLine>> taxLinesByLine(String invoiceId) {
        Map<Integer, List<TaxLine>> byLine = new HashMap<>();
        db.sql("""
                SELECT line_no, name, rate, amount
                  FROM invoice_line_tax_lines
                 WHERE invoice_id = :invoiceId
                 ORDER BY line_no, charge_no
                """)
                .param("invoiceId", invoiceId)
                .query((ResultSet row, int index) -> Map.entry(
                        row.getInt("line_no"),
                        new TaxLine(row.getString("name"),
                                row.getBigDecimal("rate"),
                                row.getBigDecimal("amount"))))
                .list()
                .forEach(entry -> byLine
                        .computeIfAbsent(entry.getKey(), key -> new ArrayList<>())
                        .add(entry.getValue()));
        return byLine;
    }

    private Map<Integer, List<FeeLine>> feeLinesByLine(String invoiceId) {
        Map<Integer, List<FeeLine>> byLine = new HashMap<>();
        db.sql("""
                SELECT line_no, name, amount
                  FROM invoice_line_fee_lines
                 WHERE invoice_id = :invoiceId
                 ORDER BY line_no, charge_no
                """)
                .param("invoiceId", invoiceId)
                .query((ResultSet row, int index) -> Map.entry(
                        row.getInt("line_no"),
                        new FeeLine(row.getString("name"), row.getBigDecimal("amount"))))
                .list()
                .forEach(entry -> byLine
                        .computeIfAbsent(entry.getKey(), key -> new ArrayList<>())
                        .add(entry.getValue()));
        return byLine;
    }
}
