package com.portfolio.flashsale.catalog.repository;

import com.portfolio.flashsale.catalog.domain.Product;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

public final class ProductSpecifications {

    private ProductSpecifications() {
    }

    public static Specification<Product> withFilters(String query, String category, Boolean activeOnly) {
        return Specification
                .where(matchesQuery(query))
                .and(matchesCategory(category))
                .and(matchesActive(activeOnly));
    }

    private static Specification<Product> matchesQuery(String query) {
        if (!StringUtils.hasText(query)) {
            return null;
        }
        String pattern = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
        return (root, cq, cb) -> cb.or(
                cb.like(cb.lower(root.get("name")), pattern),
                cb.like(cb.lower(root.get("sku")), pattern)
        );
    }

    private static Specification<Product> matchesCategory(String category) {
        if (!StringUtils.hasText(category)) {
            return null;
        }
        String normalized = category.trim().toLowerCase(Locale.ROOT);
        return (root, cq, cb) -> cb.equal(cb.lower(root.get("category")), normalized);
    }

    private static Specification<Product> matchesActive(Boolean activeOnly) {
        if (activeOnly == null || !activeOnly) {
            return null;
        }
        return (root, cq, cb) -> cb.isTrue(root.get("active"));
    }
}
