package ru.easyitsm.admin.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.PageParams;

/**
 * Версии настроек и журнал действий администратора {@code /api/v1/admin/config-versions} (ADM-06, 10.16
 * «История»). Экраны «Управления» записывают сюда снимок настройки другого модуля после её сохранения.
 */
@RestController
@RequestMapping("/api/v1/admin/config-versions")
class ConfigVersionController {

    private final ConfigVersionService service;

    ConfigVersionController(ConfigVersionService service) {
        this.service = service;
    }

    record RecordCommand(
            @NotBlank @Size(max = 100) String entity,
            @Size(max = 100) String entityId,
            @Size(max = 255) String label,
            @NotNull @Pattern(regexp = "create|update|delete|restore") String action,
            Object snapshot,
            @Size(max = 1000) String comment) {
    }

    @GetMapping
    @PreAuthorize(Roles.JOURNAL)
    CursorPage<ConfigVersionService.Summary> list(@RequestParam(required = false) String entity,
                                                  @RequestParam(required = false) String entityId,
                                                  @RequestParam(required = false) UUID author,
                                                  @RequestParam(required = false) String action,
                                                  @RequestParam(required = false) Instant from,
                                                  @RequestParam(required = false) Instant to,
                                                  @RequestParam(required = false) String cursor,
                                                  @RequestParam(required = false) Integer limit) {
        return service.list(entity, entityId, author, action, from, to, PageParams.of(cursor, limit));
    }

    @GetMapping("/{id}")
    @PreAuthorize(Roles.JOURNAL)
    ConfigVersionService.Version get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping("/{id}/diff")
    @PreAuthorize(Roles.JOURNAL)
    ConfigVersionService.Diff diff(@PathVariable UUID id, @RequestParam(required = false) UUID against) {
        return service.diff(id, against);
    }

    /**
     * Версия настройки другого модуля. Настройки самого «Управления» ({@code admin.*}) версионирует сервер,
     * подделать их снимок с клиента нельзя.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADM', 'SEC')")
    ResponseEntity<ConfigVersionService.Summary> create(@Valid @RequestBody RecordCommand cmd) {
        if (cmd.entity().startsWith("admin.")) {
            throw ApiException.badRequest("server_managed", "Версии настроек «Управления» записывает сервер");
        }
        var saved = service.save(cmd.entity(), cmd.entityId(), cmd.label(), cmd.action(), cmd.snapshot(),
                cmd.comment(), "ui");
        return ResponseEntity.created(URI.create("/api/v1/admin/config-versions/" + saved.id())).body(saved);
    }
}
