package com.bisoft.bfm.core.state;

import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/** Typed, compare-and-set access to the {@link ClusterState} kept in a {@link StateStore}. */
public final class ClusterStateRepo {

    public static final String KEY = "cluster";
    private static final int ATTEMPTS = 8;

    private final StateStore store;

    public ClusterStateRepo(StateStore store) {
        this.store = store;
    }

    /** The current state, or null before bootstrap. */
    public ClusterState current() {
        StateStore.Versioned v = store.get(KEY);
        return v == null ? null : ClusterState.fromJson(v.value());
    }

    /** Creates the state if none exists yet; returns whatever is stored afterwards. */
    public ClusterState initialise(ClusterState initial) {
        if (store.compareAndSet(KEY, 0, initial.toJson())) {
            return initial;
        }
        return current();
    }

    /**
     * Applies {@code change} to the current state if {@code precondition} holds, retrying when another write got
     * in between. The precondition is checked again on every attempt.
     *
     * @throws StateConflictException when the precondition does not hold (or the state is missing)
     */
    public ClusterState update(Predicate<ClusterState> precondition, String why, UnaryOperator<ClusterState> change) {
        for (int i = 0; i < ATTEMPTS; i++) {
            StateStore.Versioned v = store.get(KEY);
            if (v == null) {
                throw new StateConflictException("cluster state is not initialised");
            }
            ClusterState cur = ClusterState.fromJson(v.value());
            if (!precondition.test(cur)) {
                throw new StateConflictException(why);
            }
            ClusterState next = change.apply(cur);
            if (store.compareAndSet(KEY, v.version(), next.toJson())) {
                return next;
            }
        }
        throw new StateConflictException("cluster state keeps changing; try again");
    }
}
