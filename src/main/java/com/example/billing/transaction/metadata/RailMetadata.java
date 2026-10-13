package com.example.billing.transaction.metadata;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;


public record RailMetadata(
        @NotEmpty @Valid List<Leg> legs) implements TransactionMetadata {

    public record Leg(
            @NotBlank String origin,
            @NotBlank String destination,
            @NotNull Instant departure,
            @NotNull Instant arrival,
            String trainNumber,
            // "class" is a Java keyword; the wire name is kept.
            @JsonProperty("class") String travelClass) {
    }
}
