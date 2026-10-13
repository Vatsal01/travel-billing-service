package com.example.billing.transaction.metadata;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.List;

//The kinds of platform-owned fee that can be charged on a trip. 
public enum FeeKind {

    // Flat fee for booking the trip.
    TRIP_FEE("trip_fee"),

    // Fee for a trip handled or changed by a support agent on a call.
    AGENT_CALL_FEE("agent_call_fee");

    private final String wireValue;

    FeeKind(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static FeeKind fromWire(String value) {
        return Arrays.stream(values())
                .filter(kind -> kind.wireValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown fee_kind '" + value + "'; expected one of " + wireValues()));
    }

    public static List<String> wireValues() {
        return Arrays.stream(values()).map(FeeKind::wireValue).toList();
    }
}
