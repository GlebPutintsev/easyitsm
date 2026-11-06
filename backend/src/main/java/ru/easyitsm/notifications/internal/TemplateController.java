package ru.easyitsm.notifications.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.platform.web.ETags;

/** «Управление → Шаблоны уведомлений» (NTF-02): шаблоны на русском и английском с предпросмотром. Только ADM. */
@RestController
@RequestMapping("/api/v1/notification-settings/templates")
@PreAuthorize("hasRole('ADM')")
class TemplateController {

    private final TemplateStore store;
    private final NotificationProperties props;

    TemplateController(TemplateStore store, NotificationProperties props) {
        this.store = store;
        this.props = props;
    }

    @GetMapping
    List<TemplateStore.TemplateView> list() {
        return store.list();
    }

    @GetMapping("/{kind}/{locale}")
    ResponseEntity<TemplateStore.TemplateView> get(@PathVariable String kind, @PathVariable String locale) {
        var v = store.view(kind, locale);
        return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
    }

    @PutMapping("/{kind}/{locale}")
    ResponseEntity<TemplateStore.TemplateView> save(@PathVariable String kind, @PathVariable String locale,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody TemplateStore.TemplateInput in) {
        var v = store.save(kind, locale, in, ifMatch);
        return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
    }

    @DeleteMapping("/{kind}/{locale}")
    ResponseEntity<TemplateStore.TemplateView> reset(@PathVariable String kind, @PathVariable String locale) {
        var v = store.reset(kind, locale);
        return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
    }

    record PreviewRequest(@Size(max = 500) String subject, @Size(max = 20000) String body, boolean confidential) {
    }

    record Preview(String subject, String text, String html) {
    }

    /** Предпросмотр на примере заявки; {@code confidential} показывает, что увидит получатель конфиденциальной. */
    @PostMapping("/{kind}/{locale}/preview")
    Preview preview(@PathVariable String kind, @PathVariable String locale, @Valid @RequestBody PreviewRequest req) {
        TemplateStore.requireKind(kind, locale);
        Templates.Template current = store.effective(kind, locale);
        Map<String, String> model = TemplateStore.sample(locale, req.confidential());
        String subject = TemplateRenderer.subject(req.subject() == null ? current.subject() : req.subject(), model);
        String text = TemplateRenderer.render(req.body() == null ? current.body() : req.body(), model);
        return new Preview(subject, text, TemplateRenderer.html(text, props.productName()));
    }
}
