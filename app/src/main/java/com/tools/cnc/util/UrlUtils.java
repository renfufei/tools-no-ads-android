package com.tools.cnc.util;

import java.util.regex.Pattern;

/**
 * Detects whether a piece of text looks like a web URL (http/https or a
 * bare domain such as www.example.com/path).
 */
public final class UrlUtils {

    /** http(s) URLs. */
    private static final Pattern PATTERN_SCHEME = Pattern.compile("(?i)^https?://\\S+$");
    /** Bare domains: one or more labels + a TLD of 2+ letters, optional port/path/query. */
    private static final Pattern PATTERN_BARE_DOMAIN = Pattern.compile(
            "(?i)^(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,}"
                    + "(?::\\d{1,5})?(?:[/?#]\\S*)?$");

    private UrlUtils() {
    }

    /** True when the text matches http/https URL features or a website address. */
    public static boolean isWebUrl(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        return PATTERN_SCHEME.matcher(trimmed).matches()
                || PATTERN_BARE_DOMAIN.matcher(trimmed).matches();
    }

    /** Ensures the string has a scheme so Uri.parse + ACTION_VIEW works. */
    public static String normalize(String text) {
        String trimmed = text.trim();
        if (PATTERN_SCHEME.matcher(trimmed).matches()) {
            return trimmed;
        }
        return "https://" + trimmed;
    }
}
