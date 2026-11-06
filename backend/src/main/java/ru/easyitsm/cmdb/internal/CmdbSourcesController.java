package ru.easyitsm.cmdb.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
import ru.easyitsm.identity.api.RequiresRecentAuth;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.ETags;
import ru.easyitsm.platform.web.PageParams;

/**
 * REST загрузки и сверки CMDB (CFG-04, CFG-05): источники данных, пакеты, конфликты, слияние дубликатов.
 * Источники заводят CFG и ADM; пакеты присылает пользователь с ролью CFG или агент с токеном источника
 * ({@link CmdbIngestController}).
 */
@RestController
@RequestMapping("/api/v1/cmdb")
class CmdbSourcesController {

    private final SourceService sources;
    private final IngestService ingest;
    private final ConflictService conflicts;
    private final MergeService merges;

    CmdbSourcesController(SourceService sources, IngestService ingest, ConflictService conflicts,
                          MergeService merges) {
        this.sources = sources;
        this.ingest = ingest;
        this.conflicts = conflicts;
        this.merges = merges;
    }

    @GetMapping("/sources")
    @PreAuthorize(CmdbController.READ)
    List<SourceService.Source> sources() {
        return sources.list();
    }

    @PostMapping("/sources")
    @PreAuthorize(CmdbController.CONFIGURE)
    ResponseEntity<SourceService.Source> createSource(@Valid @RequestBody SourceService.CreateSource cmd) {
        SourceService.Source s = sources.create(cmd);
        return ResponseEntity.created(URI.create("/api/v1/cmdb/sources/" + s.code()))
                .eTag(ETags.of(s.version())).body(s);
    }

    @GetMapping("/sources/{code}")
    @PreAuthorize(CmdbController.READ)
    ResponseEntity<SourceService.Source> source(@PathVariable String code) {
        SourceService.Source s = sources.get(code);
        return ResponseEntity.ok().eTag(ETags.of(s.version())).body(s);
    }

    @PatchMapping("/sources/{code}")
    @PreAuthorize(CmdbController.CONFIGURE)
    ResponseEntity<SourceService.Source> updateSource(
            @PathVariable String code,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody SourceService.UpdateSource cmd) {
        SourceService.Source s = sources.update(code, ifMatch, cmd);
        return ResponseEntity.ok().eTag(ETags.of(s.version())).body(s);
    }

    /**
     * Выпустить токен источника для агента обнаружения или коннектора (CFG-04). Показывается один раз, прежний
     * токен сразу перестаёт приниматься.
     */
    @PostMapping("/sources/{code}/token")
    @PreAuthorize(CmdbController.CONFIGURE)
    @RequiresRecentAuth
    SourceService.IssuedToken issueToken(@PathVariable String code) {
        return sources.issueToken(code);
    }

    @DeleteMapping("/sources/{code}/token")
    @PreAuthorize(CmdbController.CONFIGURE)
    @RequiresRecentAuth
    ResponseEntity<SourceService.Source> revokeToken(@PathVariable String code) {
        SourceService.Source s = sources.revokeToken(code);
        return ResponseEntity.ok().eTag(ETags.of(s.version())).body(s);
    }

    /** Пакет объектов источника: до 1000 объектов и 5 МБ, повтор с тем же batchId вернёт тот же результат. */
    @PostMapping("/sources/{code}/batches")
    @PreAuthorize(CmdbController.WRITE)
    IngestService.BatchResult ingest(@PathVariable String code, HttpServletRequest request,
                                     @Valid @RequestBody IngestService.BatchRequest batch) {
        if (request.getContentLengthLong() > IngestService.MAX_BYTES) {
            throw new CmdbProblem(HttpStatus.CONTENT_TOO_LARGE, "batch_too_large",
                    "Пакет больше 5 МБ: разбейте его на несколько");
        }
        return ingest.ingest(code, batch);
    }

    @GetMapping("/sources/{code}/batches")
    @PreAuthorize(CmdbController.READ)
    List<IngestService.BatchResult> batches(@PathVariable String code,
                                            @RequestParam(defaultValue = "20") int limit) {
        return ingest.recent(code, limit);
    }

    @GetMapping("/sources/{code}/batches/{batchId}")
    @PreAuthorize(CmdbController.READ)
    IngestService.BatchResult batch(@PathVariable String code, @PathVariable String batchId) {
        return ingest.get(code, batchId);
    }

    /** Экран «Сверка»: по умолчанию открытые конфликты, новые первыми. */
    @GetMapping("/conflicts")
    @PreAuthorize(CmdbController.READ)
    CursorPage<ConflictService.ConflictView> conflicts(@RequestParam(required = false) String status,
                                                       @RequestParam(required = false) String kind,
                                                       @RequestParam(required = false) String source,
                                                       @RequestParam(required = false) UUID ciId,
                                                       @RequestParam(required = false) String cursor,
                                                       @RequestParam(required = false) Integer limit) {
        return conflicts.list(status, kind, source, ciId, PageParams.of(cursor, limit));
    }

    @PostMapping("/conflicts/{id}/resolve")
    @PreAuthorize(CmdbController.WRITE)
    ConflictService.ConflictView resolve(@PathVariable UUID id, @Valid @RequestBody ConflictService.Resolve cmd) {
        return conflicts.resolve(id, cmd);
    }

    /** Слить дубликат {@code mergedId} в эту КЕ (CFG-07, экран «Качество данных»). */
    @PostMapping("/cis/{id}/merge")
    @PreAuthorize(CmdbController.WRITE)
    ResponseEntity<CiView> merge(@PathVariable UUID id, @RequestBody MergeService.MergeRequest cmd) {
        CiView view = merges.merge(id, cmd.mergedId());
        return ResponseEntity.ok().eTag(ETags.of(view.version())).body(view);
    }
}
