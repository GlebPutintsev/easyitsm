package ru.easyitsm.changes.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.security.CurrentActor;
import ru.easyitsm.platform.tenant.TenantContext;

/** Заседания CAB, повестка и голоса (CHG-07). */
@Repository
class CabStore {

    record Meeting(UUID id, String title, Instant startsAt, int durationMinutes, String location, UUID chairId,
                   List<UUID> memberIds, String state, String minutes, Instant startedAt, Instant completedAt,
                   Instant protocolSentAt, Instant createdAt, long version) {

        boolean participant(UUID user) {
            return chairId.equals(user) || memberIds.contains(user);
        }
    }

    record Item(UUID id, UUID meetingId, UUID changeId, int seq, String decision, String comment, UUID decidedBy,
                Instant decidedAt) {
    }

    record Vote(UUID itemId, UUID voterId, String vote, String comment, Instant votedAt) {
    }

    private final JdbcClient jdbc;

    CabStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- заседания

    Optional<Meeting> find(UUID id) {
        return jdbc.sql("select * from cab_meeting where id = :id").param("id", id).query(CabStore::meeting)
                .optional();
    }

    Optional<Meeting> lock(UUID id) {
        return jdbc.sql("select * from cab_meeting where id = :id for update").param("id", id)
                .query(CabStore::meeting).optional();
    }

    /** Заседания по убыванию начала с keyset-курсором. */
    List<Meeting> page(Instant afterStartsAt, UUID afterId, String state, int limit) {
        return jdbc.sql("""
                select * from cab_meeting
                 where (cast(:state as text) is null or state = :state)
                   and (cast(:after as timestamptz) is null or (starts_at, id) < (cast(:after as timestamptz), cast(:afterId as uuid)))
                 order by starts_at desc, id desc limit :limit""")
                .param("state", state).param("after", Sql.ts(afterStartsAt)).param("afterId", afterId)
                .param("limit", limit).query(CabStore::meeting).list();
    }

    UUID insert(String title, Instant startsAt, int duration, String location, UUID chairId, List<UUID> members) {
        UUID id = Ids.newId();
        UUID actor = CurrentActor.userId().orElse(null);
        jdbc.sql("""
                insert into cab_meeting (id, tenant_id, title, starts_at, duration_minutes, location, chair_id,
                    member_ids, state, created_at, created_by, updated_at, updated_by, version)
                values (:id, :tenant, :title, :starts, :duration, :location, :chair, cast(:members as uuid[]),
                    'planned', now(), :actor, now(), :actor, 0)""")
                .param("id", id).param("tenant", TenantContext.require()).param("title", title)
                .param("starts", Sql.ts(startsAt)).param("duration", duration).param("location", location)
                .param("chair", chairId).param("members", Sql.array(members)).param("actor", actor).update();
        return id;
    }

    void update(Meeting m) {
        jdbc.sql("""
                update cab_meeting set title = :title, starts_at = :starts, duration_minutes = :duration,
                    location = :location, chair_id = :chair, member_ids = cast(:members as uuid[]), state = :state,
                    minutes = :minutes, started_at = :startedAt, completed_at = :completedAt,
                    protocol_sent_at = :sentAt, updated_at = now(), updated_by = :actor, version = version + 1
                 where id = :id""")
                .param("id", m.id()).param("title", m.title()).param("starts", Sql.ts(m.startsAt()))
                .param("duration", m.durationMinutes()).param("location", m.location()).param("chair", m.chairId())
                .param("members", Sql.array(m.memberIds())).param("state", m.state()).param("minutes", m.minutes())
                .param("startedAt", Sql.ts(m.startedAt())).param("completedAt", Sql.ts(m.completedAt()))
                .param("sentAt", Sql.ts(m.protocolSentAt())).param("actor", CurrentActor.userId().orElse(null))
                .update();
    }

    // ---------------------------------------------------------------- повестка

    List<Item> items(UUID meetingId) {
        return jdbc.sql("select * from cab_item where meeting_id = :id order by seq, id").param("id", meetingId)
                .query(CabStore::item).list();
    }

    Optional<Item> item(UUID itemId) {
        return jdbc.sql("select * from cab_item where id = :id").param("id", itemId).query(CabStore::item).optional();
    }

    /** Пункты повестки изменения во всех заседаниях: для вкладки «Согласования». */
    List<Item> itemsOfChange(UUID changeId) {
        return jdbc.sql("select * from cab_item where change_id = :id order by created_at, id").param("id", changeId)
                .query(CabStore::item).list();
    }

    /** @return false — изменение уже в повестке */
    boolean addItem(UUID meetingId, UUID changeId) {
        Integer next = jdbc.sql("select coalesce(max(seq), 0) + 1 from cab_item where meeting_id = :id")
                .param("id", meetingId).query(Integer.class).single();
        UUID actor = CurrentActor.userId().orElse(null);
        return jdbc.sql("""
                insert into cab_item (id, tenant_id, meeting_id, change_id, seq, decision, created_at, created_by,
                    updated_at, updated_by, version)
                values (:id, :tenant, :meeting, :change, :seq, 'pending', now(), :actor, now(), :actor, 0)
                on conflict (meeting_id, change_id) do nothing""")
                .param("id", Ids.newId()).param("tenant", TenantContext.require()).param("meeting", meetingId)
                .param("change", changeId).param("seq", next).param("actor", actor).update() > 0;
    }

    boolean removeItem(UUID itemId) {
        return jdbc.sql("delete from cab_item where id = :id and decision = 'pending'").param("id", itemId).update() > 0;
    }

    void decide(UUID itemId, String decision, String comment, UUID by, Instant at) {
        jdbc.sql("""
                update cab_item set decision = :decision, comment = :comment, decided_by = :by, decided_at = :at,
                    updated_at = :at, updated_by = :by, version = version + 1
                 where id = :id""")
                .param("id", itemId).param("decision", decision).param("comment", comment).param("by", by)
                .param("at", Sql.ts(at)).update();
    }

    /**
     * Изменения, которые ждут решения CAB и ещё не стоят в повестке незавершённого заседания: для «Собрать
     * повестку».
     */
    List<UUID> awaitingCab() {
        return jdbc.sql("""
                select distinct a.change_id, c.window_start from change_approval a
                  join change_detail c on c.ticket_id = a.change_id
                 where a.kind = 'cab' and a.decision = 'pending'
                   and not exists (select 1 from cab_item i join cab_meeting m on m.id = i.meeting_id
                                    where i.change_id = a.change_id and i.decision = 'pending'
                                      and m.state in ('planned', 'in_progress'))
                 order by c.window_start nulls last, a.change_id""")
                .query((rs, n) -> Sql.uuid(rs, "change_id")).list();
    }

    /** Пользователь — председатель или участник заседания, в повестке которого есть изменение. */
    boolean isMemberForChange(UUID changeId, UUID userId) {
        return jdbc.sql("""
                select count(*) from cab_item i join cab_meeting m on m.id = i.meeting_id
                 where i.change_id = :change and (m.chair_id = :user or :user = any(m.member_ids))""")
                .param("change", changeId).param("user", userId).query(Long.class).single() > 0;
    }

    // ---------------------------------------------------------------- голоса

    List<Vote> votes(UUID meetingId) {
        return jdbc.sql("""
                select v.* from cab_vote v join cab_item i on i.id = v.item_id
                 where i.meeting_id = :id order by v.voted_at""")
                .param("id", meetingId).query(CabStore::vote).list();
    }

    void vote(UUID itemId, UUID voterId, String vote, String comment) {
        jdbc.sql("""
                insert into cab_vote (tenant_id, item_id, voter_id, vote, comment, voted_at)
                values (:tenant, :item, :voter, :vote, :comment, now())
                on conflict (item_id, voter_id) do update set vote = excluded.vote, comment = excluded.comment,
                    voted_at = excluded.voted_at""")
                .param("tenant", TenantContext.require()).param("item", itemId).param("voter", voterId)
                .param("vote", vote).param("comment", comment).update();
    }

    private static Meeting meeting(ResultSet rs, int n) throws SQLException {
        return new Meeting(Sql.uuid(rs, "id"), rs.getString("title"), Sql.instant(rs, "starts_at"),
                rs.getInt("duration_minutes"), rs.getString("location"), Sql.uuid(rs, "chair_id"),
                Sql.uuids(rs, "member_ids"), rs.getString("state"), rs.getString("minutes"),
                Sql.instant(rs, "started_at"), Sql.instant(rs, "completed_at"), Sql.instant(rs, "protocol_sent_at"),
                Sql.instant(rs, "created_at"), rs.getLong("version"));
    }

    private static Item item(ResultSet rs, int n) throws SQLException {
        return new Item(Sql.uuid(rs, "id"), Sql.uuid(rs, "meeting_id"), Sql.uuid(rs, "change_id"), rs.getInt("seq"),
                rs.getString("decision"), rs.getString("comment"), Sql.uuid(rs, "decided_by"),
                Sql.instant(rs, "decided_at"));
    }

    private static Vote vote(ResultSet rs, int n) throws SQLException {
        return new Vote(Sql.uuid(rs, "item_id"), Sql.uuid(rs, "voter_id"), rs.getString("vote"),
                rs.getString("comment"), Sql.instant(rs, "voted_at"));
    }
}
