package io.pgvip.common;

/** PostgreSQL log sequence number helpers ("16/B374D848" text form). */
public final class Lsn {

    private Lsn() {
    }

    /** Parses an LSN; returns -1 for null or blank input. */
    public static long parse(String text) {
        if (text == null || text.isBlank()) {
            return -1;
        }
        String t = text.trim();
        int slash = t.indexOf('/');
        if (slash <= 0) {
            throw new IllegalArgumentException("Not an LSN: " + text);
        }
        long hi = Long.parseLong(t.substring(0, slash), 16);
        long lo = Long.parseLong(t.substring(slash + 1), 16);
        return (hi << 32) | (lo & 0xFFFFFFFFL);
    }

    public static String format(long value) {
        if (value < 0) {
            return null;
        }
        return Long.toHexString(value >>> 32).toUpperCase() + "/" + Long.toHexString(value & 0xFFFFFFFFL).toUpperCase();
    }

    /** Bytes from {@code behind} to {@code ahead}; null when either side is unknown. Never negative. */
    public static Long diff(String ahead, String behind) {
        long a = parse(ahead);
        long b = parse(behind);
        if (a < 0 || b < 0) {
            return null;
        }
        return Math.max(0, a - b);
    }

    public static int compare(String x, String y) {
        return Long.compare(parse(x), parse(y));
    }

    /** Human readable byte count: 0 B, 30 KB, 1.5 MB, 2.1 GB. */
    public static String bytes(Long bytes) {
        if (bytes == null) {
            return "-";
        }
        double b = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int u = 0;
        while (b >= 1024 && u < units.length - 1) {
            b /= 1024;
            u++;
        }
        if (u == 0) {
            return bytes + " B";
        }
        return (b >= 10 ? String.format("%.0f", b) : String.format("%.1f", b)) + " " + units[u];
    }
}
