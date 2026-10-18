package edu.cwru.openbench.common;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * One page of a paged list, as the API returns it (design Section 7, "Representation"):
 * {@code {"content": [...], "page": 0, "size": 50, "totalElements": 2}}, with {@code page} from 0.
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements) {

    public PageResponse {
        content = List.copyOf(content);
    }

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements());
    }
}
