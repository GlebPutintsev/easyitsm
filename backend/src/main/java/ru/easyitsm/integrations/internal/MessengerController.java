package ru.easyitsm.integrations.internal;

import java.net.URI;
import java.util.List;
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
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.identity.api.RequiresRecentAuth;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.ETags;

/**
 * Мессенджеры: боты «Управление → Интеграции» (ADM, чтение — ещё AUD) и привязка из профиля (любой вошедший).
 */
@RestController
@RequestMapping("/api/v1/integrations")
class MessengerController {

    private final MessengerService service;

    MessengerController(MessengerService service) {
        this.service = service;
    }

    @GetMapping("/messengers")
    @PreAuthorize("hasAnyRole('ADM', 'AUD')")
    CursorPage<MessengerStore.Messenger> list() {
        return new CursorPage<>(service.list(), null);
    }

    @GetMapping("/messengers/{id}")
    @PreAuthorize("hasAnyRole('ADM', 'AUD')")
    ResponseEntity<MessengerStore.Messenger> get(@PathVariable UUID id) {
        MessengerStore.Messenger m = service.get(id);
        return ResponseEntity.ok().eTag(ETags.of(m.version())).body(m);
    }

    @PostMapping("/messengers")
    @PreAuthorize("hasRole('ADM')")
    ResponseEntity<MessengerStore.Messenger> create(@RequestBody MessengerService.MessengerInput input) {
        MessengerStore.Messenger m = service.create(input);
        return ResponseEntity.created(URI.create("/api/v1/integrations/messengers/" + m.id()))
                .eTag(ETags.of(m.version())).body(m);
    }

    @PatchMapping("/messengers/{id}")
    @PreAuthorize("hasRole('ADM')")
    ResponseEntity<MessengerStore.Messenger> update(@PathVariable UUID id,
                                                    @RequestHeader(value = HttpHeaders.IF_MATCH, required = false)
                                                    String ifMatch,
                                                    @RequestBody MessengerService.MessengerInput input) {
        MessengerStore.Messenger m = service.update(id, ifMatch, input);
        return ResponseEntity.ok().eTag(ETags.of(m.version())).body(m);
    }

    @DeleteMapping("/messengers/{id}")
    @PreAuthorize("hasRole('ADM')")
    @RequiresRecentAuth
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/messengers/{id}/check")
    @PreAuthorize("hasRole('ADM')")
    MessengerService.CheckResult check(@PathVariable UUID id) {
        return service.check(id);
    }

    // ---------------------------------------------------------------- профиль

    @GetMapping("/my-messengers")
    @PreAuthorize("isAuthenticated()")
    List<MessengerService.MyMessenger> mine() {
        return service.mine();
    }

    @PostMapping("/my-messengers/{id}/link")
    @PreAuthorize("isAuthenticated()")
    MessengerService.LinkCode link(@PathVariable UUID id) {
        return service.linkCode(id);
    }

    @DeleteMapping("/my-messengers/{id}")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<Void> unlink(@PathVariable UUID id) {
        service.unlinkMine(id);
        return ResponseEntity.noContent().build();
    }
}
