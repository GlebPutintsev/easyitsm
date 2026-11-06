package ru.easyitsm.automation.internal;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
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
import ru.easyitsm.identity.api.RequiresRecentAuth;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.ETags;
import ru.easyitsm.platform.web.PageParams;

/**
 * Пространство «Управление → Автоматизация» (10.16): правила, проверка, журнал запусков.
 * Изменять может ADM, читать правила и журнал — ещё AUD.
 */
@RestController
@RequestMapping("/api/v1/automation")
@PreAuthorize("hasAnyRole('ADM', 'AUD')")
class AutomationController {

    record TestRequest(@NotNull RuleInput rule, String ticket) {
    }

    private final AutomationService service;

    AutomationController(AutomationService service) {
        this.service = service;
    }

    @GetMapping("/rules")
    CursorPage<Rule> rules() {
        return new CursorPage<>(service.list(), null);
    }

    @GetMapping("/rules/{id}")
    ResponseEntity<Rule> rule(@PathVariable UUID id) {
        Rule r = service.get(id);
        return ResponseEntity.ok().eTag(ETags.of(r.version())).body(r);
    }

    @PostMapping("/rules")
    @PreAuthorize("hasRole('ADM')")
    ResponseEntity<Rule> create(@RequestBody RuleInput input) {
        Rule r = service.create(input);
        return ResponseEntity.created(URI.create("/api/v1/automation/rules/" + r.id())).eTag(ETags.of(r.version()))
                .body(r);
    }

    @PatchMapping("/rules/{id}")
    @PreAuthorize("hasRole('ADM')")
    ResponseEntity<Rule> update(@PathVariable UUID id,
                                @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                @RequestBody RuleInput input) {
        Rule r = service.update(id, ifMatch, input);
        return ResponseEntity.ok().eTag(ETags.of(r.version())).body(r);
    }

    @DeleteMapping("/rules/{id}")
    @PreAuthorize("hasRole('ADM')")
    @RequiresRecentAuth
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** «Проверить»: что сделает правило с заявкой сейчас, без изменений. Правило может быть не сохранено. */
    @PostMapping("/test")
    @PreAuthorize("hasRole('ADM')")
    AutomationService.TestResult test(@RequestBody TestRequest req) {
        return service.test(req.rule(), req.ticket());
    }

    @GetMapping("/runs")
    CursorPage<Run> runs(@RequestParam(required = false) UUID ruleId,
                         @RequestParam(required = false) String status,
                         @RequestParam(required = false) String ticket,
                         @RequestParam(required = false) String cursor,
                         @RequestParam(required = false) Integer limit) {
        return service.runs(ruleId, status, ticket, PageParams.of(cursor, limit));
    }

    @GetMapping("/runs/{id}")
    Run run(@PathVariable UUID id) {
        return service.run(id);
    }
}
