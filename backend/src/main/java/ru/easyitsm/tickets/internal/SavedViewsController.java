package ru.easyitsm.tickets.internal;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.platform.web.ETags;

/** {@code /api/v1/ticket-views} (TCK-16) и {@code /api/v1/response-templates} (TCK-09). */
@RestController
class SavedViewsController {

    private final SavedViews saved;

    SavedViewsController(SavedViews saved) {
        this.saved = saved;
    }

    @GetMapping("/api/v1/ticket-views")
    List<SavedViews.ViewDef> views() {
        return saved.views(Actor.current());
    }

    @PostMapping("/api/v1/ticket-views")
    ResponseEntity<SavedViews.ViewDef> createView(@RequestBody SavedViews.ViewDef v) {
        SavedViews.ViewDef r = saved.createView(v, Actor.current());
        return ResponseEntity.created(URI.create("/api/v1/ticket-views/" + r.id())).eTag(ETags.of(r.version())).body(r);
    }

    @PatchMapping("/api/v1/ticket-views/{id}")
    ResponseEntity<SavedViews.ViewDef> updateView(@PathVariable UUID id,
                                                  @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                  @RequestBody SavedViews.ViewDef v) {
        SavedViews.ViewDef r = saved.updateView(id, ifMatch, v, Actor.current());
        return ResponseEntity.ok().eTag(ETags.of(r.version())).body(r);
    }

    @DeleteMapping("/api/v1/ticket-views/{id}")
    ResponseEntity<Void> deleteView(@PathVariable UUID id) {
        saved.deleteView(id, Actor.current());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/response-templates")
    List<SavedViews.Template> templates(@RequestParam(required = false) String q) {
        return saved.templates(q, Actor.current());
    }

    @PostMapping("/api/v1/response-templates")
    ResponseEntity<SavedViews.Template> createTemplate(@RequestBody SavedViews.Template t) {
        SavedViews.Template r = saved.createTemplate(t, Actor.current());
        return ResponseEntity.created(URI.create("/api/v1/response-templates/" + r.id())).eTag(ETags.of(r.version()))
                .body(r);
    }

    @PatchMapping("/api/v1/response-templates/{id}")
    ResponseEntity<SavedViews.Template> updateTemplate(@PathVariable UUID id,
                                                       @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                       @RequestBody SavedViews.Template t) {
        SavedViews.Template r = saved.updateTemplate(id, ifMatch, t, Actor.current());
        return ResponseEntity.ok().eTag(ETags.of(r.version())).body(r);
    }

    @DeleteMapping("/api/v1/response-templates/{id}")
    ResponseEntity<Void> deleteTemplate(@PathVariable UUID id) {
        saved.deleteTemplate(id, Actor.current());
        return ResponseEntity.noContent().build();
    }
}
