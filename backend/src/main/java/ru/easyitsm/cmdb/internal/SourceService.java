package ru.easyitsm.cmdb.internal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.ETags;

/**
 * Источники данных CMDB (CFG-04): агент обнаружения, импорт, коннекторы. Приоритет решает, чьё значение атрибута
 * главнее при сверке (CFG-05): 1 — самый доверенный, ручной ввод — {@value #MANUAL_PRIORITY}.
 */
@Service
class SourceService {

    static final int MANUAL_PRIORITY = 10;
    /** Приоритет удалённого или неизвестного источника: его значения уступают любому. */
    static final int UNKNOWN_PRIORITY = 101;
    static final String CODE = "[a-z][a-z0-9_-]{1,62}";
    static final String KIND = "import|api|discovery|kubernetes|cloud";

    /**
     * @param records    объекты источника, связанные с КЕ (без удалённых источником)
     * @param lastBatchAt время последнего пакета
     */
    record Source(UUID id, String code, String name, String kind, int priority, int staleDays, boolean active,
                  long records, Instant lastBatchAt, Instant createdAt, Instant updatedAt, long version) {
    }

    record CreateSource(
            @NotBlank @Pattern(regexp = CODE) String code,
            @NotBlank @Size(max = 255) String name,
            @NotBlank @Pattern(regexp = KIND) String kind,
            @Min(1) @Max(100) Integer priority,
            @Min(1) @Max(3650) Integer staleDays) {
    }

    record UpdateSource(@Size(min = 1, max = 255) String name, @Min(1) @Max(100) Integer priority,
                        @Min(1) @Max(3650) Integer staleDays, Boolean active) {
    }

    private static final String SELECT = """
            select s.id, s.code, s.name, s.kind, s.priority, s.stale_days, s.active, s.created_at, s.updated_at,
                   s.version,
                   (select count(*) from ci_source_record r where r.source_id = s.id and r.removed_at is null)
                       as records,
                   (select max(b.created_at) from ci_ingest_batch b where b.source_id = s.id) as last_batch_at
              from ci_source s
            """;

    private final JdbcClient jdbc;

    SourceService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<Source> list() {
        return jdbc.sql(SELECT + " order by s.priority, s.name, s.id").query(this::map).list();
    }

    @Transactional(readOnly = true)
    public Source get(String code) {
        return find(code).orElseThrow(() -> ApiException.notFound("Источник данных"));
    }

    Optional<Source> find(String code) {
        return jdbc.sql(SELECT + " where s.code = :code").param("code", code).query(this::map).optional();
    }

    @Transactional
    public Source create(CreateSource cmd) {
        if (find(cmd.code()).isPresent()) {
            throw CmdbProblem.clash("source_exists", "Источник с кодом «" + cmd.code() + "» уже есть");
        }
        UUID actor = CurrentActor.userId().orElse(null);
        Instant now = Sql.now();
        jdbc.sql("""
                insert into ci_source (id, tenant_id, code, name, kind, priority, stale_days, active, created_at,
                                       created_by, updated_at, updated_by, version)
                values (:id, :tenant, :code, :name, :kind, :priority, :stale, true, :now, :actor, :now, :actor, 0)""")
                .param("id", Ids.newId()).param("tenant", TenantContext.require()).param("code", cmd.code())
                .param("name", cmd.name().trim()).param("kind", cmd.kind())
                .param("priority", cmd.priority() == null ? 20 : cmd.priority())
                .param("stale", cmd.staleDays() == null ? QualityService.STALE_DAYS : cmd.staleDays())
                .param("now", Sql.ts(now)).param("actor", actor)
                .update();
        return get(cmd.code());
    }

    @Transactional
    public Source update(String code, String ifMatch, UpdateSource cmd) {
        Source cur = get(code);
        ETags.requireMatch(ifMatch, cur.version());
        jdbc.sql("""
                update ci_source set name = :name, priority = :priority, stale_days = :stale, active = :active,
                                     updated_at = :now, updated_by = :actor, version = version + 1
                 where id = :id and version = :version""")
                .param("id", cur.id()).param("version", cur.version())
                .param("name", cmd.name() == null ? cur.name() : cmd.name().trim())
                .param("priority", cmd.priority() == null ? cur.priority() : cmd.priority())
                .param("stale", cmd.staleDays() == null ? cur.staleDays() : cmd.staleDays())
                .param("active", cmd.active() == null ? cur.active() : cmd.active())
                .param("now", Sql.ts(Sql.now())).param("actor", CurrentActor.userId().orElse(null))
                .update();
        return get(code);
    }

    /** Приоритет владельца значения атрибута: {@code manual} или код источника. */
    int priorityOf(String owner) {
        if (owner == null || CiService.MANUAL.equals(owner)) {
            return MANUAL_PRIORITY;
        }
        return find(owner).map(Source::priority).orElse(UNKNOWN_PRIORITY);
    }

    private Source map(ResultSet rs, int n) throws SQLException {
        return new Source(Sql.uuid(rs, "id"), rs.getString("code"), rs.getString("name"), rs.getString("kind"),
                rs.getInt("priority"), rs.getInt("stale_days"), rs.getBoolean("active"), rs.getLong("records"),
                Sql.instant(rs, "last_batch_at"), Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"),
                rs.getLong("version"));
    }
}
