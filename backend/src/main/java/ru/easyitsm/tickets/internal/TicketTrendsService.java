package ru.easyitsm.tickets.internal;

import java.sql.Array;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.tickets.api.TicketTrends;

/** Повторяющиеся инциденты (PRB-05) прямо из таблицы заявок, без витрины: считаются и заявки до установки модулей. */
@Service
class TicketTrendsService implements TicketTrends {

    private static final int MAX_IDS = 100;

    private final JdbcClient jdbc;

    TicketTrendsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<IncidentCluster> recurringIncidents(Dimension dimension, Instant from, Instant to, int minCount,
                                                    int limit) {
        String column = switch (dimension) {
            case SERVICE -> "coalesce(service_id, catalog_item_id)";
            case CATEGORY -> "category_id";
            case CI -> "ci_id";
        };
        return jdbc.sql("""
                select %1$s as key, count(*) as cnt,
                       count(*) filter (where status_category not in ('resolved', 'closed', 'cancelled')) as open,
                       min(created_at) as first_at, max(created_at) as last_at,
                       (array_agg(id order by created_at desc, id desc))[1\\:%2$d] as ids
                  from ticket
                 where type = 'incident' and not confidential and %1$s is not null
                   and created_at >= :from and created_at < :to
                 group by %1$s
                having count(*) >= :min
                 order by count(*) desc, max(created_at) desc
                 limit :limit""".formatted(column, MAX_IDS))
                .param("from", Sql.ts(from)).param("to", Sql.ts(to))
                .param("min", Math.max(2, minCount)).param("limit", Math.clamp(limit, 1, 200))
                .query((rs, n) -> {
                    Array arr = rs.getArray("ids");
                    List<UUID> ids = Arrays.stream((Object[]) arr.getArray()).map(o -> (UUID) o).toList();
                    return new IncidentCluster(Sql.uuid(rs, "key"), rs.getLong("cnt"), rs.getLong("open"),
                            Sql.instant(rs, "first_at"), Sql.instant(rs, "last_at"), ids);
                })
                .list();
    }
}
