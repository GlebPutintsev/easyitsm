package ru.easyitsm.assets.internal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Список активов с фильтрами и сводка по состояниям. Ключ страницы — id (UUIDv7: новые сверху). */
@Component
class AssetSearch {

    private final JdbcClient jdbc;

    AssetSearch(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Filter(String q, String state, String kind, UUID holderId, UUID locationId, UUID ciId, UUID contractId,
                  UUID vendorId, UUID ticketId) {
    }

    record Row(UUID id, String tag, String name, String kind, String model, String serial, String state,
               UUID holderId, UUID locationId, String locationName, LocalDate warrantyTo, BigDecimal cost,
               String currency) {
    }

    List<Row> page(Filter f, UUID afterId, int limit) {
        StringBuilder sql = new StringBuilder("""
                select a.id, a.tag, a.name, a.kind, a.model, a.serial, a.state, a.holder_id, a.location_id,
                       l.name as location_name, a.warranty_to, a.cost, a.currency
                  from asset a left join asset_location l on l.id = a.location_id
                 where true""");
        Map<String, Object> p = new HashMap<>();
        if (f.q() != null && !f.q().isBlank()) {
            sql.append(" ").append("""
                     and (a.tag ilike :q escape '\\' or a.name ilike :q escape '\\' or a.serial ilike :q escape '\\'
                          or a.model ilike :q escape '\\' or a.manufacturer ilike :q escape '\\')""");
            p.put("q", "%" + like(f.q().strip()) + "%");
        }
        if (f.state() != null) {
            sql.append(" and a.state = :state");
            p.put("state", f.state());
        } else {
            // Списанные по умолчанию не мешают в списке, их показывает фильтр «Списаны».
            sql.append(" and a.state <> 'retired'");
        }
        eq(sql, p, "a.kind", "kind", f.kind());
        eq(sql, p, "a.holder_id", "holder", f.holderId());
        eq(sql, p, "a.location_id", "location", f.locationId());
        eq(sql, p, "a.ci_id", "ci", f.ciId());
        eq(sql, p, "a.contract_id", "contract", f.contractId());
        eq(sql, p, "a.vendor_id", "vendor", f.vendorId());
        if (f.ticketId() != null) {
            sql.append(" and exists (select 1 from asset_ticket t where t.asset_id = a.id and t.ticket_id = :ticket)");
            p.put("ticket", f.ticketId());
        }
        if (afterId != null) {
            sql.append(" and a.id < :after");
            p.put("after", afterId);
        }
        sql.append(" order by a.id desc limit :limit");
        p.put("limit", limit);
        return jdbc.sql(sql.toString()).params(p).query(AssetSearch::row).list();
    }

    private static void eq(StringBuilder sql, Map<String, Object> p, String column, String name, Object value) {
        if (value != null) {
            sql.append(" and ").append(column).append(" = :").append(name);
            p.put(name, value);
        }
    }

    private static String like(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getString("tag"), rs.getString("name"),
                rs.getString("kind"), rs.getString("model"), rs.getString("serial"), rs.getString("state"),
                rs.getObject("holder_id", UUID.class), rs.getObject("location_id", UUID.class),
                rs.getString("location_name"), rs.getObject("warranty_to", LocalDate.class),
                rs.getBigDecimal("cost"), rs.getString("currency"));
    }

    AssetDtos.AssetStats stats(LocalDate today, int warrantyDays) {
        record Counts(long total, long inStock, long inUse, long inRepair, long retired, long warranty, long eol) {
        }
        Counts c = jdbc.sql("""
                        select count(*) filter (where state <> 'retired') as total,
                               count(*) filter (where state = 'in_stock') as in_stock,
                               count(*) filter (where state = 'in_use') as in_use,
                               count(*) filter (where state = 'in_repair') as in_repair,
                               count(*) filter (where state = 'retired') as retired,
                               count(*) filter (where state <> 'retired' and warranty_to between :today and :until)
                                   as warranty,
                               count(*) filter (where state <> 'retired' and purchased_on is not null
                                   and service_life_months is not null
                                   and purchased_on + make_interval(months => service_life_months) <= :today) as eol
                          from asset""")
                .param("today", today).param("until", today.plusDays(warrantyDays))
                .query((rs, n) -> new Counts(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5),
                        rs.getLong(6), rs.getLong(7)))
                .single();
        List<AssetDtos.CostTotal> cost = jdbc.sql("""
                        select currency, sum(cost) from asset where state <> 'retired' and cost is not null
                         group by currency order by currency""")
                .query((rs, n) -> new AssetDtos.CostTotal(rs.getString(1), rs.getBigDecimal(2))).list();
        return new AssetDtos.AssetStats(c.total(), c.inStock(), c.inUse(), c.inRepair(), c.retired(), c.warranty(),
                c.eol(), cost);
    }

    /** Сколько активов ссылается на договор (для карточки договора). */
    Map<UUID, Long> countByContract(List<UUID> contractIds) {
        if (contractIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Long> out = new HashMap<>();
        jdbc.sql("select contract_id, count(*) from asset where contract_id in (:ids) group by contract_id")
                .param("ids", contractIds)
                .query(rs -> {
                    out.put(rs.getObject(1, UUID.class), rs.getLong(2));
                });
        return out;
    }
}
