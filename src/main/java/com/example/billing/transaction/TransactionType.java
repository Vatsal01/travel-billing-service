package com.example.billing.transaction;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.List;

public enum TransactionType {

    FLIGHT("flight"),
    HOTEL("hotel"),
    RAIL("rail"),
    NAVAN_FEE("navan_fee");

    private final String wireValue;

    TransactionType(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static TransactionType fromWire(String value) {
        return Arrays.stream(values())
                .filter(type -> type.wireValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown transaction type '" + value + "'; expected one of " + wireValues()));
    }

    public static List<String> wireValues() {
        return Arrays.stream(values()).map(TransactionType::wireValue).toList();
    }
}
