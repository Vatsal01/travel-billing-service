package com.example.billing.support;

import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Base for the API tests: a running server, the assignment fixtures, and an empty database before
 * each test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class ApiTestBase {

    /** Tables in an order that respects the foreign keys: invoices release transactions first. */
    private static final String[] TABLES_IN_DELETE_ORDER = {
            "invoice_line_tax_lines",
            "invoice_line_fee_lines",
            "invoice_lines",
            "invoices",
            "transaction_tax_lines",
            "transaction_fee_lines",
            "transactions"
    };

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcClient db;

    @Value("${local.server.port}")
    private int port;

    protected HttpApi api;

    private JsonNode seeds;
    private JsonNode expectedInvoice;

    @BeforeEach
    void prepare() {
        api = new HttpApi("http://localhost:" + port, objectMapper);
        for (String table : TABLES_IN_DELETE_ORDER) {
            db.sql("DELETE FROM " + table).update();
        }
        seeds = readFixture("fixtures/seed-payloads.json");
        expectedInvoice = readFixture("fixtures/expected-invoice.json");
    }

    /** A mutable copy of one of the supplied seed payloads, e.g. {@code seed("flight")}. */
    protected ObjectNode seed(String name) {
        JsonNode payload = seeds.get(name);
        if (payload == null) {
            throw new IllegalArgumentException("No seed payload named '" + name + "'");
        }
        return (ObjectNode) payload.deepCopy();
    }

    /** A seed payload under a fresh {@code external_id}, for cases that need a second row. */
    protected ObjectNode seedWithExternalId(String name, String externalId) {
        return seed(name).put("external_id", externalId);
    }

    /** The expected amounts published with the assignment, so the tests assert against those. */
    protected JsonNode expected(String jsonPointer) {
        return expectedInvoice.at(jsonPointer);
    }

    protected String createTransaction(String seedName) {
        HttpApi.Response response = api.post("/transactions", seed(seedName));
        if (response.status() != 201) {
            throw new IllegalStateException("Seed '" + seedName + "' was rejected: " + response.body());
        }
        return response.id();
    }

    protected long countTransactionsWithExternalId(String externalId) {
        return db.sql("SELECT COUNT(*) FROM transactions WHERE external_id = :externalId")
                .param("externalId", externalId)
                .query(Long.class)
                .single();
    }

    private JsonNode readFixture(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return objectMapper.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read fixture " + path, e);
        }
    }
}
