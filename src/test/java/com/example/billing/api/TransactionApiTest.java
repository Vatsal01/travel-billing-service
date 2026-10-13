package com.example.billing.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.billing.support.ApiTestBase;
import com.example.billing.support.HttpApi;
import com.example.billing.transaction.metadata.MetadataCodec;
import com.example.billing.web.dto.CreateTransactionRequest;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** The create/read/update/delete contract for transactions, driven over HTTP. */
class TransactionApiTest extends ApiTestBase {

    @Test
    @DisplayName("a flight is stored with its coupons, origin/destination and flight number")
    void createsFlightWithCoupons() {
        HttpApi.Response created = api.post("/transactions", seed("flight"));

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.at("/type").asString()).isEqualTo("flight");

        HttpApi.Response fetched = api.get("/transactions/" + created.id());
        assertThat(fetched.status()).isEqualTo(200);
        assertThat(fetched.at("/metadata/origin").asString()).isEqualTo("TXL");
        assertThat(fetched.at("/metadata/destination").asString()).isEqualTo("LHR");
        assertThat(fetched.at("/metadata/coupons").size()).isEqualTo(1);
        assertThat(fetched.at("/metadata/coupons/0/flight_number").asString()).isEqualTo("BA982");
        assertThat(fetched.at("/metadata/coupons/0/carrier").asString()).isEqualTo("BA");
        assertThat(fetched.at("/tax_lines/0/name").asString()).isEqualTo("Airport tax");
        assertThat(fetched.at("/tax_lines/0/amount").decimalValue()).isEqualByComparingTo("15.00");
        assertThat(fetched.at("/fee_lines/0/name").asString()).isEqualTo("YQ");
        assertThat(fetched.at("/fee_lines/0/amount").decimalValue()).isEqualByComparingTo("25.00");
    }

    @Test
    @DisplayName("a hotel is stored with the property name and the full address")
    void createsHotelWithNameAndAddress() {
        String id = createTransaction("hotel");

        HttpApi.Response fetched = api.get("/transactions/" + id);
        assertThat(fetched.at("/metadata/name").asString()).isEqualTo("Hotel Mitte London");
        assertThat(fetched.at("/metadata/address/street").asString()).isEqualTo("12 Kingsway");
        assertThat(fetched.at("/metadata/address/city").asString()).isEqualTo("London");
        assertThat(fetched.at("/metadata/address/country").asString()).isEqualTo("GB");
        assertThat(fetched.at("/metadata/nights").asInt()).isEqualTo(2);
        assertThat(fetched.at("/metadata/extras/0/name").asString()).isEqualTo("Breakfast");
        assertThat(fetched.at("/tax_lines/0/rate").decimalValue()).isEqualByComparingTo("0.07");
        assertThat(fetched.at("/fee_lines")).isEmpty();
    }

    @Test
    @DisplayName("a rail journey with a change of train keeps both legs")
    void createsRailWithTwoLegs() {
        String id = createTransaction("rail");

        HttpApi.Response fetched = api.get("/transactions/" + id);
        assertThat(fetched.at("/metadata/legs").size()).isEqualTo(2);
        assertThat(fetched.at("/metadata/legs/0/origin").asString()).isEqualTo("London St Pancras");
        assertThat(fetched.at("/metadata/legs/0/destination").asString()).isEqualTo("Lille Europe");
        assertThat(fetched.at("/metadata/legs/0/train_number").asString()).isEqualTo("EST9108");
        assertThat(fetched.at("/metadata/legs/1/origin").asString()).isEqualTo("Lille Europe");
        assertThat(fetched.at("/metadata/legs/1/destination").asString()).isEqualTo("Paris Nord");
        // "class" is a Java keyword; the wire name has to survive it.
        assertThat(fetched.at("/metadata/legs/1/class").asString()).isEqualTo("standard");
    }

    @Test
    @DisplayName("both fee kinds are stored as first-class transactions with their fee_kind")
    void createsBothFeeKinds() {
        String tripFeeId = createTransaction("trip_fee");
        String agentCallFeeId = createTransaction("agent_call_fee");

        HttpApi.Response tripFee = api.get("/transactions/" + tripFeeId);
        assertThat(tripFee.at("/type").asString()).isEqualTo("navan_fee");
        assertThat(tripFee.at("/metadata/fee_kind").asString()).isEqualTo("trip_fee");
        assertThat(tripFee.at("/metadata/related_external_id").asString())
                .isEqualTo("flight-TXL-LHR-2026-03-11");
        assertThat(tripFee.at("/total").decimalValue()).isEqualByComparingTo("25.00");

        HttpApi.Response agentCallFee = api.get("/transactions/" + agentCallFeeId);
        assertThat(agentCallFee.at("/metadata/fee_kind").asString()).isEqualTo("agent_call_fee");
        assertThat(agentCallFee.at("/total").decimalValue()).isEqualByComparingTo("15.00");
    }

    @Test
    @DisplayName("metadata fields the API does not model are stored and returned, not dropped")
    void keepsUnmodelledMetadataFields() {
        ObjectNode flight = seed("flight");
        ((ObjectNode) flight.get("metadata")).put("fare_basis", "YFLEX");

        String id = api.post("/transactions", flight).id();

        assertThat(api.get("/transactions/" + id).at("/metadata/fare_basis").asString()).isEqualTo("YFLEX");
    }

    @Test
    @DisplayName("a second POST of the same external_id is a 409 naming the existing id, and stores no second row")
    void rejectsDuplicateExternalId() {
        HttpApi.Response first = api.post("/transactions", seed("flight"));
        assertThat(first.status()).isEqualTo(201);

        // The published duplicate payload, which also carries an empty coupon list.
        HttpApi.Response second = api.post("/transactions", seed("duplicate_flight"));

        assertThat(second.status()).isEqualTo(409);
        assertThat(second.code()).isEqualTo("DUPLICATE_EXTERNAL_ID");
        assertThat(second.at("/existing_id").asString()).isEqualTo(first.id());
        assertThat(countTransactionsWithExternalId("flight-TXL-LHR-2026-03-11")).isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown type is rejected and nothing is stored")
    void rejectsUnknownType() {
        ObjectNode payload = seed("flight").put("type", "spaceship").put("external_id", "unknown-type-1");

        HttpApi.Response response = api.post("/transactions", payload);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.at("/detail").asString()).contains("spaceship", "flight", "navan_fee");
        assertThat(countTransactionsWithExternalId("unknown-type-1")).isZero();
    }

    @Test
    @DisplayName("an unknown fee_kind is rejected rather than stored as an empty fee")
    void rejectsUnknownFeeKind() {
        ObjectNode payload = seedWithExternalId("trip_fee", "unknown-kind-1");
        ((ObjectNode) payload.get("metadata")).put("fee_kind", "parking_fee");

        HttpApi.Response response = api.post("/transactions", payload);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.at("/detail").asString()).contains("parking_fee", "trip_fee", "agent_call_fee");
        assertThat(countTransactionsWithExternalId("unknown-kind-1")).isZero();
    }

    @Test
    @DisplayName("metadata that does not match its type is rejected, with the offending field named")
    void rejectsMetadataThatDoesNotMatchItsType() {
        ObjectNode railWithoutLegs = seedWithExternalId("rail", "rail-no-legs-1");
        railWithoutLegs.putObject("metadata");

        HttpApi.Response response = api.post("/transactions", railWithoutLegs);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.at("/errors/0").asString()).contains("legs");
        assertThat(countTransactionsWithExternalId("rail-no-legs-1")).isZero();
    }

    @Test
    @DisplayName("a hotel stay that ends before it starts is rejected")
    void rejectsHotelStayInTheWrongOrder() {
        ObjectNode hotel = seedWithExternalId("hotel", "hotel-bad-dates-1");
        ((ObjectNode) hotel.get("metadata")).put("check_out", "2026-03-10");

        HttpApi.Response response = api.post("/transactions", hotel);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.at("/errors/0").asString()).contains("check_out must be after check_in");
    }

    @Test
    @DisplayName("an amount with sub-cent precision is rejected rather than silently rounded")
    void rejectsAmountsBelowTheStoredScale() {
        ObjectNode flight = seedWithExternalId("flight", "flight-precision-1")
                .put("total", new BigDecimal("220.005"));

        HttpApi.Response response = api.post("/transactions", flight);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.at("/errors/0").asString()).contains("total");
    }

    @Test
    @DisplayName("more charge lines than the bound is a 400, and nothing is stored")
    void rejectsTooManyChargeLines() {
        ObjectNode flight = seedWithExternalId("flight", "too-many-tax-lines");
        ArrayNode taxLines = objectMapper.createArrayNode();
        for (int line = 0; line <= CreateTransactionRequest.MAX_CHARGE_LINES; line++) {
            taxLines.add(objectMapper.createObjectNode().put("name", "T" + line).put("amount", 0.01));
        }
        flight.set("tax_lines", taxLines);

        HttpApi.Response response = api.post("/transactions", flight);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(countTransactionsWithExternalId("too-many-tax-lines")).isZero();
    }

    @Test
    @DisplayName("a metadata document above the size bound is a 400, and nothing is stored")
    void rejectsAnOversizedMetadataDocument() {
        ObjectNode flight = seedWithExternalId("flight", "oversized-metadata");
        ((ObjectNode) flight.get("metadata"))
                .put("fare_basis", "X".repeat(MetadataCodec.MAX_METADATA_CHARS));

        HttpApi.Response response = api.post("/transactions", flight);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(countTransactionsWithExternalId("oversized-metadata")).isZero();
    }

    @Test
    @DisplayName("a missing transaction is a 404")
    void missingTransactionIsNotFound() {
        HttpApi.Response response = api.get("/transactions/txn_does_not_exist");

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.code()).isEqualTo("TRANSACTION_NOT_FOUND");
    }

    @Test
    @DisplayName("a patch replaces the fields it carries, including whole charge-line lists")
    void patchesStoredFields() {
        String id = createTransaction("flight");

        HttpApi.Response patched = api.patch("/transactions/" + id, """
                {
                  "total": 240.00,
                  "tax_lines": [{"name": "Airport tax", "amount": 17.50},
                                {"name": "Security charge", "amount": 2.50}],
                  "fee_lines": []
                }
                """);

        assertThat(patched.status()).isEqualTo(200);
        assertThat(patched.at("/total").decimalValue()).isEqualByComparingTo("240.00");
        assertThat(patched.at("/tax_lines").size()).isEqualTo(2);
        assertThat(patched.at("/fee_lines")).isEmpty();
        // Untouched fields keep their stored values.
        assertThat(patched.at("/metadata/coupons/0/flight_number").asString()).isEqualTo("BA982");

        HttpApi.Response reread = api.get("/transactions/" + id);
        assertThat(reread.at("/tax_lines/1/name").asString()).isEqualTo("Security charge");
        assertThat(reread.at("/fee_lines")).isEmpty();
    }

    @Test
    @DisplayName("a patch cannot change identity or currency")
    void refusesToPatchImmutableFields() {
        String id = createTransaction("flight");

        assertThat(api.patch("/transactions/" + id, "{\"currency\": \"USD\"}").status()).isEqualTo(400);
        assertThat(api.patch("/transactions/" + id, "{\"external_id\": \"other\"}").code())
                .isEqualTo("IMMUTABLE_FIELD");
        assertThat(api.get("/transactions/" + id).at("/currency").asString()).isEqualTo("EUR");
    }

    @Test
    @DisplayName("a patch with no updatable field is a 400, not a silent no-op")
    void refusesEmptyPatch() {
        String id = createTransaction("flight");

        HttpApi.Response response = api.patch("/transactions/" + id, "{}");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("NO_UPDATABLE_FIELDS");
    }

    @Test
    @DisplayName("patching a missing transaction is a 404")
    void patchingMissingTransactionIsNotFound() {
        assertThat(api.patch("/transactions/txn_missing", "{\"total\": 1.00}").status()).isEqualTo(404);
    }

    @Test
    @DisplayName("deleting a transaction removes its charge lines with it")
    void deletesTransactionAndItsChargeLines() {
        String id = createTransaction("flight");

        assertThat(api.delete("/transactions/" + id).status()).isEqualTo(204);

        assertThat(api.get("/transactions/" + id).status()).isEqualTo(404);
        assertThat(countChargeLines(id)).isZero();
    }

    @Test
    @DisplayName("deleting a missing transaction is a 404")
    void deletingMissingTransactionIsNotFound() {
        assertThat(api.delete("/transactions/txn_missing").status()).isEqualTo(404);
    }

    @Test
    @DisplayName("the list is always bounded: default 50, 100 at most, and it pages")
    void listIsAlwaysBounded() {
        createTransaction("flight");
        createTransaction("hotel");
        createTransaction("rail");

        HttpApi.Response defaults = api.get("/transactions");
        assertThat(defaults.status()).isEqualTo(200);
        assertThat(defaults.at("/limit").asInt()).isEqualTo(50);
        assertThat(defaults.at("/offset").asInt()).isZero();
        assertThat(defaults.at("/has_more").asBoolean()).isFalse();
        assertThat(defaults.at("/items").size()).isEqualTo(3);

        HttpApi.Response tooLarge = api.get("/transactions?limit=1000");
        assertThat(tooLarge.status()).isEqualTo(400);
        assertThat(tooLarge.at("/errors/0").asString()).contains("limit");

        HttpApi.Response firstPage = api.get("/transactions?limit=2&offset=0");
        assertThat(firstPage.at("/items").size()).isEqualTo(2);
        assertThat(firstPage.at("/has_more").asBoolean()).isTrue();

        HttpApi.Response secondPage = api.get("/transactions?limit=2&offset=2");
        assertThat(secondPage.at("/items").size()).isEqualTo(1);
        assertThat(secondPage.at("/has_more").asBoolean()).isFalse();

        assertThat(api.get("/transactions?limit=0").status()).isEqualTo(400);
        assertThat(api.get("/transactions?offset=-1").status()).isEqualTo(400);
    }

    @Test
    @DisplayName("health reports the database it depends on")
    void healthChecksTheDatabase() {
        HttpApi.Response response = api.get("/health");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.at("/database").asString()).isEqualTo("UP");
    }

    private long countChargeLines(String transactionId) {
        return db.sql("""
                SELECT (SELECT COUNT(*) FROM transaction_tax_lines WHERE transaction_id = :id)
                     + (SELECT COUNT(*) FROM transaction_fee_lines WHERE transaction_id = :id)
                """)
                .param("id", transactionId)
                .query(Long.class)
                .single();
    }
}
