package ru.easyitsm.integrations.internal;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
 * «Управление → Интеграции → Вебхуки»: получатели, разрешённые адреса, журнал доставок, тест и повтор
 * (10.16 «Интеграции и API»). Изменять может ADM, читать — ещё AUD.
 */
@RestController
@RequestMapping("/api/v1/integrations")
@PreAuthorize("hasAnyRole('ADM', 'AUD')")
class WebhookController {

    private final WebhookService service;

    WebhookController(WebhookService service) {
        this.service = service;
    }

    @GetMapping("/webhooks")
    CursorPage<WebhookStore.Webhook> webhooks() {
        return new CursorPage<>(service.webhooks(), null);
    }

    @GetMapping("/webhooks/{id}")
    ResponseEntity<WebhookStore.Webhook> webhook(@PathVariable UUID id) {
        WebhookStore.Webhook w = service.get(id);
        return ResponseEntity.ok().eTag(ETags.of(w.version())).body(w);
    }

    @PostMapping("/webhooks")
    @PreAuthorize("hasRole('ADM')")
    ResponseEntity<WebhookService.WithSecret> create(@RequestBody WebhookService.WebhookInput input) {
        WebhookService.WithSecret created = service.create(input);
        return ResponseEntity.created(URI.create("/api/v1/integrations/webhooks/" + created.webhook().id()))
                .eTag(ETags.of(created.webhook().version())).body(created);
    }

    @PatchMapping("/webhooks/{id}")
    @PreAuthorize("hasRole('ADM')")
    ResponseEntity<WebhookStore.Webhook> update(@PathVariable UUID id,
                                                @RequestHeader(value = HttpHeaders.IF_MATCH, required = false)
                                                String ifMatch,
                                                @RequestBody WebhookService.WebhookInput input) {
        WebhookStore.Webhook w = service.update(id, ifMatch, input);
        return ResponseEntity.ok().eTag(ETags.of(w.version())).body(w);
    }

    /** Перевыпуск ключа подписи: прежние подписи у получателя перестают сходиться сразу. */
    @PostMapping("/webhooks/{id}/secret")
    @PreAuthorize("hasRole('ADM')")
    @RequiresRecentAuth
    WebhookService.WithSecret rotate(@PathVariable UUID id) {
        return service.rotate(id);
    }

    @DeleteMapping("/webhooks/{id}")
    @PreAuthorize("hasRole('ADM')")
    @RequiresRecentAuth
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/webhooks/{id}/test")
    @PreAuthorize("hasRole('ADM')")
    WebhookService.TestResult test(@PathVariable UUID id) {
        return service.test(id);
    }

    @GetMapping("/outbound-policy")
    ResponseEntity<WebhookStore.Policy> policy() {
        WebhookStore.Policy p = service.policy();
        return ResponseEntity.ok().eTag(ETags.of(p.version())).body(p);
    }

    @PutMapping("/outbound-policy")
    @PreAuthorize("hasRole('ADM')")
    ResponseEntity<WebhookStore.Policy> savePolicy(@RequestHeader(value = HttpHeaders.IF_MATCH, required = false)
                                                   String ifMatch,
                                                   @RequestBody WebhookService.PolicyInput input) {
        WebhookStore.Policy p = service.savePolicy(ifMatch, input);
        return ResponseEntity.ok().eTag(ETags.of(p.version())).body(p);
    }

    @GetMapping("/webhook-deliveries")
    CursorPage<DeliveryStore.Delivery> deliveries(@RequestParam(required = false) UUID webhookId,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(required = false) String cursor,
                                                  @RequestParam(required = false) Integer limit) {
        return service.deliveries(webhookId, status, PageParams.of(cursor, limit));
    }

    @GetMapping("/webhook-deliveries/{id}")
    Map<String, Object> delivery(@PathVariable UUID id) {
        return service.delivery(id);
    }

    @PostMapping("/webhook-deliveries/{id}/retry")
    @PreAuthorize("hasRole('ADM')")
    DeliveryStore.Delivery retry(@PathVariable UUID id) {
        return service.retry(id);
    }
}
