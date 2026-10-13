package com.example.billing.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.billing.support.ApiTestBase;
import com.example.billing.support.HttpApi;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The contended write path.
 *
 * <p>Every write to a transaction takes a row lock first, so a request that cannot get that lock
 * within {@code LOCK_TIMEOUT} is an expected outcome under load, not a crash. It has to come back as
 * a coded conflict the client can retry - a bare 500 would tell a caller the service is broken when
 * in fact nothing was written and one row was simply busy.
 */
class LockContentionTest extends ApiTestBase {

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("a write that cannot take the row lock is a coded 409, never a bare 500")
    void contendedWriteIsAConflictNotAServerError() throws Exception {
        String flightId = createTransaction("flight");

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            lockRow(holder, flightId);

            // The service's own SELECT ... FOR UPDATE now waits, and gives up at LOCK_TIMEOUT.
            HttpApi.Response blocked = api.patch("/transactions/" + flightId, Map.of("total", 300.00));

            assertThat(blocked.status()).isEqualTo(409);
            assertThat(blocked.code()).isEqualTo("LOCK_TIMEOUT");
            holder.rollback();
        }

        // Nothing was written while the lock was held, and the row is usable once it is released.
        assertThat(api.get("/transactions/" + flightId).at("/total").decimalValue())
                .isEqualByComparingTo(new BigDecimal("220.00"));
        assertThat(api.patch("/transactions/" + flightId, Map.of("total", 300.00)).status())
                .isEqualTo(200);
    }

    /** Holds a write lock on the row from outside the service, as a concurrent writer would. */
    private static void lockRow(Connection connection, String transactionId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM transactions WHERE id = ? FOR UPDATE")) {
            statement.setString(1, transactionId);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
            }
        }
    }
}
