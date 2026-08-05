package com.webtoonmap.mobile.data;

import java.util.Locale;

public final class AvseeMetadata {
    private AvseeMetadata() { }

    public static String cleanActors(String value) {
        String out = normalize(value);
        if (out.isEmpty()) return "";

        out = out.replaceFirst("(?i)^(?:배우|출연|actor|cast)\\s*[:：]\\s*", "");
        int productAt = firstIndex(out, "품번", "작품번호");
        if (productAt >= 0) {
            out = normalize(out.substring(0, productAt)
                    .replaceFirst("(?:및|[/·|])\\s*$", ""));
        }

        String lower = out.toLowerCase(Locale.US);
        if (out.isEmpty() || "및".equals(out) || out.length() > 160 ||
                lower.contains("등록합니다") || lower.contains("댓글") ||
                lower.contains("문의") || lower.contains("요청") ||
                lower.contains("알려 주세요") || lower.contains("알려주세요")) {
            return "";
        }
        return out;
    }

    private static int firstIndex(String value, String... needles) {
        int first = -1;
        for (String needle : needles) {
            int index = value.indexOf(needle);
            if (index >= 0 && (first < 0 || index < first)) first = index;
        }
        return first;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }
}