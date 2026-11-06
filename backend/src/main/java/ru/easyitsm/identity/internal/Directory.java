package ru.easyitsm.identity.internal;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.identity.api.GroupSummary;
import ru.easyitsm.identity.api.Role;
import ru.easyitsm.identity.api.UserDirectory;
import ru.easyitsm.identity.api.UserSummary;

@Service
@Transactional(readOnly = true)
class Directory implements UserDirectory {

    private final UserRepository users;
    private final GroupRepository groups;
    private final JdbcClient jdbc;
    private final Set<Role> defaultRoles;

    Directory(UserRepository users, GroupRepository groups, JdbcClient jdbc, IdentityProperties props) {
        this.users = users;
        this.groups = groups;
        this.jdbc = jdbc;
        this.defaultRoles = defaultRoles(props);
    }

    static Set<Role> defaultRoles(IdentityProperties props) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        props.defaultRoles().forEach(code -> Role.parse(code).ifPresent(roles::add));
        return roles;
    }

    Set<Role> defaultRoles() {
        return defaultRoles;
    }

    @Override
    public Optional<UserSummary> findUser(UUID userId) {
        return users.findById(userId).map(UserAccount::toSummary);
    }

    @Override
    public List<UserSummary> findUsers(Collection<UUID> userIds) {
        return users.findAllById(userIds).stream().map(UserAccount::toSummary).toList();
    }

    @Override
    public Optional<GroupSummary> findGroup(UUID groupId) {
        return groups.findById(groupId).map(UserGroup::toSummary);
    }

    @Override
    public List<GroupSummary> listGroups(String type) {
        return jdbc.sql("""
                select id, name, type from user_group
                 where cast(:type as text) is null or type = :type
                 order by lower(name), id""")
                .param("type", type)
                .query((rs, n) -> new GroupSummary(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("type")))
                .list();
    }

    @Override
    public Set<UUID> groupsOf(UUID userId) {
        return new HashSet<>(jdbc.sql("select group_id from group_member where user_id = :user")
                .param("user", userId).query(UUID.class).list());
    }

    @Override
    public List<UUID> activeMembersOf(UUID groupId) {
        return jdbc.sql("""
                select m.user_id from group_member m join user_account u on u.id = m.user_id
                 where m.group_id = :group and u.state = 'active'
                 order by m.added_at, m.user_id""")
                .param("group", groupId).query(UUID.class).list();
    }

    @Override
    public Optional<UUID> leaderOf(UUID groupId) {
        return jdbc.sql("""
                select m.user_id from group_member m join user_account u on u.id = m.user_id
                 where m.group_id = :group and m.is_lead and u.state = 'active'
                 order by m.added_at, m.user_id limit 1""")
                .param("group", groupId).query(UUID.class).optional();
    }

    @Override
    public Optional<UUID> organizationOf(UUID userId) {
        return users.findById(userId).map(u -> u.toSummary().organizationId());
    }

    @Override
    public boolean isMember(UUID groupId, UUID userId) {
        return jdbc.sql("select exists (select 1 from group_member where group_id = :group and user_id = :user)")
                .param("group", groupId).param("user", userId).query(Boolean.class).single();
    }

    @Override
    public Set<Role> rolesOf(UUID userId) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        boolean active = users.findById(userId).map(UserAccount::active).orElse(false);
        if (!active) {
            return roles;
        }
        roles.addAll(defaultRoles);
        jdbc.sql("""
                select distinct g.role from role_grant g
                 where g.revoked_at is null and (g.expires_at is null or g.expires_at > now())
                   and (g.user_id = :user or g.group_id in (select group_id from group_member where user_id = :user))""")
                .param("user", userId).query(String.class).list()
                .forEach(code -> Role.parse(code).ifPresent(roles::add));
        return roles;
    }
}
