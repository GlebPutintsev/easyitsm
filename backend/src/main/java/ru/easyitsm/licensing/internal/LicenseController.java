package ru.easyitsm.licensing.internal;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.licensing.api.LicenseStatus;
import ru.easyitsm.platform.security.CurrentActor;

/** «Управление → Лицензия» (ADM-07): просмотр и загрузка файла лицензии. Только администратор. */
@RestController
@RequestMapping("/api/v1/admin/license")
@PreAuthorize("hasRole('ADM')")
class LicenseController {

    private final LicenseService service;

    LicenseController(LicenseService service) {
        this.service = service;
    }

    @GetMapping
    LicenseStatus get() {
        return service.current();
    }

    /** Тело — содержимое файла лицензии как есть. */
    @PutMapping(consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_PLAIN_VALUE,
            MediaType.APPLICATION_OCTET_STREAM_VALUE})
    LicenseStatus install(@RequestBody String content) {
        return service.install(content, CurrentActor.userId().orElse(null));
    }
}
