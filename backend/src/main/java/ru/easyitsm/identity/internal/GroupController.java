package ru.easyitsm.identity.internal;

import jakarta.validation.Valid;
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

@RestController
@RequestMapping("/api/v1/groups")
class GroupController {

    private final GroupService service;

    GroupController(GroupService service) {
        this.service = service;
    }

    record MemberCommand(Boolean lead) {
    }

    @GetMapping
    @PreAuthorize(UserController.STAFF)
    CursorPage<UserGroup.GroupView> list(@RequestParam(required = false) String q,
                                         @RequestParam(required = false) String cursor,
                                         @RequestParam(required = false) Integer limit) {
        return service.list(q, PageParams.of(cursor, limit));
    }

    @GetMapping("/{id}")
    @PreAuthorize(UserController.STAFF)
    ResponseEntity<UserGroup.GroupView> get(@PathVariable UUID id) {
        var view = service.get(id);
        return ResponseEntity.ok().eTag(ETags.of(view.version())).body(view);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADM')")
    ResponseEntity<UserGroup.GroupView> create(@Valid @RequestBody GroupService.Create cmd) {
        var view = service.create(cmd);
        return ResponseEntity.created(URI.create("/api/v1/groups/" + view.id())).eTag(ETags.of(view.version()))
                .body(view);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADM')")
    ResponseEntity<UserGroup.GroupView> update(@PathVariable UUID id,
                                               @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                               @Valid @RequestBody GroupService.Update cmd) {
        var view = service.update(id, ifMatch, cmd);
        return ResponseEntity.ok().eTag(ETags.of(view.version())).body(view);
    }

    @GetMapping("/{id}/members")
    @PreAuthorize(UserController.STAFF)
    List<GroupService.Member> members(@PathVariable UUID id) {
        return service.members(id);
    }

    /** Членство в группе даёт роли группы, поэтому это назначение ролей: нужен повторный вход (SEC-08). */
    @PutMapping("/{id}/members/{userId}")
    @PreAuthorize("hasRole('ADM')")
    @RequiresRecentAuth
    ResponseEntity<Void> addMember(@PathVariable UUID id, @PathVariable UUID userId,
                                   @RequestBody(required = false) MemberCommand cmd) {
        service.addMember(id, userId, cmd != null && Boolean.TRUE.equals(cmd.lead()));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/members/{userId}")
    @PreAuthorize("hasRole('ADM')")
    @RequiresRecentAuth
    ResponseEntity<Void> removeMember(@PathVariable UUID id, @PathVariable UUID userId) {
        service.removeMember(id, userId);
        return ResponseEntity.noContent().build();
    }
}
