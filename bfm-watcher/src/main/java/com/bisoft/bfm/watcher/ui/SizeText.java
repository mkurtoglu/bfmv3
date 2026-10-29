package com.bisoft.bfm.watcher.ui;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Sizes as operators write them: "512 KB", "16MB", "1.5 GB". Units are powers of 1024, as PostgreSQL uses them. */
final class SizeText {

    private static final Pattern SIZE = Pattern.compile("\\s*([0-9]+(?:[.,][0-9]+)?)\\s*([kmgt]?i?b?)\\s*",
            Pattern.CASE_INSENSITIVE);

    private SizeText() {
    }

    /**
     * @return the size in bytes
     * @throws IllegalArgumentException with a message an operator can act on
     */
    static long parse(String text) {
        Matcher m = SIZE.matcher(text == null ? "" : text);
        if (!m.matches()) {
            throw new IllegalArgumentException("Write a size such as 512 KB, 16 MB or 1 GB");
        }
        String unit = m.group(2).toUpperCase(Locale.ROOT).replace("I", "");
        if (unit.isEmpty()) {
            throw new IllegalArgumentException("Add a unit: KB, MB or GB");
        }
        long factor = switch (unit.charAt(0)) {
            case 'K' -> 1024L;
            case 'M' -> 1024L * 1024;
            case 'G' -> 1024L * 1024 * 1024;
            case 'T' -> 1024L * 1024 * 1024 * 1024;
            default -> 1L;
        };
        double v = Double.parseDouble(m.group(1).replace(',', '.'));
        long bytes = Math.round(v * factor);
        if (bytes <= 0) {
            throw new IllegalArgumentException("The size must be above zero");
        }
        return bytes;
    }
}
