package ru.easyitsm.identity.internal;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.platform.web.ApiException;

/** «Пользователи → Синхронизировать» (10.16 ТЗ). */
@RestController
class DirectoryController {

    private final LdapSync sync;
    private final LdapClient ldap;

    DirectoryController(LdapSync sync, LdapClient ldap) {
        this.sync = sync;
        this.ldap = ldap;
    }

    @PostMapping("/api/v1/directory/ldap/sync")
    @PreAuthorize("hasRole('ADM')")
    LdapSync.Report syncNow() {
        if (!ldap.enabled()) {
            throw ApiException.conflict("ldap_disabled", "Каталог LDAP не подключён");
        }
        return sync.sync();
    }
}
