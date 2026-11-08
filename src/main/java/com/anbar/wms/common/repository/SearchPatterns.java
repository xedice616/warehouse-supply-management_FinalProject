package com.anbar.wms.common.repository;

import java.util.Locale;

public final class SearchPatterns {

    public static final char ESCAPE = '\\';

    private SearchPatterns() {
    }

    /** Lower-cased {@code LIKE} prefix pattern with user-supplied wildcards escaped. */
    public static String lowerPrefix(String term) {
        String escaped = term.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return escaped + "%";
    }

    public static boolean isBlank(String term) {
        return term == null || term.isBlank();
    }
}
