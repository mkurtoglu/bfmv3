package com.bisoft.bfm.core.ops;

import java.util.List;

/** One line of the switchover pre-check table. */
public record PreCheck(String title, Level level, String detail) {

    public enum Level { PASS, WARN, FAIL }

    public static PreCheck pass(String title, String detail) {
        return new PreCheck(title, Level.PASS, detail);
    }

    public static PreCheck warn(String title, String detail) {
        return new PreCheck(title, Level.WARN, detail);
    }

    public static PreCheck fail(String title, String detail) {
        return new PreCheck(title, Level.FAIL, detail);
    }

    public static boolean allowed(List<PreCheck> checks) {
        return checks.stream().noneMatch(c -> c.level() == Level.FAIL);
    }
}
