package ru.easyitsm.cmdb.internal;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ru.easyitsm.cmdb.api.CiLayer;

/** Чтение классов КЕ и типов связей арендатора. Классов десятки, поэтому дерево читается целиком. */
@Component
class CiClasses {

    record RelationType(java.util.UUID id, String code, String name, String inverseName, String impactDirection,
                        boolean system, boolean active) {

        boolean propagates() {
            return !"none".equals(impactDirection);
        }
    }

    private final JdbcClient jdbc;
    private final CmdbJson json;
    private final CmdbDefaults defaults;

    CiClasses(JdbcClient jdbc, CmdbJson json, CmdbDefaults defaults) {
        this.jdbc = jdbc;
        this.json = json;
        this.defaults = defaults;
    }

    ClassTree tree() {
        defaults.ensure();
        List<ClassTree.ClassInfo> rows = jdbc.sql("""
                        select id, parent_id, code, name, layer, abstract, system, active, attributes::text as attributes,
                               identification_rules::text as rules, version
                          from ci_class order by name, id""")
                .query((rs, n) -> new ClassTree.ClassInfo(Sql.uuid(rs, "id"), Sql.uuid(rs, "parent_id"),
                        rs.getString("code"), rs.getString("name"), CiLayer.fromCode(rs.getString("layer")),
                        rs.getBoolean("abstract"), rs.getBoolean("system"), rs.getBoolean("active"),
                        json.readAttributes(rs.getString("attributes")), json.readRules(rs.getString("rules")),
                        rs.getLong("version")))
                .list();
        return new ClassTree(rows);
    }

    List<RelationType> relationTypes() {
        defaults.ensure();
        return jdbc.sql("""
                        select id, code, name, inverse_name, impact_direction, system, active
                          from ci_relation_type order by name, id""")
                .query((rs, n) -> new RelationType(Sql.uuid(rs, "id"), rs.getString("code"), rs.getString("name"),
                        rs.getString("inverse_name"), rs.getString("impact_direction"), rs.getBoolean("system"),
                        rs.getBoolean("active")))
                .list();
    }
}
