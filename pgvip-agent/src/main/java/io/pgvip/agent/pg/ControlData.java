package io.pgvip.agent.pg;

/**
 * The fields of {@code pg_controldata} the agent cares about.
 *
 * @param clusterState     for example "shut down", "shut down in recovery", "in production", "in archive recovery"
 * @param checkpointLsn    "Latest checkpoint location"
 * @param timeline         "Latest checkpoint's TimeLineID"
 */
public record ControlData(String clusterState, String checkpointLsn, Integer timeline) {

    public static final ControlData UNKNOWN = new ControlData(null, null, null);

    /** Parses pg_controldata output produced with LC_ALL=C. */
    public static ControlData parse(String text) {
        String state = null;
        String lsn = null;
        Integer tli = null;
        for (String line : text.split("\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            switch (key) {
                case "Database cluster state" -> state = value;
                case "Latest checkpoint location" -> lsn = value;
                case "Latest checkpoint's TimeLineID" -> {
                    try {
                        tli = Integer.parseInt(value);
                    } catch (NumberFormatException ignored) {
                        // leave unknown
                    }
                }
                default -> {
                }
            }
        }
        return new ControlData(state, lsn, tli);
    }

    /** True when the last shutdown was clean, so no crash recovery is pending. */
    public boolean cleanShutdown() {
        return clusterState != null && clusterState.startsWith("shut down");
    }
}
