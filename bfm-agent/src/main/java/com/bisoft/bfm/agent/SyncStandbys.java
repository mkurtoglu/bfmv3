package com.bisoft.bfm.agent;

import java.util.regex.Pattern;

/**
 * The values of {@code synchronous_standby_names} BFM writes: empty (asynchronous), {@code FIRST 1 ("a")} for one
 * standby or {@code ANY n ("a", "b", ...)} with node names. Anything else is refused, so the setting never carries text the watcher did not build from the
 * cluster's node names.
 */
public final class SyncStandbys {

    private static final String NAME = "\"[a-z0-9][a-z0-9_-]{0,62}\"";
    private static final Pattern ANY = Pattern.compile("ANY [1-9][0-9]? \\(" + NAME + "(, " + NAME + "){0,31}\\)");
    private static final Pattern FIRST = Pattern.compile("FIRST 1 \\(" + NAME + "\\)");

    private SyncStandbys() {
    }

    public static boolean valid(String value) {
        return value != null && (value.isEmpty() || ANY.matcher(value).matches() || FIRST.matcher(value).matches());
    }
}
