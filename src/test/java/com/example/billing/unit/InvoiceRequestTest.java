package com.example.billing.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.billing.common.ApiException;
import com.example.billing.web.dto.InvoiceRequest;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The rules for naming the transactions an invoice bills. */
class InvoiceRequestTest {

    @Test
    @DisplayName("either shape is accepted")
    void acceptsBothShapes() {
        assertThat(new InvoiceRequest("txn_1", null).ids()).containsExactly("txn_1");
        assertThat(new InvoiceRequest(null, List.of("txn_1", "txn_2")).ids())
                .containsExactly("txn_1", "txn_2");
    }

    @Test
    @DisplayName("naming neither, both, or an empty list is rejected rather than guessed at")
    void rejectsAmbiguousShapes() {
        assertThatThrownBy(() -> new InvoiceRequest(null, null).ids())
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new InvoiceRequest("txn_1", List.of("txn_2")).ids())
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new InvoiceRequest(null, List.of()).ids())
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new InvoiceRequest("   ", null).ids())
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a blank id inside the list is rejected, not silently skipped")
    void rejectsBlankIdsInTheList() {
        assertThatThrownBy(() -> new InvoiceRequest(null, Arrays.asList("txn_1", " ")).ids())
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("blank");
        assertThatThrownBy(() -> new InvoiceRequest(null, Arrays.asList("txn_1", null)).ids())
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("one invoice cannot bill an unbounded number of transactions")
    void rejectsTooManyTransactions() {
        List<String> atTheLimit = ids(InvoiceRequest.MAX_TRANSACTIONS_PER_INVOICE);
        List<String> overTheLimit = ids(InvoiceRequest.MAX_TRANSACTIONS_PER_INVOICE + 1);

        assertThat(new InvoiceRequest(null, atTheLimit).ids()).hasSameSizeAs(atTheLimit);
        assertThatThrownBy(() -> new InvoiceRequest(null, overTheLimit).ids())
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("at most");
    }

    private static List<String> ids(int count) {
        return IntStream.range(0, count).mapToObj(index -> "txn_" + index).toList();
    }
}
