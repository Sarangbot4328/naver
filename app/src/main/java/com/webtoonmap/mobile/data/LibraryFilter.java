package com.webtoonmap.mobile.data;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.TreeMap;

/** Shared matching rules for the library search and its tag picker. */
public final class LibraryFilter {
    private LibraryFilter() { }

    public static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).trim();
    }

    public static Set<String> tags(String value) {
        Set<String> tags = new LinkedHashSet<>();
        if (value == null) return tags;
        for (String part : value.split("[,，\\n\\r]+")) {
            String tag = normalize(part).replaceFirst("^#+\\s*", "");
            if (!tag.isEmpty()) tags.add(tag);
        }
        return tags;
    }

    public static Map<String, Integer> tagCounts(List<SeriesItem> items) {
        Map<String, Integer> counts = new TreeMap<>();
        for (SeriesItem item : items) {
            for (String tag : tags(item.tags)) counts.put(tag, counts.getOrDefault(tag, 0) + 1);
        }
        return counts;
    }

    public static void retainAvailableTags(List<SeriesItem> items, Set<String> selectedTags) {
        selectedTags.retainAll(tagCounts(items).keySet());
    }

    public static List<SeriesItem> apply(List<SeriesItem> items, String query, Set<String> selectedTags) {
        List<SeriesItem> result = new ArrayList<>();
        String normalizedQuery = normalize(query).replace("#", " ").trim();
        String[] words = normalizedQuery.isEmpty() ? new String[0] : normalizedQuery.split("\\s+");
        for (SeriesItem item : items) {
            if (!tags(item.tags).containsAll(selectedTags)) continue;
            String text = normalize(item.title + " " + item.tags + " " + item.description);
            boolean matches = true;
            for (String word : words) {
                if (!text.contains(word)) { matches = false; break; }
            }
            if (matches) result.add(item);
        }
        return result;
    }
}
