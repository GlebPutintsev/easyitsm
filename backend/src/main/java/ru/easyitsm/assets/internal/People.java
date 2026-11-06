package ru.easyitsm.assets.internal;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ru.easyitsm.identity.api.UserDirectory;
import ru.easyitsm.identity.api.UserSummary;
import ru.easyitsm.platform.web.ApiException;

/** Сотрудники из модуля identity: имена для карточек и проверка того, кому выдаётся актив. */
@Component
class People {

    private final UserDirectory directory;

    People(UserDirectory directory) {
        this.directory = directory;
    }

    Map<UUID, String> names(Collection<UUID> ids) {
        var wanted = ids.stream().filter(Objects::nonNull).distinct().toList();
        Map<UUID, String> out = new HashMap<>();
        if (!wanted.isEmpty()) {
            for (UserSummary u : directory.findUsers(wanted)) {
                out.put(u.id(), u.displayName());
            }
        }
        return out;
    }

    /** Действующий сотрудник арендатора, иначе 400: выдать актив заблокированному или чужому нельзя. */
    void requireActive(UUID userId, String field) {
        boolean ok = directory.findUser(userId).map(UserSummary::active).orElse(false);
        if (!ok) {
            throw ApiException.badRequest("unknown_user", "Сотрудник не найден или заблокирован (" + field + ")");
        }
    }

    /** Ответственный за договор или лицензию: сотрудник арендатора (может быть заблокирован позже). */
    void requireKnown(UUID userId, String field) {
        if (userId != null && directory.findUser(userId).isEmpty()) {
            throw ApiException.badRequest("unknown_user", "Сотрудник не найден (" + field + ")");
        }
    }
}
