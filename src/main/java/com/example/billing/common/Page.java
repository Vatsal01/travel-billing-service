package com.example.billing.common;

import java.util.List;

public record Page<T>(List<T> items, boolean hasMore) {

    public <R> Page<R> map(java.util.function.Function<T, R> mapper) {
        return new Page<>(items.stream().map(mapper).toList(), hasMore);
    }
}
