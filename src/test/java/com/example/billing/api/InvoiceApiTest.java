package com.example.billing.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.billing.support.ApiTestBase;
import com.example.billing.support.HttpApi;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** Invoicing: the snapshot, the rollups, the currency rule, and what a billed transaction forbids. */
class InvoiceApiTest extends ApiTestBase {

    @Test
    @DisplayName("a trip invoice bills flight, hotel, rail and both fee kinds, and the totals match the published figures")
    void invoicesAWholeTrip() {
        Map<String, String> ids = createTrip();

        HttpApi.Response invoice = api.post("/invoices",
                Map.of("transaction_ids", List.of(ids.get("flight"), ids.get("hotel"), ids.get("rail"),
                        ids.get("trip_fee"), ids.get("agent_call_fee"))));

        assertThat(invoice.status()).isEqualTo(201);
        assertThat(invoice.at("/currency").asString()).isEqualTo("EUR");
        assertThat(invoice.at("/total").decimalValue())
                .isEqualByComparingTo(expected("/totals/grand_total").decimalValue());
        assertThat(invoice.at("/line_count").asInt())
                .isGreaterThanOrEqualTo(expected("/line_count_minimum").asInt());

        assertThat(typesOf(invoice)).containsExactly("flight", "hotel", "rail", "navan_fee", "navan_fee");
        assertThat(contributionOf(invoice, "flight"))
                .isEqualByComparingTo(expected("/totals/flight").decimalValue());
        assertThat(contributionOf(invoice, "hotel"))
                .isEqualByComparingTo(expected("/totals/hotel").decimalValue());
        assertThat(contributionOf(invoice, "rail"))
                .isEqualByComparingTo(expected("/totals/rail").decimalValue());

        // Type-specific detail survives onto the invoice, not just onto the transaction.
        assertThat(invoice.at("/transactions/0/metadata/coupons/0/flight_number").asString()).isEqualTo("BA982");
        assertThat(invoice.at("/transactions/1/metadata/address/city").asString()).isEqualTo("London");
        assertThat(invoice.at("/transactions/2/metadata/legs").size()).isEqualTo(2);
        assertThat(invoice.at("/transactions/3/metadata/fee_kind").asString()).isEqualTo("trip_fee");
        assertThat(invoice.at("/transactions/4/metadata/fee_kind").asString()).isEqualTo("agent_call_fee");

        // Taxes and fees roll up separately, grouped by name (and rate, for a tax).
        assertThat(namesIn(invoice, "tax_rollup")).containsExactly("Airport tax", "VAT");
        assertThat(invoice.at("/tax_rollup/0/amount").decimalValue()).isEqualByComparingTo("15.00");
        assertThat(invoice.at("/tax_rollup/1/amount").decimalValue()).isEqualByComparingTo("21.70");
        assertThat(invoice.at("/tax_rollup/1/rate").decimalValue()).isEqualByComparingTo("0.07");
        assertThat(namesIn(invoice, "fee_rollup")).containsExactly("Booking fee", "YQ");
        assertThat(invoice.at("/fee_rollup/0/amount").decimalValue()).isEqualByComparingTo("4.00");
        assertThat(invoice.at("/fee_rollup/1/amount").decimalValue()).isEqualByComparingTo("25.00");
        assertThat(invoice.at("/tax_total").decimalValue()).isEqualByComparingTo("36.70");
        assertThat(invoice.at("/fee_total").decimalValue()).isEqualByComparingTo("29.00");

        // The same invoice reads back identically.
        HttpApi.Response reread = api.get("/invoices/" + invoice.id());
        assertThat(reread.at("/total").decimalValue()).isEqualByComparingTo("685.70");
        assertThat(reread.at("/transactions").size()).isEqualTo(5);
    }

    @Test
    @DisplayName("a single transaction_id is accepted as well as a list")
    void invoicesASingleTransaction() {
        String flightId = createTransaction("flight");

        HttpApi.Response invoice = api.post("/invoices", Map.of("transaction_id", flightId));

        assertThat(invoice.status()).isEqualTo(201);
        assertThat(invoice.at("/transactions").size()).isEqualTo(1);
        assertThat(invoice.at("/total").decimalValue()).isEqualByComparingTo("220.00");
    }

    @Test
    @DisplayName("the snapshot is frozen: changing the stored flight underneath does not change the invoice")
    void snapshotIsFrozenAgainstLaterChanges() {
        String flightId = createTransaction("flight");
        String invoiceId = api.post("/invoices", Map.of("transaction_id", flightId)).id();

        // The API refuses to rewrite a billed transaction (see refusesToChangeABilledTransaction),
        // so the row is changed directly in the database - which is also the stronger test: it
        // proves the invoice is a copy rather than a live join, whatever else touches the table.
        db.sql("UPDATE transactions SET total = 999.99 WHERE id = :id").param("id", flightId).update();
        db.sql("UPDATE transaction_tax_lines SET amount = 500.00 WHERE transaction_id = :id")
                .param("id", flightId).update();

        HttpApi.Response invoice = api.get("/invoices/" + invoiceId);
        assertThat(contributionOf(invoice, "flight")).isEqualByComparingTo("220.00");
        assertThat(invoice.at("/total").decimalValue()).isEqualByComparingTo("220.00");
        assertThat(invoice.at("/tax_rollup/0/amount").decimalValue()).isEqualByComparingTo("15.00");

        // The transaction itself does show the new value: the two are genuinely independent.
        assertThat(api.get("/transactions/" + flightId).at("/total").decimalValue())
                .isEqualByComparingTo("999.99");
    }

    @Test
    @DisplayName("an invoice mixing currencies is refused")
    void refusesMixedCurrencies() {
        String euroFlightId = createTransaction("flight");
        ObjectNode dollarHotel = seedWithExternalId("hotel", "hotel-NYC-2026-03-11").put("currency", "USD");
        String dollarHotelId = api.post("/transactions", dollarHotel).id();

        HttpApi.Response response = api.post("/invoices",
                Map.of("transaction_ids", List.of(euroFlightId, dollarHotelId)));

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("MIXED_CURRENCY");
        assertThat(api.get("/invoices").at("/items")).isEmpty();
    }

    @Test
    @DisplayName("the same transaction twice on one invoice is refused rather than double-counted")
    void refusesToBillTheSameTransactionTwice() {
        String flightId = createTransaction("flight");

        HttpApi.Response response = api.post("/invoices",
                Map.of("transaction_ids", List.of(flightId, flightId)));

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("DUPLICATE_TRANSACTION_ON_INVOICE");
    }

    @Test
    @DisplayName("an invoice for an unknown transaction is a 404 and writes nothing")
    void refusesUnknownTransaction() {
        String flightId = createTransaction("flight");

        HttpApi.Response response = api.post("/invoices",
                Map.of("transaction_ids", List.of(flightId, "txn_missing")));

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.code()).isEqualTo("TRANSACTION_NOT_FOUND");
        assertThat(api.get("/invoices").at("/items")).isEmpty();
    }

    @Test
    @DisplayName("a body with neither or both id shapes is rejected")
    void refusesAmbiguousRequestShapes() {
        String flightId = createTransaction("flight");

        assertThat(api.post("/invoices", Map.of()).status()).isEqualTo(400);
        assertThat(api.post("/invoices", "{\"transaction_ids\": []}").status()).isEqualTo(400);
        assertThat(api.post("/invoices",
                Map.of("transaction_id", flightId, "transaction_ids", List.of(flightId))).status())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("a billed transaction cannot be patched, and the invoice keeps its totals")
    void refusesToChangeABilledTransaction() {
        String flightId = createTransaction("flight");
        String invoiceId = api.post("/invoices", Map.of("transaction_id", flightId)).id();

        HttpApi.Response response = api.patch("/transactions/" + flightId, "{\"total\": 999.00}");

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("TRANSACTION_ALREADY_INVOICED");
        assertThat(response.at("/invoice_ids/0").asString()).isEqualTo(invoiceId);
        // Nothing was written: neither the invoice nor the transaction moved.
        assertThat(api.get("/invoices/" + invoiceId).at("/total").decimalValue())
                .isEqualByComparingTo("220.00");
        assertThat(api.get("/transactions/" + flightId).at("/total").decimalValue())
                .isEqualByComparingTo("220.00");
    }

    @Test
    @DisplayName("a billed transaction cannot be deleted, and the row survives")
    void refusesToDeleteABilledTransaction() {
        String flightId = createTransaction("flight");
        api.post("/invoices", Map.of("transaction_id", flightId));

        HttpApi.Response response = api.delete("/transactions/" + flightId);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("TRANSACTION_ALREADY_INVOICED");
        assertThat(api.get("/transactions/" + flightId).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("patching an invoice rebuilds the snapshot from the transactions as they are now")
    void patchRebuildsTheSnapshot() {
        Map<String, String> ids = createTrip();
        String invoiceId = api.post("/invoices", Map.of("transaction_id", ids.get("flight"))).id();

        HttpApi.Response rebuilt = api.patch("/invoices/" + invoiceId,
                Map.of("transaction_ids", List.of(ids.get("hotel"), ids.get("rail"))));

        assertThat(rebuilt.status()).isEqualTo(200);
        assertThat(typesOf(rebuilt)).containsExactly("hotel", "rail");
        assertThat(rebuilt.at("/total").decimalValue()).isEqualByComparingTo("425.70");
        assertThat(rebuilt.at("/line_count").asInt()).isEqualTo(2);
        assertThat(rebuilt.at("/id").asString()).isEqualTo(invoiceId);

        // The flight is no longer billed, so it becomes editable again.
        assertThat(api.patch("/transactions/" + ids.get("flight"), "{\"total\": 221.00}").status())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("a rejected invoice patch leaves the invoice exactly as it was")
    void failedPatchLeavesInvoiceUnchanged() {
        String flightId = createTransaction("flight");
        ObjectNode dollarHotel = seedWithExternalId("hotel", "hotel-NYC-2026-03-11").put("currency", "USD");
        String dollarHotelId = api.post("/transactions", dollarHotel).id();
        String invoiceId = api.post("/invoices", Map.of("transaction_id", flightId)).id();

        HttpApi.Response rejected = api.patch("/invoices/" + invoiceId,
                Map.of("transaction_ids", List.of(flightId, dollarHotelId)));

        assertThat(rejected.status()).isEqualTo(409);
        HttpApi.Response unchanged = api.get("/invoices/" + invoiceId);
        assertThat(unchanged.at("/total").decimalValue()).isEqualByComparingTo("220.00");
        assertThat(typesOf(unchanged)).containsExactly("flight");
    }

    @Test
    @DisplayName("patching a missing invoice is a 404")
    void patchingMissingInvoiceIsNotFound() {
        String flightId = createTransaction("flight");

        assertThat(api.patch("/invoices/inv_missing", Map.of("transaction_id", flightId)).status())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("deleting an invoice removes it and leaves the transactions alone")
    void deletingAnInvoiceKeepsTheTransactions() {
        Map<String, String> ids = createTrip();
        String invoiceId = api.post("/invoices",
                Map.of("transaction_ids", List.of(ids.get("flight"), ids.get("hotel")))).id();

        assertThat(api.delete("/invoices/" + invoiceId).status()).isEqualTo(204);

        assertThat(api.get("/invoices/" + invoiceId).status()).isEqualTo(404);
        assertThat(api.get("/transactions/" + ids.get("flight")).status()).isEqualTo(200);
        assertThat(api.get("/transactions/" + ids.get("hotel")).status()).isEqualTo(200);
        assertThat(countInvoiceLines()).isZero();
        // With the invoice gone the transactions are editable again.
        assertThat(api.patch("/transactions/" + ids.get("flight"), "{\"total\": 221.00}").status())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("deleting a missing invoice is a 404")
    void deletingMissingInvoiceIsNotFound() {
        assertThat(api.delete("/invoices/inv_missing").status()).isEqualTo(404);
    }

    @Test
    @DisplayName("the invoice list is bounded the same way as transactions")
    void invoiceListIsAlsoBounded() {
        Map<String, String> ids = createTrip();
        api.post("/invoices", Map.of("transaction_id", ids.get("flight")));
        api.post("/invoices", Map.of("transaction_id", ids.get("hotel")));

        HttpApi.Response list = api.get("/invoices");
        assertThat(list.at("/limit").asInt()).isEqualTo(50);
        assertThat(list.at("/items").size()).isEqualTo(2);
        // A list page carries headers only, not every billed line.
        assertThat(list.at("/items/0/line_count").asInt()).isEqualTo(1);
        assertThat(list.at("/items/0/transactions").isMissingNode()).isTrue();

        assertThat(api.get("/invoices?limit=1").at("/has_more").asBoolean()).isTrue();
        assertThat(api.get("/invoices?limit=1000").status()).isEqualTo(400);
    }

    private Map<String, String> createTrip() {
        return Map.of(
                "flight", createTransaction("flight"),
                "hotel", createTransaction("hotel"),
                "rail", createTransaction("rail"),
                "trip_fee", createTransaction("trip_fee"),
                "agent_call_fee", createTransaction("agent_call_fee"));
    }

    private static List<String> typesOf(HttpApi.Response invoice) {
        return invoice.at("/transactions").valueStream().map(line -> line.path("type").asString()).toList();
    }

    /** What one transaction type contributed to the invoice, read off the snapshot. */
    private static BigDecimal contributionOf(HttpApi.Response invoice, String type) {
        return invoice.at("/transactions").valueStream()
                .filter(line -> type.equals(line.path("type").asString()))
                .map(line -> line.path("total").decimalValue())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static List<String> namesIn(HttpApi.Response invoice, String field) {
        return invoice.at("/" + field).valueStream().map(entry -> entry.path("name").asString()).toList();
    }

    private long countInvoiceLines() {
        return db.sql("SELECT COUNT(*) FROM invoice_lines").query(Long.class).single();
    }
}
