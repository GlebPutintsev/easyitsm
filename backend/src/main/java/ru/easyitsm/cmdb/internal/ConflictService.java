package ru.easyitsm.cmdb.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.KeysetCursor;
import ru.easyitsm.platform.web.PageParams;
import tools.jackson.core.type.TypeReference;

/**
 * Экран «Сверка» (CFG-05): конфликты, которые сверка не решила сама.
 * <ul>
 *   <li>{@code attribute}: «Принять источник» — значение источника записывается в КЕ; «Игнорировать» — остаётся
 *       текущее, то же значение источника конфликт больше не поднимает.</li>
 *   <li>{@code duplicate}: «Принять» с выбранной КЕ — объект источника связывается с ней; «Объединить» —
 *       остальные кандидаты сливаются в выбранную ({@code CiMerged}); «Игнорировать» — объект пропускается до
 *       следующего пакета.</li>
 * </ul>
 */
@Service
class ConflictService {

    private static final TypeReference<IngestService.Item> ITEM = new TypeReference<>() {
    };

    record SourceRef(String code, String name) {
    }

    record ConflictView(UUID id, String kind, String status, SourceRef source, String externalId,
                        CmdbController.CiRefView ci, String attribute, Object currentValue, Object proposedValue,
                        String currentSource, List<CmdbController.CiRefView> candidates, String itemName,
                        String itemClassCode, Instant createdAt, Instant updatedAt, Instant resolvedAt,
                        UUID resolvedBy, long version) {
    }

    /** @param ciId для {@code duplicate} при {@code accept} и {@code merge}: КЕ из кандидатов */
    record Resolve(@NotBlank @Pattern(regexp = "accept|ignore|merge") String action, UUID ciId) {
    }

    private record Row(UUID id, String kind, String status, UUID sourceId, String sourceCode, String sourceName,
                       String externalId, UUID ciId, String attribute, String currentValue, String proposedValue,
                       String currentSource, List<UUID> candidates, String item, Instant createdAt,
                       Instant updatedAt, Instant resolvedAt, UUID resolvedBy, long version) {
    }

    private static final String SELECT = """
            select x.id, x.kind, x.status, x.source_id, s.code as source_code, s.name as source_name, x.external_id,
                   x.ci_id, x.attribute, x.current_value::text as current_value,
                   x.proposed_value::text as proposed_value, x.current_source, x.candidate_ids,
                   x.item::text as item, x.created_at, x.updated_at, x.resolved_at, x.resolved_by, x.version
              from ci_conflict x join ci_source s on s.id = x.source_id
            """;

    private final JdbcClient jdbc;
    private final CiStore store;
    private final CiService cis;
    private final IngestService ingest;
    private final MergeService merges;
    private final CmdbJson json;
    private final ApplicationEventPublisher events;

    ConflictService(JdbcClient jdbc, CiStore store, CiService cis, IngestService ingest, MergeService merges,
                    CmdbJson json, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.store = store;
        this.cis = cis;
        this.ingest = ingest;
        this.merges = merges;
        this.json = json;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public CursorPage<ConflictView> list(String status, String kind, String sourceCode, UUID ciId,
                                         PageParams page) {
        Map<String, Object> params = new HashMap<>();
        StringBuilder where = new StringBuilder(" where true");
        String st = status == null || status.isBlank() ? "open" : status;
        if (!"all".equals(st)) {
            if (!st.matches("open|accepted|ignored|merged|dismissed")) {
                throw CmdbProblem.invalid("invalid_field", "status",
                        "status: open, accepted, ignored, merged, dismissed, all");
            }
            where.append(" and x.status = :status");
            params.put("status", st);
        }
        if (kind != null && !kind.isBlank()) {
            where.append(" and x.kind = :kind");
            params.put("kind", kind);
        }
        if (sourceCode != null && !sourceCode.isBlank()) {
            where.append(" and s.code = :source");
            params.put("source", sourceCode);
        }
        if (ciId != null) {
            where.append(" and (x.ci_id = :ci or :ci = any(x.candidate_ids))");
            params.put("ci", ciId);
        }
        KeysetCursor after = page.keyset();
        if (after != null) {
            where.append(" and (x.created_at, x.id) < (cast(:afterAt as timestamptz), :afterId)");
            params.put("afterAt", after.sortValue());
            params.put("afterId", after.id());
        }
        params.put("limit", page.limit() + 1);
        List<Row> rows = jdbc.sql(SELECT + where + " order by x.created_at desc, x.id desc limit :limit")
                .params(params).query(this::map).list();
        CursorPage<Row> raw = CursorPage.of(rows, page.limit(),
                r -> new KeysetCursor(r.createdAt().toString(), r.id()));
        Map<UUID, CmdbController.CiRefView> refs = refs(raw.items());
        return raw.map(r -> view(r, refs));
    }

    @Transactional
    public ConflictView resolve(UUID id, Resolve cmd) {
        Row row = jdbc.sql(SELECT + " where x.id = :id for update of x").param("id", id).query(this::map)
                .optional().orElseThrow(() -> ApiException.notFound("Конфликт"));
        if (!"open".equals(row.status())) {
            throw CmdbProblem.clash("conflict_resolved", "Конфликт уже разобран");
        }
        UUID actor = CurrentActor.userId().orElse(null);
        Instant now = Sql.now();
        String status = switch (row.kind() + ":" + cmd.action()) {
            case "attribute:accept" -> {
                acceptAttribute(row, actor, now);
                yield "accepted";
            }
            case "attribute:ignore" -> "ignored";
            case "duplicate:ignore" -> "dismissed";
            case "duplicate:accept", "duplicate:merge" -> {
                if (cmd.ciId() == null || !row.candidates().contains(cmd.ciId())) {
                    throw CmdbProblem.invalid("unknown_ci", "ciId", "Выберите КЕ из найденных кандидатов");
                }
                if ("merge".equals(cmd.action())) {
                    for (UUID other : row.candidates()) {
                        if (!other.equals(cmd.ciId()) && store.find(other).map(r -> r.mergedInto() == null)
                                .orElse(false)) {
                            merges.merge(cmd.ciId(), other, CiService.MANUAL, actor, now, events::publishEvent);
                        }
                    }
                }
                IngestService.Item item = json.read(row.item(), ITEM);
                ingest.applyTo(cmd.ciId(), row.sourceCode(), item, events::publishEvent);
                yield "merge".equals(cmd.action()) ? "merged" : "accepted";
            }
            default -> throw CmdbProblem.invalid("invalid_field", "action",
                    "Для конфликта атрибута: accept или ignore; для дубликата: accept, merge или ignore");
        };
        jdbc.sql("""
                update ci_conflict set status = :status, resolved_at = :now, resolved_by = :actor, updated_at = :now,
                                       ci_id = coalesce(ci_id, :ci), version = version + 1
                 where id = :id""")
                .param("status", status).param("now", Sql.ts(now)).param("actor", actor)
                .param("ci", cmd.ciId()).param("id", id).update();
        Row done = jdbc.sql(SELECT + " where x.id = :id").param("id", id).query(this::map).single();
        return view(done, refs(List.of(done)));
    }

    /** Значение источника записывается в КЕ, владельцем атрибута становится источник. */
    private void acceptAttribute(Row row, UUID actor, Instant now) {
        CiRow cur = store.lock(row.ciId()).orElseThrow(() -> ApiException.notFound("КЕ"));
        Map<String, Object> attrs = new LinkedHashMap<>(cur.attributes());
        Map<String, Object> owners = new LinkedHashMap<>(cur.attributeSources());
        attrs.put(row.attribute(), json.read(row.proposedValue()));
        owners.put(row.attribute(), row.sourceCode());
        CiRow next = new CiRow(cur.id(), cur.classId(), cur.classCode(), cur.className(), cur.layer(), cur.name(),
                cur.status(), cur.environment(), cur.criticality(), cur.ownerGroupId(), cur.description(), attrs,
                owners, cur.lastSeenAt(), cur.createdAt(), cur.createdBy(), now, actor, cur.version() + 1,
                cur.mergedInto());
        Set<String> changed = cis.recordChanges(cur, next, row.sourceCode(), actor, now);
        store.update(next);
        cis.publishChanges(cur, next, changed, row.sourceCode(), actor, now);
    }

    private Map<UUID, CmdbController.CiRefView> refs(List<Row> rows) {
        List<UUID> ids = new ArrayList<>();
        for (Row r : rows) {
            if (r.ciId() != null) {
                ids.add(r.ciId());
            }
            ids.addAll(r.candidates());
        }
        return store.findAll(ids.stream().distinct().toList()).stream()
                .map(r -> CmdbController.CiRefView.of(r.ref()))
                .collect(Collectors.toMap(CmdbController.CiRefView::id, Function.identity()));
    }

    private ConflictView view(Row r, Map<UUID, CmdbController.CiRefView> refs) {
        String itemName = null;
        String itemClass = null;
        if (r.item() != null) {
            IngestService.Item item = json.read(r.item(), ITEM);
            itemName = item.name();
            itemClass = item.classCode();
        }
        return new ConflictView(r.id(), r.kind(), r.status(), new SourceRef(r.sourceCode(), r.sourceName()),
                r.externalId(), r.ciId() == null ? null : refs.get(r.ciId()), r.attribute(),
                json.read(r.currentValue()), json.read(r.proposedValue()), r.currentSource(),
                r.candidates().stream().map(refs::get).filter(java.util.Objects::nonNull).toList(), itemName,
                itemClass, r.createdAt(), r.updatedAt(), r.resolvedAt(), r.resolvedBy(), r.version());
    }

    private Row map(ResultSet rs, int n) throws SQLException {
        List<UUID> candidates = new ArrayList<>();
        Array array = rs.getArray("candidate_ids");
        if (array != null) {
            for (Object o : (Object[]) array.getArray()) {
                candidates.add((UUID) o);
            }
        }
        return new Row(Sql.uuid(rs, "id"), rs.getString("kind"), rs.getString("status"), Sql.uuid(rs, "source_id"),
                rs.getString("source_code"), rs.getString("source_name"), rs.getString("external_id"),
                Sql.uuid(rs, "ci_id"), rs.getString("attribute"), rs.getString("current_value"),
                rs.getString("proposed_value"), rs.getString("current_source"), candidates, rs.getString("item"),
                Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"), Sql.instant(rs, "resolved_at"),
                Sql.uuid(rs, "resolved_by"), rs.getLong("version"));
    }
}
