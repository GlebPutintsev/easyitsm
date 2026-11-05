package ru.easyitsm.identity.internal;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import ru.easyitsm.identity.api.AccessDecisions;
import ru.easyitsm.identity.api.AccessScope;
import ru.easyitsm.identity.api.RecordAccess;
import ru.easyitsm.identity.api.Role;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.web.ApiException;

/**
 * ABAC по атрибутам записи (ACC-02, ACC-04) поверх ролей из матрицы раздела 3.2 ТЗ, строка «Читать заявку».
 * Роли берутся из текущей аутентификации, группы и организация — из справочника.
 */
@Service
class AccessDecisionService implements AccessDecisions {

    /** Читают все неконфиденциальные записи. */
    private static final Set<Role> READ_ALL = EnumSet.of(Role.MIM, Role.AUD);
    /** Читают записи своих групп («О» в матрице). */
    private static final Set<Role> GROUP_SCOPED = EnumSet.of(Role.AGT, Role.LEAD, Role.CHG, Role.PRB, Role.CFG,
            Role.AST, Role.KNW);

    private final Directory directory;
    private final IdentityProperties props;

    AccessDecisionService(Directory directory, IdentityProperties props) {
        this.directory = directory;
        this.props = props;
    }

    @Override
    public AccessScope currentScope() {
        UUID userId = CurrentActor.userId().orElse(null);
        if (userId == null) {
            return new AccessScope(null, Set.of(), false, false, null);
        }
        Set<Role> roles = currentRoles();
        UUID organization = props.access().requesterSeesOrganization()
                ? directory.organizationOf(userId).orElse(null) : null;
        return new AccessScope(userId, Set.copyOf(directory.groupsOf(userId)),
                roles.stream().anyMatch(READ_ALL::contains),
                roles.stream().anyMatch(GROUP_SCOPED::contains),
                organization);
    }

    @Override
    public boolean canRead(RecordAccess record) {
        AccessScope scope = currentScope();
        return decide(scope, record);
    }

    static boolean decide(AccessScope scope, RecordAccess record) {
        if (scope.userId() == null) {
            return false;
        }
        if (record.isParticipant(scope.userId())) {
            return true;
        }
        if (record.confidential()) {
            return record.securityGroupId() != null && scope.groupIds().contains(record.securityGroupId());
        }
        if (scope.allNonConfidential()) {
            return true;
        }
        if (scope.groupScoped() && record.assignedGroupId() != null
                && scope.groupIds().contains(record.assignedGroupId())) {
            return true;
        }
        return scope.organizationId() != null && scope.organizationId().equals(record.organizationId());
    }

    @Override
    public void requireNotAuthor(UUID authorId) {
        UUID userId = CurrentActor.userId().orElseThrow(ApiException::forbidden);
        if (userId.equals(authorId)) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "separation_of_duties",
                    "Автор не может утвердить собственное изменение");
        }
    }

    private static Set<Role> currentRoles() {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
            for (GrantedAuthority a : auth.getAuthorities()) {
                String name = a.getAuthority();
                if (name != null && name.startsWith("ROLE_")) {
                    Role.parse(name.substring(5)).ifPresent(roles::add);
                }
            }
        }
        return roles;
    }
}
