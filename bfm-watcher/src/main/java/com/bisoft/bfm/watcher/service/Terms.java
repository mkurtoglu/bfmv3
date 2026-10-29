package com.bisoft.bfm.watcher.service;

import java.util.Locale;

/**
 * One vocabulary for the console, the API's display fields and the mails: the same operation always has the same
 * name, whether the watcher, an agent or the configuration calls it something shorter.
 */
public final class Terms {

    private Terms() {
    }

    /** Operation types of the watcher (FAILOVER, REINIT, ...) and the agents' own names (reinit, demote, ...). */
    public static String operation(String type) {
        if (type == null) {
            return "";
        }
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "FAILOVER" -> "Automatic failover";
            case "SWITCHOVER" -> "Switchover";
            case "START_PRIMARY" -> "Start as primary";
            case "REJOIN", "DEMOTE" -> "Rejoin";
            case "REINIT" -> "Re-initialize";
            case "FOLLOW" -> "Restart replica";
            case "PROMOTE" -> "Promote";
            case "START" -> "Start";
            case "STOP" -> "Stop";
            case "CHECKPOINT" -> "Checkpoint";
            case "AUTOSTART" -> "Autostart";
            default -> capitalize(type.replace('_', ' ').replace('-', ' ').toLowerCase(Locale.ROOT));
        };
    }

    /** "streaming" -> "Streaming", for values PostgreSQL reports in lower case. */
    public static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** The console role names, the same words as in bfm.users[].role. */
    public static String role(boolean admin) {
        return admin ? "Admin" : "Viewer";
    }
}
