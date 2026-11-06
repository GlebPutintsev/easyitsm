package ru.easyitsm.identity.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.identity.api.DirectoryAdmin;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.web.ApiException;

/** Реализация {@link DirectoryAdmin} поверх тех же сервисов, что и REST «Управления»: события и проверки общие. */
@Service
class DirectoryAdminService implements DirectoryAdmin {

    private final UserAdminService users;
    private final GroupService groups;
    private final UserRepository userRepository;
    private final GroupRepository groupRepository;
    private final JdbcClient jdbc;

    DirectoryAdminService(UserAdminService users, GroupService groups, UserRepository userRepository,
                          GroupRepository groupRepository, JdbcClient jdbc) {
        this.users = users;
        this.groups = groups;
        this.userRepository = userRepository;
        this.groupRepository = groupRepository;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public UUID createUser(NewUser u) {
        var cmd = new UserCommands.Create(u.login(), u.email(), u.displayName(), u.locale(), u.timezone(), null,
                u.organizationId(), u.departmentId(), null);
        return users.create(cmd).id();
    }

    @Override
    @Transactional
    public UUID createGroup(String name, String type, String description) {
        return groups.create(new GroupService.Create(name, description, type)).id();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> findUserByLogin(String loginOrEmail) {
        if (loginOrEmail == null || loginOrEmail.isBlank()) {
            return Optional.empty();
        }
        return userRepository.findByLoginOrEmail(loginOrEmail.trim()).map(UserAccount::getId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> findGroupByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return jdbc.sql("select id from user_group where lower(name) = lower(:name) limit 1")
                .param("name", name.trim()).query(UUID.class).optional();
    }

    @Override
    @Transactional
    public void addMember(UUID groupId, UUID userId, boolean lead) {
        groups.addMember(groupId, userId, lead);
    }

    @Override
    @Transactional
    public void removeMember(UUID groupId, UUID userId) {
        try {
            groups.removeMember(groupId, userId);
        } catch (ApiException e) {
            if (!"not_found".equals(e.code())) {
                throw e;
            }
        }
    }

    @Override
    @Transactional
    public boolean removeImportedUser(UUID userId) {
        Optional<UserAccount> found = userRepository.findById(userId);
        if (found.isEmpty()) {
            return false;
        }
        UserAccount user = found.get();
        if (user.toView().lastLoginAt() == null) {
            userRepository.delete(user);
            return true;
        }
        users.block(user, "admin", CurrentActor.userId().orElse(null));
        return false;
    }

    @Override
    @Transactional
    public void removeImportedGroup(UUID groupId) {
        groupRepository.findById(groupId).ifPresent(g -> {
            if ("local".equals(g.source())) {
                groupRepository.delete(g);
            }
        });
    }
}
