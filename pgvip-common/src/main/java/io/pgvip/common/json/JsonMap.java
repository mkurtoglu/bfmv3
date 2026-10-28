package io.pgvip.common.json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Typed read access over a parsed JSON object, plus a small builder for writing one. */
public final class JsonMap {

    private final Map<String, Object> map;

    private JsonMap(Map<String, Object> map) {
        this.map = map;
    }

    @SuppressWarnings("unchecked")
    public static JsonMap wrap(Map<?, ?> map) {
        return new JsonMap(map == null ? new LinkedHashMap<>() : (Map<String, Object>) map);
    }

    public static JsonMap create() {
        return new JsonMap(new LinkedHashMap<>());
    }

    public Map<String, Object> raw() {
        return map;
    }

    public JsonMap put(String key, Object value) {
        map.put(key, value);
        return this;
    }

    public boolean has(String key) {
        return map.containsKey(key) && map.get(key) != null;
    }

    public String str(String key) {
        Object v = map.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public String str(String key, String def) {
        String v = str(key);
        return v == null ? def : v;
    }

    public String requireStr(String key) {
        String v = str(key);
        if (v == null || v.isBlank()) {
            throw new JsonException("Missing field '" + key + "'");
        }
        return v;
    }

    public Long lngOrNull(String key) {
        Object v = map.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            throw new JsonException("Field '" + key + "' is not a number");
        }
    }

    public long lng(String key, long def) {
        Long v = lngOrNull(key);
        return v == null ? def : v;
    }

    public long requireLng(String key) {
        Long v = lngOrNull(key);
        if (v == null) {
            throw new JsonException("Missing field '" + key + "'");
        }
        return v;
    }

    public Integer intOrNull(String key) {
        Long v = lngOrNull(key);
        return v == null ? null : v.intValue();
    }

    public int integer(String key, int def) {
        Long v = lngOrNull(key);
        return v == null ? def : v.intValue();
    }

    public boolean bool(String key, boolean def) {
        Object v = map.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(v.toString());
    }

    public JsonMap obj(String key) {
        Object v = map.get(key);
        if (v instanceof Map<?, ?> m) {
            return wrap(m);
        }
        return null;
    }

    public List<JsonMap> objList(String key) {
        Object v = map.get(key);
        if (!(v instanceof List<?> list)) {
            return Collections.emptyList();
        }
        List<JsonMap> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                out.add(wrap(m));
            }
        }
        return out;
    }

    public <T> List<T> objList(String key, Function<JsonMap, T> mapper) {
        List<JsonMap> maps = objList(key);
        List<T> out = new ArrayList<>(maps.size());
        for (JsonMap m : maps) {
            out.add(mapper.apply(m));
        }
        return out;
    }

    public List<String> strList(String key) {
        Object v = map.get(key);
        if (!(v instanceof List<?> list)) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o != null) {
                out.add(o.toString());
            }
        }
        return out;
    }

    public <E extends Enum<E>> E enumValue(String key, Class<E> type, E def) {
        String v = str(key);
        if (v == null) {
            return def;
        }
        try {
            return Enum.valueOf(type, v);
        } catch (IllegalArgumentException e) {
            return def;
        }
    }

    public String toJson() {
        return Json.write(map);
    }

    @Override
    public String toString() {
        return toJson();
    }
}
