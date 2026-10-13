package com.example.billing.support;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A thin HTTP client for the tests.
 *
 * <p>The tests drive the service over real HTTP on a real port, so what they exercise is the whole
 * stack: binding, validation, the transaction boundaries and the database. The JDK request factory
 * is used because {@code PATCH} is part of this API's contract and the simple factory cannot send
 * it. Error statuses are returned rather than thrown, because a 409 is an expected answer here.
 */
public class HttpApi {

    private final RestClient client;
    private final ObjectMapper objectMapper;

    public HttpApi(String baseUrl, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.client = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory())
                .baseUrl(baseUrl)
                .defaultStatusHandler(status -> true, (request, response) -> {
                })
                .build();
    }

    public Response get(String path) {
        return send(HttpMethod.GET, path, null);
    }

    public Response post(String path, Object body) {
        return send(HttpMethod.POST, path, body);
    }

    public Response patch(String path, Object body) {
        return send(HttpMethod.PATCH, path, body);
    }

    public Response delete(String path) {
        return send(HttpMethod.DELETE, path, null);
    }

    private Response send(HttpMethod method, String path, Object body) {
        RestClient.RequestBodySpec request = client.method(method).uri(path);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON)
                    .body(body instanceof String text ? text : objectMapper.writeValueAsString(body));
        }
        ResponseEntity<String> response = request.retrieve().toEntity(String.class);
        String payload = response.getBody();
        JsonNode parsed = payload == null || payload.isBlank() ? null : objectMapper.readTree(payload);
        return new Response(response.getStatusCode().value(), parsed);
    }

    /** One HTTP response: the status, and the body as a tree so tests assert on the wire shape. */
    public record Response(int status, JsonNode body) {

        public String id() {
            return body.path("id").asString();
        }

        /** Reads a value by JSON Pointer, e.g. {@code at("/metadata/coupons/0/flight_number")}. */
        public JsonNode at(String jsonPointer) {
            return body.at(jsonPointer);
        }

        public String code() {
            return body.path("code").asString();
        }
    }
}
