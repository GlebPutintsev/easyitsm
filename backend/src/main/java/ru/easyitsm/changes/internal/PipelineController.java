package ru.easyitsm.changes.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.changes.api.ChangeState;
import ru.easyitsm.changes.api.ChangeType;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.ETags;
import ru.easyitsm.tickets.api.Channel;

/**
 * Изменения из CI/CD (CHG-10). Конвейер под сервисной учётной записью (SVC) создаёт изменение по шаблону с ключом
 * {@code pipelineKey}, перед развёртыванием спрашивает {@code deploy-check} и сообщает о начале и итоге работ.
 * Стандартный шаблон с окном сразу планирует изменение, остальные идут обычным маршрутом.
 */
@RestController
class PipelineController {

    private final ChangeService service;
    private final ChangeStore store;
    private final TemplateStore templates;
    private final FreezeStore freezes;

    PipelineController(ChangeService service, ChangeStore store, TemplateStore templates, FreezeStore freezes) {
        this.service = service;
        this.store = store;
        this.templates = templates;
        this.freezes = freezes;
    }

    record PipelineChange(
            @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9._-]{1,63}$") String templateKey,
            @NotBlank @Size(max = 255) String title,
            @Size(max = 20_000) String description,
            @Size(max = 200) String system,
            @Size(max = 500) String repository,
            @Size(max = 200) String ref,
            @Size(max = 100) String environment,
            @Size(max = 1000) @Pattern(regexp = "^(https?://.*)?$") String pipelineUrl,
            Instant windowStart,
            Instant windowEnd,
            @Size(max = 100) List<UUID> ciIds,
            @Size(max = 100) List<UUID> serviceIds,
            @Size(max = 4000) String acknowledgeConflicts) {
    }

    /**
     * @param allowed можно развёртывать сейчас
     * @param reason  код причины запрета: {@code state}, {@code window}, {@code freeze}
     */
    record DeployCheck(UUID changeId, String number, String state, boolean allowed, String reason, String message,
                       Instant windowStart, Instant windowEnd) {
    }

    record Finished(@NotBlank @Pattern(regexp = "successful|partial|rolled_back") String outcome,
                    @Size(max = 20_000) String conclusion) {
    }

    @PostMapping("/api/v1/changes/pipeline")
    @PreAuthorize("hasAnyRole('SVC', 'CHG')")
    @Transactional
    ResponseEntity<ChangeDtos.ChangeView> create(@Valid @RequestBody PipelineChange req) {
        Me me = Me.current();
        TemplateStore.Template tpl = templates.byPipelineKey(req.templateKey())
                .orElseThrow(() -> ChangeProblem.field("templateKey", "Нет активного шаблона с таким ключом"));
        ChangeRow c = service.createInternal(ChangeType.fromCode(tpl.changeType()), req.title(), req.description(),
                me.userId(), tpl.id(), null, null, req.ciIds(), req.serviceIds(), Channel.API, me.userId());
        Map<String, Object> pipeline = new LinkedHashMap<>();
        put(pipeline, "system", req.system());
        put(pipeline, "repository", req.repository());
        put(pipeline, "ref", req.ref());
        put(pipeline, "environment", req.environment());
        put(pipeline, "pipelineUrl", req.pipelineUrl());
        c.pipeline = pipeline;
        if (req.windowStart() != null && req.windowEnd() != null) {
            if (!req.windowEnd().isAfter(req.windowStart())) {
                throw ChangeProblem.field("windowEnd", "Конец окна должен быть позже начала");
            }
            c.windowStart = req.windowStart();
            c.windowEnd = req.windowEnd();
        }
        service.save(c);
        ChangeDtos.ChangeView view;
        if (c.type == ChangeType.STANDARD && c.windowStart != null) {
            view = service.submit(c.id, ETags.of(c.version), new ChangeDtos.SendToApproval(req.acknowledgeConflicts(), null), me);
        } else {
            view = service.get(c.id, me);
        }
        return ResponseEntity.created(URI.create("/api/v1/changes/" + c.id)).eTag(ETags.of(view.version())).body(view);
    }

    /** Проверка перед развёртыванием: изменение запланировано или во внедрении, сейчас окно, нет заморозки. */
    @GetMapping("/api/v1/changes/{id}/deploy-check")
    @PreAuthorize("hasAnyRole('SVC', 'CHG', 'AGT', 'LEAD', 'MIM')")
    @Transactional(readOnly = true)
    DeployCheck check(@PathVariable UUID id) {
        Me me = Me.current();
        // Учётная запись конвейера проверяет любое изменение арендатора: ответ — только статус и окно.
        ChangeRow c = me.has("SVC")
                ? store.find(id).orElseThrow(() -> ApiException.notFound("Изменение"))
                : service.readable(id, me);
        Instant now = Instant.now();
        if (c.state != ChangeState.SCHEDULED && c.state != ChangeState.IMPLEMENTATION) {
            return deny(c, "state", "Изменение в статусе «" + ChangeService.stateLabel(c.state) + "»");
        }
        if (c.type != ChangeType.EMERGENCY) {
            if (c.windowStart == null || now.isBefore(c.windowStart) || !now.isBefore(c.windowEnd)) {
                return deny(c, "window", "Сейчас вне окна работ");
            }
            boolean frozen = freezes.overlapping(now, now.plusSeconds(1)).stream()
                    .anyMatch(f -> f.covers(c.ciIds, c.serviceIds));
            if (frozen) {
                return deny(c, "freeze", "Действует заморозка изменений");
            }
        }
        return new DeployCheck(c.id, c.number, c.state.code(), true, null, null, c.windowStart, c.windowEnd);
    }

    @PostMapping("/api/v1/changes/{id}/pipeline/started")
    @PreAuthorize("hasAnyRole('SVC', 'CHG')")
    ResponseEntity<ChangeDtos.ChangeView> started(@PathVariable UUID id) {
        Me me = Me.current();
        ChangeRow c = store.find(id).orElseThrow(() -> ApiException.notFound("Изменение"));
        ChangeDtos.ChangeView v = c.state == ChangeState.IMPLEMENTATION ? service.get(id, me)
                : service.start(id, ETags.of(c.version), null, me);
        return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
    }

    @PostMapping("/api/v1/changes/{id}/pipeline/finished")
    @PreAuthorize("hasAnyRole('SVC', 'CHG')")
    ResponseEntity<ChangeDtos.ChangeView> finished(@PathVariable UUID id, @Valid @RequestBody Finished req) {
        Me me = Me.current();
        ChangeRow c = store.find(id).orElseThrow(() -> ApiException.notFound("Изменение"));
        ChangeDtos.ChangeView v = service.complete(id, ETags.of(c.version),
                new ChangeDtos.Complete(req.outcome(), null, null, req.conclusion()), me);
        return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
    }

    private static DeployCheck deny(ChangeRow c, String reason, String message) {
        return new DeployCheck(c.id, c.number, c.state.code(), false, reason, message, c.windowStart, c.windowEnd);
    }

    private static void put(Map<String, Object> m, String k, String v) {
        if (v != null && !v.isBlank()) {
            m.put(k, v.strip());
        }
    }
}
