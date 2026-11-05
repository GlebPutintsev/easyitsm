package ru.easyitsm.identity.internal;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.identity.api.RequiresRecentAuth;

/** «Роли и политики» (10.16 ТЗ): назначает SEC с повторной аутентификацией (матрица 3.2, SEC-08). */
@RestController
class RoleController {

    private final RoleGrantService service;

    RoleController(RoleGrantService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/roles")
    @PreAuthorize(UserController.STAFF)
    List<RoleGrantService.RoleView> roles() {
        return service.catalog();
    }

    @GetMapping("/api/v1/role-grants")
    @PreAuthorize("hasAnyRole('SEC', 'ADM', 'AUD')")
    List<RoleGrantService.GrantView> grants(@RequestParam(required = false) UUID userId,
                                            @RequestParam(required = false) UUID groupId,
                                            @RequestParam(defaultValue = "false") boolean includeRevoked) {
        return service.list(userId, groupId, includeRevoked);
    }

    @PostMapping("/api/v1/role-grants")
    @PreAuthorize("hasRole('SEC')")
    @RequiresRecentAuth
    @ResponseStatus(HttpStatus.CREATED)
    RoleGrantService.GrantView grant(@Valid @RequestBody RoleGrantService.Grant cmd) {
        return service.grant(cmd);
    }

    @DeleteMapping("/api/v1/role-grants/{id}")
    @PreAuthorize("hasRole('SEC')")
    @RequiresRecentAuth
    RoleGrantService.GrantView revoke(@PathVariable UUID id) {
        return service.revoke(id);
    }
}
