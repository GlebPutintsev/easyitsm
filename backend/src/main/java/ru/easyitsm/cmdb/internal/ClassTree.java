package ru.easyitsm.cmdb.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import ru.easyitsm.cmdb.api.CiLayer;

/** Иерархия классов арендатора с наследованием атрибутов и правил идентификации (CFG-01). */
final class ClassTree {

    record ClassInfo(UUID id, UUID parentId, String code, String name, CiLayer layer, boolean isAbstract,
                     boolean system, boolean active, List<AttributeDef> attributes, List<List<String>> rules,
                     long version) {
    }

    private final Map<UUID, ClassInfo> byId = new LinkedHashMap<>();
    private final Map<String, ClassInfo> byCode = new LinkedHashMap<>();

    ClassTree(Collection<ClassInfo> classes) {
        for (ClassInfo c : classes) {
            byId.put(c.id(), c);
            byCode.put(c.code(), c);
        }
    }

    Collection<ClassInfo> all() {
        return byId.values();
    }

    Optional<ClassInfo> byId(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    Optional<ClassInfo> byCode(String code) {
        return Optional.ofNullable(byCode.get(code));
    }

    /** Цепочка от корня до класса. */
    List<ClassInfo> lineage(ClassInfo c) {
        Deque<ClassInfo> chain = new ArrayDeque<>();
        Set<UUID> seen = new HashSet<>();
        for (ClassInfo cur = c; cur != null && seen.add(cur.id()); cur = byId.get(cur.parentId())) {
            chain.addFirst(cur);
        }
        return new ArrayList<>(chain);
    }

    /** Атрибуты с учётом наследования: сначала родительские; подкласс может переопределить атрибут по коду. */
    List<AttributeDef> effectiveAttributes(ClassInfo c) {
        Map<String, AttributeDef> out = new LinkedHashMap<>();
        for (ClassInfo k : lineage(c)) {
            for (AttributeDef a : k.attributes()) {
                out.put(a.code(), a);
            }
        }
        return List.copyOf(out.values());
    }

    /** Правила идентификации: собственные или ближайшего предка, у которого они заданы. */
    List<List<String>> effectiveRules(ClassInfo c) {
        List<ClassInfo> chain = lineage(c);
        for (int i = chain.size() - 1; i >= 0; i--) {
            if (!chain.get(i).rules().isEmpty()) {
                return chain.get(i).rules();
            }
        }
        return List.of();
    }

    /** Классы с подклассами по кодам; неизвестные коды пропускаются. */
    Set<UUID> withDescendants(Collection<String> codes) {
        Set<UUID> out = new HashSet<>();
        Deque<UUID> queue = new ArrayDeque<>();
        for (String code : codes) {
            ClassInfo c = byCode.get(code);
            if (c != null) {
                queue.add(c.id());
            }
        }
        while (!queue.isEmpty()) {
            UUID id = queue.poll();
            if (out.add(id)) {
                for (ClassInfo k : byId.values()) {
                    if (id.equals(k.parentId())) {
                        queue.add(k.id());
                    }
                }
            }
        }
        return out;
    }
}
