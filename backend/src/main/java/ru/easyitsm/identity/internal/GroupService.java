package ru.easyitsm.identity.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.identity.events.GroupMembershipChanged;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.ETags;
import ru.easyitsm.platform.web.KeysetCursor;
import ru.easyitsm.platform.web.PageParams;

@Service
class GroupService {

    private final GroupRepository groups;
    private final UserRepository users;
    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;

    GroupService(GroupRepository groups, UserRepository users, JdbcClient jdbc, ApplicationEventPublisher events) {
        this.groups = groups;
        this.users = users;
        this.jdbc = jdbc;
        this.events = events;
    }

    record Create(@NotBlank @Size(max = 255) String name, @Size(max = 2000) String description,
                  @Pattern(regexp = "support|security|approval|other") String type) {
    }

    record Update(@Size(min = 1, max = 255) String name, @Size(max = 2000) String description,
                  @Pattern(regexp = "support|security|approval|other") String type) {
    }

    record Member(UUID userId, String login, String displayName, boolean lead, boolean active, Instant addedAt) {
    }

    @Transactional(readOnly = true)
    public CursorPage<UserGroup.GroupView> list(String q, PageParams page) {
        KeysetCursor after = page.keyset();
        String pattern = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        List<UserGroup> rows = groups.page(pattern, after == null ? null : after.sortValue(),
                after == null ? null : after.id(), page.limit() + 1);
        return CursorPage.of(rows, page.limit(), g -> new KeysetCursor(g.name().toLowerCase(), g.getId()))
                .map(UserGroup::toView);
    }

    @Transactional(readOnly = true)
    public UserGroup.GroupView get(UUID id) {
        return find(id).toView();
    }

    @Transactional
    public UserGroup.GroupView create(Create cmd) {
        var group = new UserGroup(cmd.name().trim(), cmd.description(), cmd.type(), "local", null);
        return Duplicates.guard(() -> groups.save(group), "name_taken", "Группа с таким именем уже есть").toView();
    }

    @Transactional
    public UserGroup.GroupView update(UUID id, String ifMatch, Update cmd) {
        UserGroup group = find(id);
        ETags.requireMatch(ifMatch, group.getVersion());
        if (cmd.name() != null) {
            group.setName(cmd.name().trim());
        }
        if (cmd.description() != null) {
            group.setDescription(cmd.description());
        }
        if (cmd.type() != null) {
            group.setType(cmd.type());
        }
        return Duplicates.guard(() -> groups.save(group), "name_taken", "Группа с таким именем уже есть").toView();
    }

    @Transactional(readOnly = true)
    public List<Member> members(UUID id) {
        find(id);
        return jdbc.sql("""
                select m.user_id, u.login, u.display_name, m.is_lead, u.state = 'active', m.added_at
                  from group_member m join user_account u on u.id = m.user_id
                 where m.group_id = :group order by lower(u.display_name), u.id""")
                .param("group", id)
                .query((rs, n) -> new Member(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getBoolean(4), rs.getBoolean(5), rs.getTimestamp(6).toInstant()))
                .list();
    }

    /**
     * Добавляет участника. Администратор не может добавить сам себя в группу, которой выданы роли:
     * это обход запрета выдавать права себе (ACC-05).
     */
    @Transactional
    public void addMember(UUID groupId, UUID userId, boolean lead) {
        UserGroup group = find(groupId);
        if (!"local".equals(group.source())) {
            throw ApiException.conflict("managed_by_source", "Состав группы задаётся в каталоге");
        }
        users.findById(userId).orElseThrow(() -> ApiException.notFound("Пользователь"));
        UUID actor = CurrentActor.userId().orElse(null);
        if (userId.equals(actor) && groupHasRoles(groupId)) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "self_grant_forbidden",
                    "Нельзя добавить себя в группу, которой выданы роли");
        }
        int inserted = jdbc.sql("""
                insert into group_member (tenant_id, group_id, user_id, is_lead, added_by)
                values (:tenant, :group, :user, :lead, :actor)
                on conflict (group_id, user_id) do update set is_lead = excluded.is_lead""")
                .param("tenant", TenantContext.require()).param("group", groupId).param("user", userId)
                .param("lead", lead).param("actor", actor).update();
        if (inserted > 0) {
            events.publishEvent(new GroupMembershipChanged(TenantContext.require(), groupId, userId, true, actor));
        }
    }

    @Transactional
    public void removeMember(UUID groupId, UUID userId) {
        UserGroup group = find(groupId);
        if (!"local".equals(group.source())) {
            throw ApiException.conflict("managed_by_source", "Состав группы задаётся в каталоге");
        }
        int removed = jdbc.sql("delete from group_member where group_id = :group and user_id = :user")
                .param("group", groupId).param("user", userId).update();
        if (removed == 0) {
            throw ApiException.notFound("Участник");
        }
        events.publishEvent(new GroupMembershipChanged(TenantContext.require(), groupId, userId, false,
                CurrentActor.userId().orElse(null)));
    }

    private boolean groupHasRoles(UUID groupId) {
        return jdbc.sql("select exists (select 1 from role_grant where group_id = :group and revoked_at is null)")
                .param("group", groupId).query(Boolean.class).single();
    }

    UserGroup find(UUID id) {
        return groups.findById(id).orElseThrow(() -> ApiException.notFound("Группа"));
    }
}
