package com.example.billing.invoice;

import com.example.billing.common.Amounts;
import com.example.billing.common.Page;
import com.example.billing.common.SqlTime;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class InvoiceRepository {

    private static final String COLUMNS =
            "id, currency, total, tax_total, fee_total, line_count, created_at, updated_at";

    private static final String SELECT = "SELECT " + COLUMNS + " FROM invoices ";

    private final JdbcClient db;

    public InvoiceRepository(JdbcClient db) {
        this.db = db;
    }

    public void insert(Invoice invoice) {
        db.sql("""
                INSERT INTO invoices (id, currency, total, tax_total, fee_total, line_count,
                                      created_at, updated_at)
                VALUES (:id, :currency, :total, :taxTotal, :feeTotal, :lineCount,
                        :createdAt, :updatedAt)
                """)
                .param("id", invoice.id())
                .param("currency", invoice.currency())
                .param("total", Amounts.money(invoice.total()))
                .param("taxTotal", Amounts.money(invoice.taxTotal()))
                .param("feeTotal", Amounts.money(invoice.feeTotal()))
                .param("lineCount", invoice.lineCount())
                .param("createdAt", SqlTime.toDatabase(invoice.createdAt()))
                .param("updatedAt", SqlTime.toDatabase(invoice.updatedAt()))
                .update();
    }

    public void update(Invoice invoice) {
        db.sql("""
                UPDATE invoices
                   SET currency   = :currency,
                       total      = :total,
                       tax_total  = :taxTotal,
                       fee_total  = :feeTotal,
                       line_count = :lineCount,
                       updated_at = :updatedAt
                 WHERE id = :id
                """)
                .param("id", invoice.id())
                .param("currency", invoice.currency())
                .param("total", Amounts.money(invoice.total()))
                .param("taxTotal", Amounts.money(invoice.taxTotal()))
                .param("feeTotal", Amounts.money(invoice.feeTotal()))
                .param("lineCount", invoice.lineCount())
                .param("updatedAt", SqlTime.toDatabase(invoice.updatedAt()))
                .update();
    }

    /** Deletes the invoice and, by cascade, its snapshot. The transactions are untouched. */
    public boolean deleteById(String id) {
        return db.sql("DELETE FROM invoices WHERE id = :id").param("id", id).update() > 0;
    }

    public Optional<Invoice> findById(String id) {
        return db.sql(SELECT + "WHERE id = :id").param("id", id)
                .query(InvoiceRepository::map).optional();
    }

    public Optional<Invoice> lockById(String id) {
        return db.sql(SELECT + "WHERE id = :id FOR UPDATE").param("id", id)
                .query(InvoiceRepository::map).optional();
    }

    public Page<Invoice> findPage(int limit, int offset) {
        List<Invoice> rows = db.sql(SELECT + """
                 ORDER BY created_at DESC, id DESC
                 LIMIT :limit OFFSET :offset
                """)
                .param("limit", limit + 1)
                .param("offset", offset)
                .query(InvoiceRepository::map)
                .list();
        boolean hasMore = rows.size() > limit;
        return new Page<>(hasMore ? rows.subList(0, limit) : rows, hasMore);
    }

    private static Invoice map(ResultSet row, int index) throws SQLException {
        return new Invoice(
                row.getString("id"),
                row.getString("currency"),
                row.getBigDecimal("total"),
                row.getBigDecimal("tax_total"),
                row.getBigDecimal("fee_total"),
                row.getInt("line_count"),
                SqlTime.fromDatabase(row, "created_at"),
                SqlTime.fromDatabase(row, "updated_at"));
    }
}
