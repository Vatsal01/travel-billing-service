package com.example.billing.web.dto;

import com.example.billing.common.Page;
import java.util.List;
import java.util.function.Function;


public record PageResponse<T>(List<T> items, int limit, int offset, boolean hasMore) {

    public static <D, T> PageResponse<T> of(Page<D> page, int limit, int offset, Function<D, T> mapper) {
        return new PageResponse<>(page.items().stream().map(mapper).toList(), limit, offset, page.hasMore());
    }
}
