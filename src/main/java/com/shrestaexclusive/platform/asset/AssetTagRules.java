package com.shrestaexclusive.platform.asset;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class AssetTagRules {

    static final int MAX_TAG_COUNT = 16;
    static final int MAX_TAG_LENGTH = 40;
    static final String TAG_PATTERN_SOURCE = "^[A-Z0-9][A-Z0-9_-]{0,39}$";
    private static final Pattern TAG_PATTERN = Pattern.compile(TAG_PATTERN_SOURCE);

    private AssetTagRules() {
    }

    static List<String> normalize(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }

        Set<String> normalizedTags = new LinkedHashSet<>();
        for (String tag : tags) {
            String normalized = normalizeOne(tag);
            if (!normalized.isEmpty()) {
                if (normalized.length() > MAX_TAG_LENGTH) {
                    throw new IllegalArgumentException("Asset tags must be " + MAX_TAG_LENGTH + " characters or fewer");
                }
                if (!TAG_PATTERN.matcher(normalized).matches()) {
                    throw new IllegalArgumentException("Asset tags must use uppercase A-Z, 0-9, hyphen, or underscore");
                }
                normalizedTags.add(normalized);
            }
        }

        if (normalizedTags.size() > MAX_TAG_COUNT) {
            throw new IllegalArgumentException("Asset tags support up to " + MAX_TAG_COUNT + " values");
        }

        return List.copyOf(new ArrayList<>(normalizedTags));
    }

    static List<String> normalizeNullable(List<String> tags) {
        return tags == null ? null : normalize(tags);
    }

    private static String normalizeOne(String tag) {
        if (tag == null) {
            return "";
        }

        return tag.trim()
                .toUpperCase(Locale.ROOT)
                .replace("&", " AND ")
                .replaceAll("[^A-Z0-9_-]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("-+", "-")
                .replaceAll("^[_-]+|[_-]+$", "");
    }
}
