package com.anbar.wms.common.web;

import com.anbar.wms.common.exception.BusinessException;
import com.anbar.wms.common.exception.ErrorCode;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Restricts the {@code sort} request parameter to an explicit set of API property names, each mapped to an
 * entity path. This keeps clients from sorting on unindexed or internal columns (or traversing associations),
 * and decouples API names from entity field names.
 *
 * <pre>{@code
 * private static final SortWhitelist SORT = SortWhitelist.of(
 *         Map.of("sku", "sku", "name", "name", "createdAt", "createdAt"),
 *         Sort.by("sku"));
 *
 * Pageable safe = SORT.apply(pageable);
 * }</pre>
 */
public final class SortWhitelist {

    private final Map<String, String> allowed;
    private final Sort defaultSort;

    private SortWhitelist(Map<String, String> allowed, Sort defaultSort) {
        this.allowed = Map.copyOf(allowed);
        this.defaultSort = defaultSort;
    }

    /**
     * @param allowed     API property name → entity property path
     * @param defaultSort used when the request has no sort; should end in a unique property for stable paging
     */
    public static SortWhitelist of(Map<String, String> allowed, Sort defaultSort) {
        return new SortWhitelist(allowed, defaultSort);
    }

    /** Returns an equivalent pageable sorted by entity paths, or throws {@code INVALID_SORT_PROPERTY}. */
    public Pageable apply(Pageable pageable) {
        if (pageable.isUnpaged()) {
            return pageable;
        }
        if (pageable.getSort().isUnsorted()) {
            return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), defaultSort);
        }
        Sort translated = Sort.unsorted();
        for (Sort.Order order : pageable.getSort()) {
            String path = allowed.get(order.getProperty());
            if (path == null) {
                throw new BusinessException(ErrorCode.INVALID_SORT_PROPERTY,
                        "Sorting by '%s' is not supported".formatted(order.getProperty()),
                        Map.of("property", order.getProperty(), "allowed", new TreeSet<>(allowed.keySet())));
            }
            translated = translated.and(Sort.by(order.withProperty(path)));
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), translated);
    }

    public Set<String> allowedProperties() {
        return allowed.keySet();
    }
}
