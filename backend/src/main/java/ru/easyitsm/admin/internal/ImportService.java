package ru.easyitsm.admin.internal;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.identity.api.DirectoryAdmin;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.KeysetCursor;
import ru.easyitsm.platform.web.PageParams;
import tools.jackson.core.type.TypeReference;

/**
 * Импорт CSV (ADM-04): загрузка с предпросмотром и проверкой каждой строки, применение одной транзакцией
 * и откат созданного. Пользователи и группы создаются через {@code identity.api.DirectoryAdmin}, подразделения —
 * в справочнике модуля. Существующие записи (по логину или имени) не меняются, строка помечается «пропущена».
 */
@Service
class ImportService {

    static final int MAX_ROWS = 5000;
    static final int MAX_CHARS = 2 * 1024 * 1024;
    static final Pattern LOGIN = Pattern.compile("^[^\\s]{1,255}$");
    static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    static final Set<String> GROUP_TYPES = Set.of("support", "security", "approval", "other");

    /** Колонки каждого вида: имя в API и принятые заголовки файла (без учёта регистра). */
    static final Map<String, Map<String, List<String>>> COLUMNS = Map.of(
            "users", columns(
                    "login", List.of("login", "логин"),
                    "display_name", List.of("display_name", "name", "имя", "фио"),
                    "email", List.of("email", "e-mail", "почта"),
                    "locale", List.of("locale", "язык"),
                    "timezone", List.of("timezone", "часовой пояс"),
                    "department", List.of("department", "подразделение"),
                    "groups", List.of("groups", "группы")),
            "groups", columns(
                    "name", List.of("name", "название", "имя"),
                    "type", List.of("type", "тип"),
                    "description", List.of("description", "описание")),
            "departments", columns(
                    "name", List.of("name", "название"),
                    "code", List.of("code", "код"),
                    "parent", List.of("parent", "вышестоящее", "родитель")));
    static final Map<String, List<String>> REQUIRED = Map.of(
            "users", List.of("login", "display_name"),
            "groups", List.of("name"),
            "departments", List.of("name"));

    private static final TypeReference<List<Row>> ROWS = new TypeReference<>() {
    };
    private static final TypeReference<List<Ref>> REFS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final AdminJson json;
    private final DirectoryAdmin directory;
    private final OrgUnitService orgUnits;
    private final ConfigVersionService versions;

    ImportService(JdbcClient jdbc, AdminJson json, DirectoryAdmin directory, OrgUnitService orgUnits,
                  ConfigVersionService versions) {
        this.jdbc = jdbc;
        this.json = json;
        this.directory = directory;
        this.orgUnits = orgUnits;
        this.versions = versions;
    }

    /** Строка файла: номер строки в файле, значения, ошибки (строка не будет загружена) и предупреждения. */
    record Row(int line, Map<String, String> values, String action, List<String> errors, List<String> warnings) {
    }

    /** Что создал импорт: user, group, department, membership (id — пользователь, groupId — группа). */
    record Ref(String type, UUID id, UUID groupId) {
    }

    record Job(UUID id, String kind, String status, String fileName, int totalRows, int validRows, int errorRows,
               int skipRows, List<Row> rows, List<String> columns, Instant appliedAt, Instant rolledBackAt,
               UUID createdBy, Instant createdAt, Map<String, Integer> result) {
    }

    record JobSummary(UUID id, String kind, String status, String fileName, int totalRows, int validRows,
                      UUID createdBy, Instant createdAt, Instant appliedAt, Instant rolledBackAt) {
    }

    // ---------------------------------------------------------------- предпросмотр

    @Transactional
    Job preview(String kind, String fileName, String content) {
        if (!COLUMNS.containsKey(kind)) {
            throw ApiException.badRequest("invalid_kind", "Импортировать можно users, groups или departments");
        }
        if (content == null || content.isBlank()) {
            throw ApiException.badRequest("empty_file", "Файл пустой");
        }
        if (content.length() > MAX_CHARS) {
            throw ApiException.badRequest("file_too_large", "Файл больше 2 МБ");
        }
        List<List<String>> raw = Csv.parse(content, MAX_ROWS);
        if (raw.size() < 2) {
            throw ApiException.badRequest("no_rows", "В файле нет строк после заголовка");
        }
        Map<Integer, String> header = header(kind, raw.getFirst());
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < raw.size(); i++) {
            Map<String, String> values = new LinkedHashMap<>();
            List<String> cells = raw.get(i);
            for (var h : header.entrySet()) {
                String v = h.getKey() < cells.size() ? cells.get(h.getKey()).trim() : "";
                values.put(h.getValue(), v);
            }
            rows.add(new Row(i + 1, values, "create", List.of(), List.of()));
        }
        List<Row> checked = validate(kind, rows);
        UUID id = Ids.newId();
        UUID actor = CurrentActor.userId().orElse(null);
        var now = AdminJson.ts(Instant.now());
        String safeName = fileName == null ? "" : fileName.trim();
        safeName = safeName.length() > 255 ? safeName.substring(0, 255) : safeName;
        jdbc.sql("""
                insert into import_job (id, tenant_id, kind, status, file_name, total_rows, valid_rows, rows,
                                        created_at, created_by, updated_at, updated_by, version)
                values (:id, :tenant, :kind, 'preview', :file, :total, :valid, cast(:rows as jsonb),
                        :now, :actor, :now, :actor, 0)""")
                .param("id", id).param("tenant", TenantContext.require()).param("kind", kind)
                .param("file", safeName).param("total", checked.size())
                .param("valid", (int) checked.stream().filter(r -> "create".equals(r.action())).count())
                .param("rows", json.write(checked)).param("now", now).param("actor", actor).update();
        return get(id);
    }

    private static Map<Integer, String> header(String kind, List<String> cells) {
        Map<String, List<String>> known = COLUMNS.get(kind);
        Map<Integer, String> header = new LinkedHashMap<>();
        for (int i = 0; i < cells.size(); i++) {
            String h = cells.get(i).trim().toLowerCase(Locale.ROOT);
            for (var col : known.entrySet()) {
                if (col.getValue().contains(h) && !header.containsValue(col.getKey())) {
                    header.put(i, col.getKey());
                }
            }
        }
        for (String req : REQUIRED.get(kind)) {
            if (!header.containsValue(req)) {
                throw ApiException.badRequest("missing_column", "Нет обязательной колонки «" + req + "»");
            }
        }
        return header;
    }

    /** Проверка строк по текущему состоянию справочников. Вызывается и при предпросмотре, и перед применением. */
    List<Row> validate(String kind, List<Row> rows) {
        return switch (kind) {
            case "users" -> validateUsers(rows);
            case "groups" -> validateGroups(rows);
            case "departments" -> validateDepartments(rows);
            default -> throw ApiException.badRequest("invalid_kind", "Неизвестный вид импорта");
        };
    }

    private List<Row> validateUsers(List<Row> rows) {
        Set<String> logins = new HashSet<>();
        Set<String> emails = new HashSet<>();
        Map<String, Boolean> groupExists = new HashMap<>();
        List<Row> out = new ArrayList<>();
        for (Row r : rows) {
            Map<String, String> v = r.values();
            List<String> errors = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            String login = v.getOrDefault("login", "");
            String name = v.getOrDefault("display_name", "");
            String email = v.getOrDefault("email", "");
            if (!LOGIN.matcher(login).matches()) {
                errors.add("Логин обязателен, без пробелов, до 255 символов");
            } else if (!logins.add(login.toLowerCase(Locale.ROOT))) {
                errors.add("Логин повторяется в файле");
            }
            if (name.isEmpty() || name.length() > 255) {
                errors.add("Имя обязательно, до 255 символов");
            }
            if (!email.isEmpty()) {
                if (!EMAIL.matcher(email).matches() || email.length() > 320) {
                    errors.add("Некорректная почта");
                } else if (!emails.add(email.toLowerCase(Locale.ROOT))) {
                    errors.add("Почта повторяется в файле");
                } else if (directory.findUserByLogin(email).isPresent()
                        && !directory.findUserByLogin(email).equals(directory.findUserByLogin(login))) {
                    errors.add("Почта уже занята другим пользователем");
                }
            }
            String locale = v.getOrDefault("locale", "");
            if (!locale.isEmpty() && !List.of("ru", "en").contains(locale)) {
                errors.add("Язык: ru или en");
            }
            String tz = v.getOrDefault("timezone", "");
            if (!tz.isEmpty()) {
                try {
                    ZoneId.of(tz);
                } catch (DateTimeException e) {
                    errors.add("Неизвестный часовой пояс «" + tz + "»");
                }
            }
            String dept = v.getOrDefault("department", "");
            if (!dept.isEmpty() && orgUnits.findByName("department", dept).isEmpty()) {
                warnings.add("Подразделение «" + dept + "» будет создано");
            }
            for (String g : splitList(v.getOrDefault("groups", ""))) {
                boolean exists = groupExists.computeIfAbsent(g.toLowerCase(Locale.ROOT),
                        k -> directory.findGroupByName(g).isPresent());
                if (!exists) {
                    errors.add("Группы «" + g + "» нет");
                }
            }
            String action = !errors.isEmpty() ? "error" : "create";
            if (errors.isEmpty() && directory.findUserByLogin(login).isPresent()) {
                action = "skip";
                warnings.add("Пользователь с таким логином уже есть, строка будет пропущена");
            }
            out.add(new Row(r.line(), v, action, errors, warnings));
        }
        return out;
    }

    private List<Row> validateGroups(List<Row> rows) {
        Set<String> names = new HashSet<>();
        List<Row> out = new ArrayList<>();
        for (Row r : rows) {
            Map<String, String> v = r.values();
            List<String> errors = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            String name = v.getOrDefault("name", "");
            if (name.isEmpty() || name.length() > 255) {
                errors.add("Название обязательно, до 255 символов");
            } else if (!names.add(name.toLowerCase(Locale.ROOT))) {
                errors.add("Название повторяется в файле");
            }
            String type = v.getOrDefault("type", "");
            if (!type.isEmpty() && !GROUP_TYPES.contains(type)) {
                errors.add("Тип: support, security, approval или other");
            }
            if (v.getOrDefault("description", "").length() > 2000) {
                errors.add("Описание до 2000 символов");
            }
            String action = !errors.isEmpty() ? "error" : "create";
            if (errors.isEmpty() && directory.findGroupByName(name).isPresent()) {
                action = "skip";
                warnings.add("Группа с таким названием уже есть, строка будет пропущена");
            }
            out.add(new Row(r.line(), v, action, errors, warnings));
        }
        return out;
    }

    private List<Row> validateDepartments(List<Row> rows) {
        Set<String> names = new HashSet<>();
        Set<String> codes = new HashSet<>();
        List<Row> out = new ArrayList<>();
        for (Row r : rows) {
            Map<String, String> v = r.values();
            List<String> errors = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            String name = v.getOrDefault("name", "");
            if (name.isEmpty() || name.length() > 255) {
                errors.add("Название обязательно, до 255 символов");
            }
            String code = v.getOrDefault("code", "");
            if (code.length() > 64) {
                errors.add("Код до 64 символов");
            } else if (!code.isEmpty() && !codes.add(code.toLowerCase(Locale.ROOT))) {
                errors.add("Код повторяется в файле");
            }
            String parent = v.getOrDefault("parent", "");
            if (!parent.isEmpty() && !names.contains(parent.toLowerCase(Locale.ROOT))
                    && orgUnits.findByName("department", parent).isEmpty()) {
                errors.add("Вышестоящего подразделения «" + parent + "» нет ни в файле выше, ни в справочнике");
            }
            String action = !errors.isEmpty() ? "error" : "create";
            if (errors.isEmpty() && orgUnits.findByName("department", name).isPresent()) {
                action = "skip";
                warnings.add("Подразделение уже есть, строка будет пропущена");
            }
            if (!name.isEmpty()) {
                names.add(name.toLowerCase(Locale.ROOT));
            }
            out.add(new Row(r.line(), v, action, errors, warnings));
        }
        return out;
    }

    // ---------------------------------------------------------------- применение и откат

    /**
     * Применяет импорт одной транзакцией: ошибка любой строки отменяет всё. Строки перепроверяются, потому что
     * справочники могли измениться после предпросмотра.
     *
     * @param skipInvalid true — строки с ошибками пропускаются, false — при ошибках импорт не применяется
     */
    @Transactional
    Job apply(UUID id, boolean skipInvalid) {
        Stored job = lock(id);
        if (!"preview".equals(job.status())) {
            throw ApiException.conflict("not_in_preview", "Импорт уже применён или отменён");
        }
        List<Row> rows = validate(job.kind(), job.rows());
        if (!skipInvalid && rows.stream().anyMatch(r -> "error".equals(r.action()))) {
            throw ApiException.conflict("has_errors", "В файле есть строки с ошибками: исправьте или пропустите их");
        }
        List<Ref> created = new ArrayList<>();
        for (Row r : rows) {
            if ("create".equals(r.action())) {
                applyRow(job.kind(), r, created);
            }
        }
        jdbc.sql("""
                update import_job set status = 'applied', rows = cast(:rows as jsonb),
                       created_refs = cast(:refs as jsonb), applied_at = now(), updated_at = now(),
                       updated_by = :actor, version = version + 1
                 where id = :id""")
                .param("rows", json.write(rows)).param("refs", json.write(created))
                .param("actor", CurrentActor.userId().orElse(null)).param("id", id).update();
        Job result = get(id);
        versions.record("admin.import", id.toString(), label(job), "import", summary(result), null);
        return result;
    }

    private void applyRow(String kind, Row r, List<Ref> created) {
        Map<String, String> v = r.values();
        switch (kind) {
            case "users" -> {
                UUID dept = null;
                String deptName = v.getOrDefault("department", "");
                if (!deptName.isEmpty()) {
                    List<UUID> newUnits = new ArrayList<>();
                    dept = orgUnits.findOrCreateDepartment(deptName, newUnits).getId();
                    newUnits.forEach(u -> created.add(new Ref("department", u, null)));
                }
                UUID user = directory.createUser(new DirectoryAdmin.NewUser(v.get("login"), blankToNull(v.get("email")),
                        v.get("display_name"), blankToNull(v.get("locale")), blankToNull(v.get("timezone")), null,
                        dept));
                created.add(new Ref("user", user, null));
                for (String g : splitList(v.getOrDefault("groups", ""))) {
                    UUID group = directory.findGroupByName(g)
                            .orElseThrow(() -> ApiException.conflict("group_missing", "Группы «" + g + "» нет"));
                    directory.addMember(group, user, false);
                    created.add(new Ref("membership", user, group));
                }
            }
            case "groups" -> {
                String type = blankToNull(v.get("type"));
                UUID group = directory.createGroup(v.get("name"), type == null ? "support" : type,
                        v.getOrDefault("description", ""));
                created.add(new Ref("group", group, null));
            }
            case "departments" -> {
                String parentName = v.getOrDefault("parent", "");
                UUID parent = parentName.isEmpty() ? null
                        : orgUnits.findByName("department", parentName).map(OrgUnit::getId).orElse(null);
                OrgUnit saved = orgUnits.save(new OrgUnit("department", v.get("name"), blankToNull(v.get("code")),
                        parent));
                created.add(new Ref("department", saved.getId(), null));
            }
            default -> throw new IllegalStateException(kind);
        }
    }

    /**
     * Откат: удаляет созданное импортом в обратном порядке. Пользователь, который уже входил, блокируется,
     * а не удаляется; тогда подразделения из импорта остаются, чтобы его карточка не ссылалась в пустоту.
     */
    @Transactional
    Job rollback(UUID id) {
        Stored job = lock(id);
        if (!"applied".equals(job.status())) {
            throw ApiException.conflict("not_applied", "Откатить можно только применённый импорт");
        }
        List<Ref> refs = new ArrayList<>(job.created());
        java.util.Collections.reverse(refs);
        int removedUsers = 0;
        int blockedUsers = 0;
        int removedGroups = 0;
        int removedDepartments = 0;
        for (Ref ref : refs) {
            switch (ref.type()) {
                case "membership" -> directory.removeMember(ref.groupId(), ref.id());
                case "user" -> {
                    if (directory.removeImportedUser(ref.id())) {
                        removedUsers++;
                    } else {
                        blockedUsers++;
                    }
                }
                case "group" -> {
                    directory.removeImportedGroup(ref.id());
                    removedGroups++;
                }
                case "department" -> {
                    if (blockedUsers == 0) {
                        orgUnits.delete(ref.id());
                        removedDepartments++;
                    }
                }
                default -> {
                }
            }
        }
        Map<String, Integer> result = Map.of("removedUsers", removedUsers, "blockedUsers", blockedUsers,
                "removedGroups", removedGroups, "removedDepartments", removedDepartments);
        jdbc.sql("""
                update import_job set status = 'rolled_back', rolled_back_at = now(), updated_at = now(),
                       updated_by = :actor, version = version + 1
                 where id = :id""")
                .param("actor", CurrentActor.userId().orElse(null)).param("id", id).update();
        versions.record("admin.import", id.toString(), label(job), "rollback", result, null);
        Job after = get(id);
        return new Job(after.id(), after.kind(), after.status(), after.fileName(), after.totalRows(),
                after.validRows(), after.errorRows(), after.skipRows(), after.rows(), after.columns(),
                after.appliedAt(), after.rolledBackAt(), after.createdBy(), after.createdAt(), result);
    }

    // ---------------------------------------------------------------- чтение

    @Transactional(readOnly = true)
    Job get(UUID id) {
        Stored s = find(id).orElseThrow(() -> ApiException.notFound("Импорт"));
        int errors = (int) s.rows().stream().filter(r -> "error".equals(r.action())).count();
        int skips = (int) s.rows().stream().filter(r -> "skip".equals(r.action())).count();
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Ref r : s.created()) {
            result.merge(r.type(), 1, Integer::sum);
        }
        return new Job(s.id(), s.kind(), s.status(), s.fileName(), s.rows().size(),
                (int) s.rows().stream().filter(r -> "create".equals(r.action())).count(), errors, skips, s.rows(),
                List.copyOf(COLUMNS.get(s.kind()).keySet()), s.appliedAt(), s.rolledBackAt(), s.createdBy(),
                s.createdAt(), result);
    }

    @Transactional(readOnly = true)
    CursorPage<JobSummary> list(PageParams page) {
        KeysetCursor after = page.keyset();
        List<JobSummary> rows = jdbc.sql("""
                select id, kind, status, file_name, total_rows, valid_rows, created_by, created_at, applied_at,
                       rolled_back_at
                  from import_job
                 where cast(:afterAt as timestamptz) is null
                    or (created_at, id) < (cast(:afterAt as timestamptz), cast(:afterId as uuid))
                 order by created_at desc, id desc
                 limit :limit""")
                .param("afterAt", after == null ? null : AdminJson.ts(Instant.parse(after.sortValue())))
                .param("afterId", after == null ? null : after.id())
                .param("limit", page.limit() + 1)
                .query((rs, n) -> new JobSummary(AdminJson.uuid(rs, "id"), rs.getString("kind"),
                        rs.getString("status"), rs.getString("file_name"), rs.getInt("total_rows"),
                        rs.getInt("valid_rows"), AdminJson.uuid(rs, "created_by"), AdminJson.instant(rs, "created_at"),
                        AdminJson.instant(rs, "applied_at"), AdminJson.instant(rs, "rolled_back_at")))
                .list();
        return CursorPage.of(rows, page.limit(), j -> new KeysetCursor(j.createdAt().toString(), j.id()));
    }

    private record Stored(UUID id, String kind, String status, String fileName, List<Row> rows, List<Ref> created,
                          Instant appliedAt, Instant rolledBackAt, UUID createdBy, Instant createdAt) {
    }

    private Optional<Stored> find(UUID id) {
        return query("select *, rows::text as rows_json, created_refs::text as refs_json from import_job where id = :id",
                id);
    }

    /** Блокировка строки: два одновременных «Применить» не создадут записи дважды. */
    private Stored lock(UUID id) {
        return query("""
                select *, rows::text as rows_json, created_refs::text as refs_json from import_job
                 where id = :id for update""", id).orElseThrow(() -> ApiException.notFound("Импорт"));
    }

    private Optional<Stored> query(String sql, UUID id) {
        return jdbc.sql(sql).param("id", id)
                .query((rs, n) -> new Stored(AdminJson.uuid(rs, "id"), rs.getString("kind"), rs.getString("status"),
                        rs.getString("file_name"), json.read(rs.getString("rows_json"), ROWS),
                        json.read(rs.getString("refs_json"), REFS), AdminJson.instant(rs, "applied_at"),
                        AdminJson.instant(rs, "rolled_back_at"), AdminJson.uuid(rs, "created_by"),
                        AdminJson.instant(rs, "created_at")))
                .optional();
    }

    private static Map<String, Object> summary(Job j) {
        return Map.of("kind", j.kind(), "fileName", j.fileName(), "totalRows", j.totalRows(),
                "validRows", j.validRows(), "created", j.result());
    }

    private static String label(Stored job) {
        String what = switch (job.kind()) {
            case "users" -> "Импорт пользователей";
            case "groups" -> "Импорт групп";
            default -> "Импорт подразделений";
        };
        return job.fileName().isEmpty() ? what : what + ": " + job.fileName();
    }

    static List<String> splitList(String s) {
        if (s == null || s.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : s.split("[|,]")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Map<String, List<String>> columns(Object... pairs) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            @SuppressWarnings("unchecked")
            List<String> aliases = (List<String>) pairs[i + 1];
            m.put((String) pairs[i], aliases);
        }
        return m;
    }
}
