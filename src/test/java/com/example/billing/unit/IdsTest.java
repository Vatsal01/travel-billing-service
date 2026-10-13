package com.example.billing.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.billing.common.Ids;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    @DisplayName("ids are prefixed by what they identify and are unique")
    void generatesPrefixedUniqueIds() {
        List<String> ids = IntStream.range(0, 1_000).mapToObj(index -> Ids.transactionId()).toList();

        assertThat(ids).allMatch(id -> id.startsWith("txn_"));
        assertThat(Ids.invoiceId()).startsWith("inv_");
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("ids carry a UUID version 7 and sort in creation order as time moves on")
    void generatesTimeOrderedUuids() throws InterruptedException {
        UUID first = uuidOf(Ids.transactionId());
        assertThat(first.version()).isEqualTo(7);
        assertThat(first.variant()).isEqualTo(2);

        // Version 7 orders on a millisecond timestamp: ids from the same millisecond are in no
        // particular order, which is all the index locality this is here for needs.
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            ids.add(Ids.transactionId());
            Thread.sleep(2);
        }
        assertThat(ids).isSorted();
    }

    private static UUID uuidOf(String id) {
        return UUID.fromString(id.substring(id.indexOf('_') + 1));
    }
}
