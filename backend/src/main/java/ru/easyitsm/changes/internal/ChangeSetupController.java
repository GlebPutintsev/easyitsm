package ru.easyitsm.changes.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.admin.api.ConfigHistory;
import ru.easyitsm.identity.api.RequiresRecentAuth;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.ETags;

/**
 * Настройка изменений: шаблоны (CHG-08), заморозки (CHG-05) и маршруты согласования (CHG-04, CHG-12). Ведут
 * менеджер изменений и администратор; шаблоны и заморозки читает вся поддержка. Версии пишутся в журнал
 * «Управления».
 */
@RestController
class ChangeSetupController {

    private static final String MANAGERS = "hasAnyRole('CHG', 'ADM')";

    private final TemplateStore templates;
    private final FreezeStore freezes;
    private final ChangeSettings settings;
    private final ConfigHistory history;

    ChangeSetupController(TemplateStore templates, FreezeStore freezes, ChangeSettings settings, ConfigHistory history) {
        this.templates = templates;
        this.freezes = freezes;
        this.settings = settings;
        this.history = history;
    }

    // ---------------------------------------------------------------- шаблоны

    record TemplateInput(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 4000) String description,
            @NotNull @Pattern(regexp = "standard|normal|emergency") String changeType,
            Boolean preapproved,
            @Pattern(regexp = "low|medium|high") String riskLevel,
            @Pattern(regexp = "^[a-z0-9][a-z0-9._-]{1,63}$") String pipelineKey,
            Map<String, Object> content,
            @Size(max = 300) List<ChangeDtos.@Valid StepInput> steps,
            @Size(max = 100) List<UUID> ciIds,
            @Size(max = 100) List<UUID> serviceIds,
            Boolean active) {
    }

    @GetMapping("/api/v1/change-templates")
    @PreAuthorize(ChangeController.WORKERS + " or hasRole('ADM')")
    @Transactional(readOnly = true)
    List<TemplateStore.Template> templates(@RequestParam(defaultValue = "true") boolean active) {
        return templates.list(active);
    }

    @GetMapping("/api/v1/change-templates/{id}")
    @PreAuthorize(ChangeController.WORKERS + " or hasRole('ADM')")
    @Transactional(readOnly = true)
    ResponseEntity<TemplateStore.Template> template(@PathVariable UUID id) {
        TemplateStore.Template t = templates.find(id).orElseThrow(() -> ApiException.notFound("Шаблон"));
        return ResponseEntity.ok().eTag(ETags.of(t.version())).body(t);
    }

    @PostMapping("/api/v1/change-templates")
    @PreAuthorize(MANAGERS)
    @Transactional
    ResponseEntity<TemplateStore.Template> createTemplate(@Valid @RequestBody TemplateInput in) {
        UUID id = templates.insert(template(null, in, 0));
        TemplateStore.Template t = templates.find(id).orElseThrow();
        history.record("changes.template", id.toString(), t.name(), "create", t, null);
        return ResponseEntity.created(URI.create("/api/v1/change-templates/" + id)).eTag(ETags.of(t.version())).body(t);
    }

    @PatchMapping("/api/v1/change-templates/{id}")
    @PreAuthorize(MANAGERS)
    @Transactional
    ResponseEntity<TemplateStore.Template> updateTemplate(@PathVariable UUID id,
                                                          @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                          @Valid @RequestBody TemplateInput in) {
        TemplateStore.Template current = templates.find(id).orElseThrow(() -> ApiException.notFound("Шаблон"));
        ETags.requireMatch(ifMatch, current.version());
        if (!templates.update(id, template(id, in, current.version()), current.version())) {
            throw ApiException.conflict("version_conflict", "Шаблон изменён другим пользователем. Обновите и повторите");
        }
        TemplateStore.Template t = templates.find(id).orElseThrow();
        history.record("changes.template", id.toString(), t.name(), "update", t, null);
        return ResponseEntity.ok().eTag(ETags.of(t.version())).body(t);
    }

    private static TemplateStore.Template template(UUID id, TemplateInput in, long version) {
        if (Boolean.TRUE.equals(in.preapproved()) && !"standard".equals(in.changeType())) {
            throw ChangeProblem.field("preapproved", "Предварительно утверждается только стандартное изменение");
        }
        if ("standard".equals(in.changeType()) && !Boolean.TRUE.equals(in.preapproved())) {
            throw ChangeProblem.field("preapproved", "Стандартное изменение — это шаблон с предварительным утверждением");
        }
        List<Map<String, Object>> steps = in.steps() == null ? List.of() : in.steps().stream().map(s -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("phase", s.phase());
            m.put("title", s.title());
            m.put("description", s.description());
            m.put("ownerName", s.ownerName());
            m.put("team", s.team());
            m.put("contacts", s.contacts());
            m.put("durationMinutes", s.durationMinutes());
            m.put("upto", s.upto());
            m.put("interruption", s.interruption());
            m.put("checkCriteria", s.checkCriteria());
            m.put("rollbackRef", s.rollbackRef());
            return m;
        }).toList();
        Map<String, Object> content = new java.util.LinkedHashMap<>();
        if (in.content() != null) {
            for (String key : List.of("goal", "justification", "impact", "risks", "downtimeMinutes", "systemCriticality")) {
                Object v = in.content().get(key);
                if (v instanceof String s && s.length() > 20_000) {
                    throw ChangeProblem.field("content." + key, "Слишком длинный текст");
                }
                if (v != null) {
                    content.put(key, v);
                }
            }
        }
        return new TemplateStore.Template(id, in.name().strip(), in.description(), in.changeType(), Boolean.TRUE.equals(in.preapproved()),
                in.riskLevel() == null && Boolean.TRUE.equals(in.preapproved()) ? "low" : in.riskLevel(), in.pipelineKey(), content, steps,
                ChangeService.distinct(in.ciIds()), ChangeService.distinct(in.serviceIds()),
                in.active() == null || in.active(), null, version);
    }

    // ---------------------------------------------------------------- заморозки

    record FreezeInput(@NotBlank @Size(max = 200) String title, @Size(max = 4000) String reason,
                       @NotNull Instant startsAt, @NotNull Instant endsAt,
                       @NotNull @Pattern(regexp = "all|targeted") String scope,
                       @Size(max = 100) List<UUID> serviceIds, @Size(max = 100) List<UUID> ciIds) {
    }

    @GetMapping("/api/v1/change-freezes")
    @PreAuthorize(ChangeController.READERS + " or hasRole('ADM')")
    @Transactional(readOnly = true)
    List<ChangeDtos.FreezeView> freezes(@RequestParam(required = false) Instant from,
                                        @RequestParam(required = false) Instant to) {
        Instant start = from != null ? from : Instant.now().truncatedTo(ChronoUnit.DAYS);
        if (to == null) {
            return freezes.upcoming(start, 200).stream().map(ChangeDtos.FreezeView::of).toList();
        }
        return freezes.overlapping(start, to).stream().map(ChangeDtos.FreezeView::of).toList();
    }

    @PostMapping("/api/v1/change-freezes")
    @PreAuthorize(MANAGERS)
    @Transactional
    ResponseEntity<ChangeDtos.FreezeView> createFreeze(@Valid @RequestBody FreezeInput in) {
        UUID id = freezes.insert(freeze(in, 0));
        FreezeStore.Freeze f = freezes.find(id).orElseThrow();
        history.record("changes.freeze", id.toString(), f.title(), "create", ChangeDtos.FreezeView.of(f), null);
        return ResponseEntity.created(URI.create("/api/v1/change-freezes/" + id)).eTag(ETags.of(f.version()))
                .body(ChangeDtos.FreezeView.of(f));
    }

    @PatchMapping("/api/v1/change-freezes/{id}")
    @PreAuthorize(MANAGERS)
    @Transactional
    ResponseEntity<ChangeDtos.FreezeView> updateFreeze(@PathVariable UUID id,
                                                       @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                       @Valid @RequestBody FreezeInput in) {
        FreezeStore.Freeze current = freezes.find(id).orElseThrow(() -> ApiException.notFound("Период заморозки"));
        ETags.requireMatch(ifMatch, current.version());
        if (!freezes.update(id, freeze(in, current.version()), current.version())) {
            throw ApiException.conflict("version_conflict", "Период изменён другим пользователем. Обновите и повторите");
        }
        FreezeStore.Freeze f = freezes.find(id).orElseThrow();
        history.record("changes.freeze", id.toString(), f.title(), "update", ChangeDtos.FreezeView.of(f), null);
        return ResponseEntity.ok().eTag(ETags.of(f.version())).body(ChangeDtos.FreezeView.of(f));
    }

    @DeleteMapping("/api/v1/change-freezes/{id}")
    @PreAuthorize(MANAGERS)
    @RequiresRecentAuth
    @Transactional
    ResponseEntity<Void> deleteFreeze(@PathVariable UUID id) {
        FreezeStore.Freeze f = freezes.find(id).orElseThrow(() -> ApiException.notFound("Период заморозки"));
        freezes.delete(id);
        history.record("changes.freeze", id.toString(), f.title(), "delete", ChangeDtos.FreezeView.of(f), null);
        return ResponseEntity.noContent().build();
    }

    private static FreezeStore.Freeze freeze(FreezeInput in, long version) {
        if (!in.endsAt().isAfter(in.startsAt())) {
            throw ChangeProblem.field("endsAt", "Окончание должно быть позже начала");
        }
        List<UUID> services = ChangeService.distinct(in.serviceIds());
        List<UUID> cis = ChangeService.distinct(in.ciIds());
        if ("targeted".equals(in.scope()) && services.isEmpty() && cis.isEmpty()) {
            throw ChangeProblem.field("serviceIds", "Укажите услуги или КЕ, на которые действует заморозка");
        }
        return new FreezeStore.Freeze(null, in.title().strip(), in.reason(), in.startsAt(), in.endsAt(), in.scope(),
                "all".equals(in.scope()) ? List.of() : services, "all".equals(in.scope()) ? List.of() : cis, version);
    }

    // ---------------------------------------------------------------- настройки

    @GetMapping("/api/v1/change-settings")
    @PreAuthorize(ChangeController.WORKERS + " or hasRole('ADM')")
    ResponseEntity<ChangeSettings.Doc> settings() {
        ChangeSettings.Doc d = settings.get();
        return ResponseEntity.ok().eTag(ETags.of(d.version())).body(d);
    }

    @PutMapping("/api/v1/change-settings")
    @PreAuthorize(MANAGERS)
    @Transactional
    ResponseEntity<ChangeSettings.Doc> saveSettings(@RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                    @RequestBody ChangeSettings.Doc doc) {
        ChangeSettings.Doc saved = settings.save(doc, ifMatch);
        history.record("changes.settings", "", "Маршруты согласования изменений", "update", saved, null);
        return ResponseEntity.ok().eTag(ETags.of(saved.version())).body(saved);
    }
}
