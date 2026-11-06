package ru.easyitsm.cmdb.internal;

import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.cmdb.api.CiRef;
import ru.easyitsm.cmdb.api.ImpactReport;
import ru.easyitsm.cmdb.api.ImpactedCi;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.ETags;
import ru.easyitsm.platform.web.PageParams;
import ru.easyitsm.tickets.api.TicketQueries;
import ru.easyitsm.tickets.api.TicketSnapshot;

/**
 * REST CMDB (раздел 8.2 ТЗ). Читают поддержка, администратор и аудитор; меняют КЕ и связи менеджеры
 * конфигураций (CFG), как в матрице 3.2. Чужая или отсутствующая КЕ — 404.
 */
@RestController
@RequestMapping("/api/v1/cmdb")
class CmdbController {

    static final String READ = "hasAnyRole('AGT', 'LEAD', 'MIM', 'CHG', 'PRB', 'CFG', 'AST', 'ADM', 'AUD')";
    static final String WRITE = "hasRole('CFG')";
    static final String CONFIGURE = "hasAnyRole('CFG', 'ADM')";

    record ClassView(UUID id, UUID parentId, String code, String name, String layer, boolean isAbstract,
                     boolean system, boolean active, List<AttributeDef> attributes, List<AttributeDef> ownAttributes,
                     List<List<String>> identificationRules, long version) {
    }

    record CiRefView(UUID id, String name, String classCode, String className, String layer, String status,
                     String environment, String criticality, UUID ownerGroupId) {

        static CiRefView of(CiRef ci) {
            return new CiRefView(ci.id(), ci.name(), ci.classCode(), ci.className(), ci.layer().code(),
                    ci.status().code(), ci.environment(), ci.criticality().code(), ci.ownerGroupId());
        }
    }

    record ImpactedView(CiRefView ci, int depth, List<UUID> path) {

        static ImpactedView of(ImpactedCi i) {
            return new ImpactedView(CiRefView.of(i.ci()), i.depth(), i.path());
        }
    }

    record TicketView(UUID id, String number, String title, String type, String priority, String statusCategory,
                      UUID ciId, UUID serviceId) {
    }

    /**
     * @param hiddenTickets открытые заявки, которые пользователь не видит по правилам доступа: только число
     */
    record ImpactView(List<CiRefView> roots, List<ImpactedView> impacted, List<ImpactedView> services,
                      List<TicketView> tickets, int hiddenTickets, int depth, boolean truncated) {
    }

    private final CiService cis;
    private final RelationService relations;
    private final CiClasses classes;
    private final ImpactService impact;
    private final TicketQueries tickets;
    private final CiHistoryService history;
    private final MapService maps;
    private final ClassAdminService admin;
    private final QualityService quality;

    CmdbController(CiService cis, RelationService relations, CiClasses classes, ImpactService impact,
                   TicketQueries tickets, CiHistoryService history, MapService maps, ClassAdminService admin,
                   QualityService quality) {
        this.cis = cis;
        this.relations = relations;
        this.classes = classes;
        this.impact = impact;
        this.tickets = tickets;
        this.history = history;
        this.maps = maps;
        this.admin = admin;
        this.quality = quality;
    }

    @GetMapping("/classes")
    @PreAuthorize(READ)
    List<ClassView> classes() {
        ClassTree tree = classes.tree();
        return tree.all().stream().map(c -> classView(tree, c)).toList();
    }

    @PostMapping("/classes")
    @PreAuthorize(CONFIGURE)
    ResponseEntity<ClassView> createClass(@Valid @RequestBody ClassAdminService.CreateClass cmd) {
        admin.createClass(cmd);
        ClassView view = classView(cmd.code());
        return ResponseEntity.created(URI.create("/api/v1/cmdb/classes/" + view.code()))
                .eTag(ETags.of(view.version())).body(view);
    }

    @PatchMapping("/classes/{code}")
    @PreAuthorize(CONFIGURE)
    ResponseEntity<ClassView> updateClass(@PathVariable String code,
                                          @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                          @Valid @RequestBody ClassAdminService.UpdateClass cmd) {
        admin.updateClass(code, ifMatch, cmd);
        ClassView view = classView(code);
        return ResponseEntity.ok().eTag(ETags.of(view.version())).body(view);
    }

    @PostMapping("/relation-types")
    @PreAuthorize(CONFIGURE)
    ResponseEntity<CiClasses.RelationType> createRelationType(
            @Valid @RequestBody ClassAdminService.CreateRelationType cmd) {
        return ResponseEntity.status(201).body(admin.createRelationType(cmd));
    }

    @PatchMapping("/relation-types/{code}")
    @PreAuthorize(CONFIGURE)
    CiClasses.RelationType updateRelationType(@PathVariable String code,
                                              @Valid @RequestBody ClassAdminService.UpdateRelationType cmd) {
        return admin.updateRelationType(code, cmd);
    }

    @GetMapping("/quality")
    @PreAuthorize(READ)
    QualityService.Summary quality() {
        return quality.summary();
    }

    /** Список КЕ с замечанием: {@code incomplete}, {@code stale}, {@code orphan} — до 200. */
    @GetMapping("/quality/issues")
    @PreAuthorize(READ)
    List<CiRefView> qualityIssues(@RequestParam String kind) {
        return quality.issues(kind);
    }

    @GetMapping("/quality/duplicates")
    @PreAuthorize(READ)
    List<QualityService.DuplicateGroup> duplicates() {
        return quality.duplicateGroups();
    }

    @GetMapping("/relation-types")
    @PreAuthorize(READ)
    List<CiClasses.RelationType> relationTypes() {
        return classes.relationTypes();
    }

    @GetMapping("/cis")
    @PreAuthorize(READ)
    CursorPage<CiView> list(@RequestParam(required = false) String q,
                            @RequestParam(required = false) List<String> classCode,
                            @RequestParam(required = false) List<String> layer,
                            @RequestParam(required = false) List<String> status,
                            @RequestParam(required = false) List<String> environment,
                            @RequestParam(required = false) List<String> criticality,
                            @RequestParam(required = false) UUID ownerGroupId,
                            @RequestParam(defaultValue = "false") boolean includeRetired,
                            @RequestParam(required = false) String cursor,
                            @RequestParam(required = false) Integer limit) {
        var filter = new CiService.Filter(q, CiService.split(classCode), CiService.split(layer),
                CiService.split(status), CiService.split(environment), CiService.split(criticality), ownerGroupId,
                includeRetired);
        return cis.list(filter, PageParams.of(cursor, limit));
    }

    @PostMapping("/cis")
    @PreAuthorize(WRITE)
    ResponseEntity<CiView> create(@Valid @RequestBody CiCommands.Create cmd) {
        CiView view = cis.create(cmd);
        return ResponseEntity.created(URI.create("/api/v1/cmdb/cis/" + view.id()))
                .eTag(ETags.of(view.version())).body(view);
    }

    /** Карточка; с {@code at} — состояние на момент времени (CFG-06), без ETag: изменить его нельзя. */
    @GetMapping("/cis/{id}")
    @PreAuthorize(READ)
    ResponseEntity<CiView> get(@PathVariable UUID id, @RequestParam(required = false) Instant at) {
        if (at != null) {
            return ResponseEntity.ok(history.asOf(id, at));
        }
        CiView view = cis.get(id);
        return ResponseEntity.ok().eTag(ETags.of(view.version())).body(view);
    }

    @PatchMapping("/cis/{id}")
    @PreAuthorize(WRITE)
    ResponseEntity<CiView> update(@PathVariable UUID id,
                                  @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                  @RequestBody Map<String, Object> body) {
        CiView view = cis.update(id, ifMatch, new CiCommands.Update(body));
        return ResponseEntity.ok().eTag(ETags.of(view.version())).body(view);
    }

    @GetMapping("/cis/{id}/relations")
    @PreAuthorize(READ)
    List<RelationService.RelationView> relations(@PathVariable UUID id,
                                                 @RequestParam(required = false) Instant at) {
        return relations.list(id, at);
    }

    @GetMapping("/cis/{id}/history")
    @PreAuthorize(READ)
    CursorPage<CiHistoryService.Entry> history(@PathVariable UUID id, @RequestParam(required = false) String cursor,
                                               @RequestParam(required = false) Integer limit) {
        return history.history(id, PageParams.of(cursor, limit));
    }

    @GetMapping("/cis/{id}/map")
    @PreAuthorize(READ)
    MapService.CiMap map(@PathVariable UUID id, @RequestParam(defaultValue = "2") int depth,
                         @RequestParam(required = false) List<String> type) {
        return maps.map(id, depth, CiService.split(type));
    }

    @PostMapping("/cis/{id}/relations")
    @PreAuthorize(WRITE)
    ResponseEntity<RelationService.RelationView> addRelation(@PathVariable UUID id,
                                                             @Valid @RequestBody CiCommands.AddRelation cmd) {
        return ResponseEntity.status(201).body(relations.add(id, cmd));
    }

    /** Снятие связи: запись остаётся в истории с датой окончания, поэтому повторный вход не нужен. */
    @DeleteMapping("/relations/{relationId}")
    @PreAuthorize(WRITE)
    ResponseEntity<Void> removeRelation(@PathVariable UUID relationId) {
        relations.remove(relationId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/cis/{id}/impact")
    @PreAuthorize(READ)
    ImpactView impact(@PathVariable UUID id, @RequestParam(defaultValue = "3") int depth) {
        cis.require(id);
        ImpactReport report = impact.analyze(List.of(id), depth);
        List<TicketView> visible = report.openTicketIds().stream()
                .map(tickets::findVisible).flatMap(Optional::stream)
                .map(CmdbController::ticket).toList();
        return new ImpactView(report.roots().stream().map(CiRefView::of).toList(),
                report.impacted().stream().map(ImpactedView::of).toList(),
                report.services().stream().map(ImpactedView::of).toList(), visible,
                report.openTicketIds().size() - visible.size(), report.depth(), report.truncated());
    }

    private ClassView classView(String code) {
        ClassTree tree = classes.tree();
        return classView(tree, tree.byCode(code).orElseThrow());
    }

    private static ClassView classView(ClassTree tree, ClassTree.ClassInfo c) {
        return new ClassView(c.id(), c.parentId(), c.code(), c.name(), c.layer().code(), c.isAbstract(), c.system(),
                c.active(), tree.effectiveAttributes(c), c.attributes(), tree.effectiveRules(c), c.version());
    }

    private static TicketView ticket(TicketSnapshot t) {
        return new TicketView(t.id(), t.number(), t.title(), t.type().code(),
                t.priority().code(), t.statusCategory().code(), t.ciId(), t.serviceId());
    }
}
