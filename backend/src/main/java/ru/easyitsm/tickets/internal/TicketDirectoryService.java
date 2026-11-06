package ru.easyitsm.tickets.internal;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.tickets.api.TicketDirectory;

@Service
@Transactional(readOnly = true)
class TicketDirectoryService implements TicketDirectory {

    private final JdbcClient jdbc;

    TicketDirectoryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<CategoryRef> categories() {
        return jdbc.sql("select id, parent_id, name, active from ticket_category order by lower(name), id")
                .query((rs, n) -> new CategoryRef(Sql.uuid(rs, "id"), Sql.uuid(rs, "parent_id"), rs.getString("name"),
                        rs.getBoolean("active")))
                .list();
    }

    @Override
    public Map<UUID, Long> openCountByAssignee(Collection<UUID> assigneeIds) {
        Map<UUID, Long> out = new HashMap<>();
        if (assigneeIds.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                select assignee_id, count(*) as n from ticket
                 where assignee_id = any(:ids)
                   and status_category not in ('draft', 'resolved', 'closed', 'cancelled')
                 group by assignee_id""")
                .param("ids", assigneeIds.toArray(UUID[]::new))
                .query((rs, n) -> Map.entry(Sql.uuid(rs, "assignee_id"), rs.getLong("n")))
                .list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }
}
