package com.example.billing.transaction.metadata;

import com.example.billing.common.ApiException;
import com.example.billing.common.WireNames;
import com.example.billing.transaction.TransactionType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;


@Component
public class MetadataCodec {

    private static final Map<TransactionType, Class<? extends TransactionMetadata>> MODELS = Map.of(
            TransactionType.FLIGHT, FlightMetadata.class,
            TransactionType.HOTEL, HotelMetadata.class,
            TransactionType.RAIL, RailMetadata.class,
            TransactionType.NAVAN_FEE, NavanFeeMetadata.class);

    static {
        // A type added to the enum without a model here is a bug that should stop the service from
        // starting, not one that surfaces as a failed request months later.
        for (TransactionType type : TransactionType.values()) {
            if (!MODELS.containsKey(type)) {
                throw new IllegalStateException("No metadata model declared for type " + type);
            }
        }
    }

    
    public static final int MAX_METADATA_CHARS = 64 * 1024;

    private final ObjectMapper objectMapper;
    private final Validator validator;

    public MetadataCodec(ObjectMapper objectMapper, Validator validator) {
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    
    public String validateAndSerialise(TransactionType type, JsonNode metadata) {
        if (metadata == null || !metadata.isObject()) {
            throw ApiException.validationFailed("metadata must be a JSON object for type '" + type.wireValue() + "'");
        }
        String document = metadata.toString();
        if (document.length() > MAX_METADATA_CHARS) {
            throw ApiException.validationFailed("metadata must be at most " + MAX_METADATA_CHARS
                    + " characters for type '" + type.wireValue() + "', got " + document.length());
        }
        TransactionMetadata parsed = parse(type, metadata);
        List<String> errors = validate(parsed);
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(
                    "metadata is not valid for type '" + type.wireValue() + "'", errors);
        }
        return document;
    }

    private TransactionMetadata parse(TransactionType type, JsonNode metadata) {
        try {
            return objectMapper.treeToValue(metadata, MODELS.get(type));
        } catch (JacksonException e) {
            // Includes an unknown fee_kind and any value of the wrong JSON type.
            throw ApiException.validationFailed(
                    "metadata is not valid for type '" + type.wireValue() + "': " + rootMessage(e));
        }
    }

    private List<String> validate(TransactionMetadata parsed) {
        return validator.validate(parsed).stream()
                .map(MetadataCodec::describe)
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private static String describe(ConstraintViolation<TransactionMetadata> violation) {
        String path = violation.getPropertyPath().toString();
        return path.isEmpty() ? violation.getMessage() : WireNames.snakeCase(path) + ": " + violation.getMessage();
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }
}
