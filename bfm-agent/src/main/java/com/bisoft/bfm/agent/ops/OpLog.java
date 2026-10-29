package com.bisoft.bfm.agent.ops;

/** Progress lines of a running operation, shown in the watcher UI. */
@FunctionalInterface
public interface OpLog {
    void line(String text);

    OpLog NONE = text -> { };
}
