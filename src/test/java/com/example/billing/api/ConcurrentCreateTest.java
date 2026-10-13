package com.example.billing.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.billing.support.ApiTestBase;
import com.example.billing.support.HttpApi;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * The duplicate rule under real concurrency.
 *
 * <p>Eight requests carrying the same {@code external_id} are released at the same instant, so they
 * all get past any "does it exist yet?" lookup and race on the insert itself. Exactly one may win;
 * the unique index, not the lookup, is what makes that true.
 */
class ConcurrentCreateTest extends ApiTestBase {

    private static final int CONCURRENT_REQUESTS = 8;

    @Test
    @DisplayName("overlapping creates of one external_id leave exactly one row")
    void concurrentCreatesOfTheSameExternalIdStoreOneRow() throws Exception {
        ObjectNode payload = seed("flight");
        String externalId = payload.get("external_id").asString();
        CyclicBarrier allAtOnce = new CyclicBarrier(CONCURRENT_REQUESTS);

        List<HttpApi.Response> responses;
        try (ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_REQUESTS)) {
            List<Callable<HttpApi.Response>> requests = java.util.stream.IntStream
                    .range(0, CONCURRENT_REQUESTS)
                    .<Callable<HttpApi.Response>>mapToObj(attempt -> () -> {
                        allAtOnce.await();
                        return api.post("/transactions", payload);
                    })
                    .toList();
            responses = pool.invokeAll(requests).stream().map(ConcurrentCreateTest::value).toList();
        }

        assertThat(countTransactionsWithExternalId(externalId)).isEqualTo(1);

        List<Integer> statuses = responses.stream().map(HttpApi.Response::status).toList();
        assertThat(statuses).filteredOn(status -> status == 201).hasSize(1);
        assertThat(statuses).filteredOn(status -> status == 409).hasSize(CONCURRENT_REQUESTS - 1);

        // Every loser points at the one row that won, so a retrying client always lands on it.
        String storedId = api.get("/transactions?limit=1").at("/items/0/id").asString();
        assertThat(responses.stream()
                .filter(response -> response.status() == 409)
                .map(response -> response.at("/existing_id").asString())
                .collect(Collectors.toSet()))
                .containsExactly(storedId);
    }

    private static HttpApi.Response value(Future<HttpApi.Response> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("A concurrent request failed outright", e.getCause());
        }
    }
}
