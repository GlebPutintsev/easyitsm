package ru.easyitsm.mim.internal;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.mim.internal.MimDtos.ChainView;
import ru.easyitsm.mim.internal.MimDtos.TemplateView;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.ETags;

/**
 * Настройки командного центра: шаблоны сообщений (MIM-04) и цепочки оповещения (MIM-03). Читает поддержка,
 * правят {@code MIM} и {@code ADM}. Удаления нет — запись выключается ({@code active = false}).
 */
@RestController
@RequestMapping("/api/v1/mim")
class MimSettingsController {

    static final String EDITORS = "hasAnyRole('MIM', 'ADM')";
    private static final java.util.Set<String> AUDIENCES = java.util.Set.of("users", "management");

    private final MimStore store;
    private final MessageTemplates templates;
    private final MimService service;

    MimSettingsController(MimStore store, MessageTemplates templates, MimService service) {
        this.store = store;
        this.templates = templates;
        this.service = service;
    }

    @GetMapping("/templates")
    @PreAuthorize(MimController.READERS + " or hasRole('ADM')")
    @Transactional
    List<TemplateView> templates(@RequestParam(defaultValue = "false") boolean all) {
        return templates.all(!all, Me.id()).stream().map(MimSettingsController::view).toList();
    }

    @PostMapping("/templates")
    @PreAuthorize(EDITORS)
    @Transactional
    ResponseEntity<TemplateView> createTemplate(@Valid @RequestBody MimDtos.SaveTemplate in) {
        checkAudience(in.audience());
        templates.all(false, Me.id());
        UUID id = store.saveTemplate(null, in, Me.id());
        return template(id);
    }

    @PutMapping("/templates/{id}")
    @PreAuthorize(EDITORS)
    @Transactional
    ResponseEntity<TemplateView> updateTemplate(@PathVariable UUID id,
                                                @RequestHeader(value = HttpHeaders.IF_MATCH, required = false)
                                                String ifMatch,
                                                @Valid @RequestBody MimDtos.SaveTemplate in) {
        checkAudience(in.audience());
        MimStore.Template t = store.template(id, true).orElseThrow(() -> ApiException.notFound("Шаблон"));
        ETags.requireMatch(ifMatch, t.version());
        store.saveTemplate(id, in, Me.id());
        return template(id);
    }

    @GetMapping("/chains")
    @PreAuthorize(MimController.READERS + " or hasRole('ADM')")
    @Transactional(readOnly = true)
    List<ChainView> chains(@RequestParam(defaultValue = "false") boolean all) {
        return store.chains(!all).stream().map(MimSettingsController::view).toList();
    }

    @PostMapping("/chains")
    @PreAuthorize(EDITORS)
    @Transactional
    ResponseEntity<ChainView> createChain(@Valid @RequestBody MimDtos.SaveChain in) {
        service.validateSteps(in.steps());
        return chain(store.saveChain(null, in, Me.id()));
    }

    @PutMapping("/chains/{id}")
    @PreAuthorize(EDITORS)
    @Transactional
    ResponseEntity<ChainView> updateChain(@PathVariable UUID id,
                                          @RequestHeader(value = HttpHeaders.IF_MATCH, required = false)
                                          String ifMatch,
                                          @Valid @RequestBody MimDtos.SaveChain in) {
        MimStore.Chain c = store.chain(id).orElseThrow(() -> ApiException.notFound("Цепочка оповещения"));
        ETags.requireMatch(ifMatch, c.version());
        service.validateSteps(in.steps());
        store.saveChain(id, in, Me.id());
        return chain(id);
    }

    private static void checkAudience(String audience) {
        if (!AUDIENCES.contains(audience)) {
            throw ApiException.badRequest("invalid_audience", "Аудитория: users или management");
        }
    }

    private ResponseEntity<TemplateView> template(UUID id) {
        TemplateView v = view(store.template(id, false).orElseThrow());
        return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
    }

    private ResponseEntity<ChainView> chain(UUID id) {
        ChainView v = view(store.chain(id).orElseThrow());
        return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
    }

    private static TemplateView view(MimStore.Template t) {
        return new TemplateView(t.id(), t.name(), t.audience(), t.title(), t.body(), t.position(), t.active(),
                t.version());
    }

    private static ChainView view(MimStore.Chain c) {
        return new ChainView(c.id(), c.name(), c.steps(), c.active(), c.version());
    }
}
