package ru.easyitsm.identity.internal;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * Пользователи в «Управлении» (10.16 ТЗ). Список видят сотрудники поддержки (для назначения), менять может ADM.
 * Заявитель без других ролей список пользователей не видит.
 */
@RestController
@RequestMapping("/api/v1/users")
class UserController {

    static final String STAFF = "hasAnyRole('AGT', 'LEAD', 'MIM', 'CHG', 'PRB', 'KNW', 'CFG', 'AST', 'ANL', 'ADM', 'SEC', 'AUD')";

    private final UserAdminService service;
    private final Directory directory;
    private final GroupService groups;

    UserController(UserAdminService service, Directory directory, GroupService groups) {
        this.service = service;
        this.directory = directory;
        this.groups = groups;
    }

    record Access(UUID userId, Set<ru.easyitsm.identity.api.Role> roles, Set<UUID> groups) {
    }

    @GetMapping
    @PreAuthorize(STAFF)
    CursorPage<UserView> list(@RequestParam(required = false) String q,
                              @RequestParam(required = false) String state,
                              @RequestParam(required = false) String cursor,
                              @RequestParam(required = false) Integer limit) {
        return service.list(q, state, PageParams.of(cursor, limit));
    }

    @GetMapping("/{id}")
    @PreAuthorize(STAFF)
    ResponseEntity<UserView> get(@PathVariable UUID id) {
        UserView view = service.get(id);
        return ResponseEntity.ok().eTag(ETags.of(view.version())).body(view);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADM')")
    ResponseEntity<UserView> create(@Valid @RequestBody UserCommands.Create cmd) {
        UserView view = service.create(cmd);
        return ResponseEntity.created(URI.create("/api/v1/users/" + view.id())).eTag(ETags.of(view.version()))
                .body(view);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADM')")
    ResponseEntity<UserView> update(@PathVariable UUID id,
                                    @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                    @Valid @RequestBody UserCommands.Update cmd) {
        UserView view = service.update(id, ifMatch, cmd);
        return ResponseEntity.ok().eTag(ETags.of(view.version())).body(view);
    }

    @PostMapping("/{id}/block")
    @PreAuthorize("hasRole('ADM')")
    UserView block(@PathVariable UUID id) {
        return service.block(id);
    }

    @PostMapping("/{id}/unblock")
    @PreAuthorize("hasRole('ADM')")
    UserView unblock(@PathVariable UUID id) {
        return service.unblock(id);
    }

    /** «Завершить сессии» (SEC-09). */
    @PostMapping("/{id}/sessions/revoke")
    @PreAuthorize("hasAnyRole('ADM', 'SEC')")
    Map<String, Integer> revokeSessions(@PathVariable UUID id) {
        return Map.of("revoked", service.revokeSessions(id));
    }

    /** «Сбросить MFA с проверкой личности» (T-01). */
    @PostMapping("/{id}/mfa/reset")
    @PreAuthorize("hasAnyRole('ADM', 'SEC')")
    @RequiresRecentAuth
    ResponseEntity<Void> resetMfa(@PathVariable UUID id) {
        service.resetMfa(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/password")
    @PreAuthorize("hasRole('ADM')")
    @RequiresRecentAuth
    ResponseEntity<Void> setPassword(@PathVariable UUID id, @Valid @RequestBody UserCommands.SetPassword cmd) {
        service.setPassword(id, cmd.password());
        return ResponseEntity.noContent().build();
    }

    /** «Проверить доступ пользователя»: действующие роли и группы. */
    @GetMapping("/{id}/access")
    @PreAuthorize("hasAnyRole('ADM', 'SEC', 'AUD')")
    Access access(@PathVariable UUID id) {
        service.get(id);
        return new Access(id, directory.rolesOf(id), directory.groupsOf(id));
    }
}
