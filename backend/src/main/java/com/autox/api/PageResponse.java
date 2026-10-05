package com.autox.api;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * The envelope every list endpoint returns.
 *
 * <p>Spring's own {@code Page} serialises its internal structure, which would tie the
 * public contract to a Spring Data version. This is our shape, and it is the only
 * one a mobile client has to understand.
 *
 * <p>Offset paging is correct for admin tables. Public search will use a keyset
 * cursor instead — same envelope, with a {@code nextCursor} added — because offset
 * paging skips and repeats rows while listings are being inserted underneath.
 */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext) {

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext());
    }
}
