package com.bisoft.bfm.watcher.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.data.provider.ListDataProvider;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.function.SerializableFunction;
import com.vaadin.flow.function.SerializableSupplier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The rows of a grid the page refreshes while it is open. Rows are matched by key and updated one by one, so the
 * components in them stay in place: {@code setItems} or a full refresh would build every cell again, and an actions
 * menu open at that moment would close under the user's pointer. Only a change in the keys (a node added or
 * removed) builds the grid again.
 */
final class StableRows<T> {

    private final List<T> rows = new ArrayList<>();
    private final ListDataProvider<T> provider;
    private final SerializableFunction<T, Object> key;

    StableRows(Grid<T> grid, SerializableFunction<T, Object> key) {
        this.key = key;
        this.provider = new ListDataProvider<>(rows) {
            @Override
            public Object getId(T item) {
                return key.apply(item);
            }
        };
        grid.setItems(provider);
    }

    void set(List<T> fresh) {
        boolean sameKeys = rows.size() == fresh.size();
        for (int i = 0; sameKeys && i < rows.size(); i++) {
            sameKeys = Objects.equals(key.apply(rows.get(i)), key.apply(fresh.get(i)));
        }
        rows.clear();
        rows.addAll(fresh);
        if (sameKeys) {
            for (T row : fresh) {
                provider.refreshItem(row);
            }
        } else {
            provider.refreshAll();
        }
    }

    /** The current row with this key (a menu built for an older row looks its row up here). */
    Optional<T> find(Object k) {
        return rows.stream().filter(r -> Objects.equals(key.apply(r), k)).findFirst();
    }

    List<T> rows() {
        return List.copyOf(rows);
    }

    /**
     * Components kept across refreshes, one per key, built again only when {@code spec} (what the component
     * shows or offers) changes.
     */
    static final class Kept<C extends Component> {

        private record Entry<C>(Object spec, C component) {
        }

        private final Map<Object, Entry<C>> kept = new HashMap<>();

        C get(Object key, Object spec, SerializableSupplier<C> build) {
            Entry<C> e = kept.get(key);
            if (e == null || !Objects.equals(e.spec(), spec)) {
                e = new Entry<>(spec, build.get());
                kept.put(key, e);
            }
            return e.component();
        }

        /**
         * A renderer for a grid column of kept components: an updated row keeps its component unless the spec
         * changed.
         */
        static <C extends Component, T> ComponentRenderer<Component, T> renderer(Kept<C> kept,
                SerializableFunction<T, Object> key, SerializableFunction<T, Object> spec,
                SerializableFunction<T, C> build) {
            SerializableFunction<T, Component> get = r -> kept.get(key.apply(r), spec.apply(r), () -> build.apply(r));
            return new ComponentRenderer<>(get, (old, r) -> get.apply(r));
        }
    }
}
