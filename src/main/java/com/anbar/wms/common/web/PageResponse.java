package com.anbar.wms.common.web;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * Stable JSON shape for every paginated list endpoint. Spring's {@code Page} is not serialized directly because
 * its JSON structure is an implementation detail that has changed between Spring Data versions.
 *
 * @param page zero-based page index
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {

    public PageResponse {
        content = List.copyOf(content);
    }

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages(), page.isFirst(), page.isLast());
    }

    /** Maps entities to DTOs while keeping the paging metadata. */
    public static <E, T> PageResponse<T> from(Page<E> page, Function<? super E, ? extends T> mapper) {
        return from(page.map(mapper));
    }
}
