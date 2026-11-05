package ru.easyitsm.knowledge.internal;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.web.ApiException;

/**
 * Кто читает или меняет статью: всё, что нужно правилам доступа базы знаний (ACC-02, KB-04).
 * <p>
 * До появления модуля identity признаки берутся из полномочий входа:
 * роли {@code ROLE_*} из раздела 3.1 ТЗ, внешний пользователь — {@code ROLE_EXT},
 * членство в группе — {@code GROUP_<uuid>} (в режиме разработки {@code ROLE_GROUP_<uuid>}).
 * TODO(identity): брать сотрудника и группы из api модуля identity.
 *
 * @param manager  редактор знаний (KNW): видит всё, публикует, архивирует
 * @param auditor  аудитор (AUD): видит всё, ничего не меняет
 * @param editor   может создавать и править статьи: агенты и менеджеры процессов
 * @param staff    входит в аудиторию «Агенты»
 * @param employee входит в аудиторию «Сотрудники»
 */
record Reader(UUID userId, boolean manager, boolean auditor, boolean editor, boolean staff, boolean employee,
              Set<UUID> groups) {

    /** Роли, которым доступно создание и правка статей. «Опубликовать статью: AGT — С, KNW — Д» (3.2). */
    static final Set<String> EDITOR_ROLES = Set.of("AGT", "LEAD", "MIM", "CHG", "PRB", "CFG", "AST", "KNW");
    /** Роли сотрудников ИТ: аудитория «Агенты». */
    static final Set<String> STAFF_ROLES = Set.of("AGT", "LEAD", "MIM", "CHG", "PRB", "CFG", "AST", "KNW", "ANL",
            "ADM", "SEC", "AUD");

    private static final Pattern GROUP = Pattern.compile("^(?:ROLE_)?GROUP_([0-9a-fA-F-]{36})$");

    static Reader current() {
        UUID userId = CurrentActor.userId().orElseThrow(ApiException::forbidden);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Collection<? extends GrantedAuthority> authorities = auth == null ? Set.of() : auth.getAuthorities();
        Set<String> names = authorities.stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        Set<String> roles = names.stream().filter(a -> a.startsWith("ROLE_")).map(a -> a.substring(5))
                .collect(Collectors.toSet());
        Set<UUID> groups = names.stream().map(GROUP::matcher).filter(Matcher::matches)
                .map(m -> UUID.fromString(m.group(1))).collect(Collectors.toUnmodifiableSet());
        return of(userId, roles, groups);
    }

    static Reader of(UUID userId, Set<String> roles, Set<UUID> groups) {
        boolean staff = roles.stream().anyMatch(STAFF_ROLES::contains);
        return new Reader(userId,
                roles.contains("KNW"),
                roles.contains("AUD"),
                roles.stream().anyMatch(EDITOR_ROLES::contains),
                staff,
                staff || !roles.contains("EXT"),
                Set.copyOf(groups));
    }

    /** Видит все статьи арендатора в любом статусе. */
    boolean seesEverything() {
        return manager || auditor;
    }

    String[] groupArray() {
        return groups.stream().map(UUID::toString).toArray(String[]::new);
    }
}
