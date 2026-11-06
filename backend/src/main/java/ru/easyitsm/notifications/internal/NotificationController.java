package ru.easyitsm.notifications.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.PageParams;

/** Центр уведомлений и подписки Web Push текущего пользователя (NTF-01). Чужие записи не видны: 404. */
@RestController
@RequestMapping("/api/v1/notifications")
@PreAuthorize("isAuthenticated()")
class NotificationController {

    private final NotificationStore store;
    private final PushService push;
    private final NotificationProperties props;

    NotificationController(NotificationStore store, PushService push, NotificationProperties props) {
        this.store = store;
        this.push = push;
        this.props = props;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public CursorPage<NotificationStore.View> list(@RequestParam(defaultValue = "false") boolean unread,
                                                   @RequestParam(required = false) String cursor,
                                                   @RequestParam(required = false) Integer limit) {
        return store.page(me(), unread, PageParams.of(cursor, limit));
    }

    @GetMapping("/unread-count")
    @Transactional(readOnly = true)
    public Map<String, Long> unreadCount() {
        return Map.of("count", store.unread(me()));
    }

    record ReadRequest(Boolean read) {
    }

    @PostMapping("/{id}/read")
    @Transactional
    public ResponseEntity<Void> read(@PathVariable UUID id, @RequestBody(required = false) ReadRequest req) {
        boolean read = req == null || req.read() == null || req.read();
        if (!store.markRead(me(), id, read)) {
            throw ApiException.notFound("Уведомление");
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    @Transactional
    public Map<String, Integer> readAll() {
        return Map.of("updated", store.markAllRead(me()));
    }

    // ================================================================ Web Push

    record PushInfo(boolean enabled, String publicKey, long subscriptions) {
    }

    @GetMapping("/push")
    public PushInfo pushInfo() {
        if (!props.pushEnabled()) {
            return new PushInfo(false, null, 0);
        }
        return new PushInfo(true, WebPush.b64(push.vapid().publicRaw()), push.subscriptions(me()));
    }

    record Keys(@NotBlank @Size(max = 200) String p256dh, @NotBlank @Size(max = 100) String auth) {
    }

    record Subscription(@NotBlank @Size(max = 2000) String endpoint, @NotNull @Valid Keys keys) {
    }

    @PostMapping("/push/subscriptions")
    public ResponseEntity<Void> subscribe(@Valid @RequestBody Subscription s,
                                          @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String ua) {
        if (!props.pushEnabled()) {
            throw ApiException.conflict("push_disabled", "Web Push выключен на этой установке");
        }
        push.subscribe(me(), s.endpoint(), s.keys().p256dh(), s.keys().auth(), ua);
        return ResponseEntity.noContent().build();
    }

    record Unsubscribe(@NotBlank @Size(max = 2000) String endpoint) {
    }

    @DeleteMapping("/push/subscriptions")
    public ResponseEntity<Void> unsubscribe(@Valid @RequestBody Unsubscribe s) {
        push.unsubscribe(me(), s.endpoint());
        return ResponseEntity.noContent().build();
    }

    private static UUID me() {
        return CurrentActor.userId().orElseThrow(ApiException::forbidden);
    }
}
