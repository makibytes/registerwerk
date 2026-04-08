package de.makibytes.registerwerk.web.dto;

import java.util.List;

/**
 * Generic paginated response wrapper.
 *
 * @param <T> type of the content items
 */
public record PageResponse<T>(
    List<T> content,
    long totalElements,
    int totalPages,
    int page,
    int size
) {
    public static <T> PageResponse<T> of(org.springframework.data.domain.Page<T> page) {
        return new PageResponse<>(
            page.getContent(),
            page.getTotalElements(),
            page.getTotalPages(),
            page.getNumber(),
            page.getSize()
        );
    }
}
