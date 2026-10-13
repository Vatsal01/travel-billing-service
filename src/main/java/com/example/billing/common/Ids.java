package com.example.billing.common;

import java.security.SecureRandom;
import java.util.UUID;

public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static String transactionId() {
        return "txn_" + uuidV7();
    }

    public static String invoiceId() {
        return "inv_" + uuidV7();
    }

    static UUID uuidV7() {
        long millis = System.currentTimeMillis();            // 48 bits of big-endian timestamp
        long randomA = RANDOM.nextInt(1 << 12);              // 12 bits
        long mostSignificant = (millis << 16) | (0x7L << 12) | randomA;
        long leastSignificant = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL)   // 62 random bits
                | 0x8000_0000_0000_0000L;                                      // variant 0b10
        return new UUID(mostSignificant, leastSignificant);
    }
}
