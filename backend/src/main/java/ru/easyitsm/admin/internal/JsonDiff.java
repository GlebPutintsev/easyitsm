package ru.easyitsm.admin.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Сравнение двух снимков настройки (ADM-06). Снимок раскладывается в пути до листьев: {@code cells.high.low},
 * {@code statuses[code=new].name}. Элементы массивов с полем {@code id} или {@code code} сопоставляются по нему,
 * поэтому перестановка статусов не выглядит как замена всего списка; прочие массивы — по индексу.
 */
final class JsonDiff {

    /** Служебные поля, которые меняются при каждом сохранении и не интересны в сравнении. */
    private static final List<String> IGNORED = List.of("version", "updatedAt", "updatedBy", "etag");

    private JsonDiff() {
    }

    record Change(String path, String kind, Object before, Object after) {
    }

    static List<Change> diff(Object before, Object after) {
        Map<String, Object> a = new LinkedHashMap<>();
        Map<String, Object> b = new LinkedHashMap<>();
        flatten("", before, a);
        flatten("", after, b);
        List<Change> changes = new ArrayList<>();
        for (var e : a.entrySet()) {
            if (!b.containsKey(e.getKey())) {
                changes.add(new Change(e.getKey(), "removed", e.getValue(), null));
            } else if (!same(e.getValue(), b.get(e.getKey()))) {
                changes.add(new Change(e.getKey(), "changed", e.getValue(), b.get(e.getKey())));
            }
        }
        for (var e : b.entrySet()) {
            if (!a.containsKey(e.getKey())) {
                changes.add(new Change(e.getKey(), "added", null, e.getValue()));
            }
        }
        return changes;
    }

    private static boolean same(Object x, Object y) {
        if (x instanceof Number n && y instanceof Number m) {
            return n.doubleValue() == m.doubleValue();
        }
        return Objects.equals(x, y);
    }

    private static void flatten(String path, Object node, Map<String, Object> out) {
        if (node instanceof Map<?, ?> map) {
            if (map.isEmpty() && !path.isEmpty()) {
                out.put(path, Map.of());
            }
            for (var e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (path.isEmpty() && IGNORED.contains(key)) {
                    continue;
                }
                flatten(path.isEmpty() ? key : path + "." + key, e.getValue(), out);
            }
        } else if (node instanceof List<?> list) {
            if (list.isEmpty()) {
                out.put(path, List.of());
            }
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                flatten(path + "[" + keyOf(item, i) + "]", item, out);
            }
        } else if (!path.isEmpty() || node != null) {
            out.put(path, node);
        }
    }

    private static String keyOf(Object item, int index) {
        if (item instanceof Map<?, ?> m) {
            for (String k : List.of("id", "code")) {
                Object v = m.get(k);
                if (v != null && !(v instanceof Map) && !(v instanceof List)) {
                    return k + "=" + v;
                }
            }
            Object from = m.get("from");
            Object to = m.get("to");
            if (from != null && to != null) {
                return from + "→" + to;
            }
        }
        return String.valueOf(index);
    }
}
