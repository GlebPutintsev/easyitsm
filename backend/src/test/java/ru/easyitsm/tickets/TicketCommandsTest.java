package ru.easyitsm.tickets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;
import ru.easyitsm.tickets.api.Channel;
import ru.easyitsm.tickets.api.EscalationKind;
import ru.easyitsm.tickets.api.NewTicket;
import ru.easyitsm.tickets.api.StatusCategory;
import ru.easyitsm.tickets.api.TicketCommands;
import ru.easyitsm.tickets.api.TicketQueries;
import ru.easyitsm.tickets.api.TicketRef;
import ru.easyitsm.tickets.api.TicketType;
import ru.easyitsm.tickets.api.Visibility;

/** Java-контракт для других модулей: каталог, почта, SLA, а также таймеры автозакрытия. */
@SpringBootTest
@ActiveProfiles("test")
class TicketCommandsTest {

    static final UUID DEMO = UUID.fromString("01930000-0000-7000-8000-000000000001");

    @Autowired
    TicketCommands commands;
    @Autowired
    TicketQueries queries;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    org.springframework.context.ApplicationContext ctx;

    <T> T inDemo(java.util.concurrent.Callable<T> action) throws Exception {
        return TenantContext.callAs(DEMO, action);
    }

    @Test
    void catalogRequestGoesThroughApproval() throws Exception {
        UUID requester = Ids.newId();
        TicketRef ref = inDemo(() -> commands.create(new NewTicket(TicketType.REQUEST, "Новый ноутбук", "Для стажёра",
                requester, null, null, Channel.PORTAL, null, null, null, null, null, Ids.newId(), null, null,
                Map.of(), true, requester)));
        assertThat(ref.number()).startsWith("REQ-");
        assertThat(ref.statusCode()).isEqualTo("pending_approval");
        assertThat(ref.statusCategory()).isEqualTo(StatusCategory.APPROVAL);

        assertThatThrownBy(() -> inDemo(() -> commands.applyApprovalOutcome(ref.id(), false, null)))
                .hasMessageContaining("причину");
        TicketRef approved = inDemo(() -> commands.applyApprovalOutcome(ref.id(), true, null));
        assertThat(approved.statusCode()).isEqualTo("approved");
        assertThat(inDemo(() -> queries.find(ref.id())).orElseThrow().requesterId()).isEqualTo(requester);
        assertThat(inDemo(() -> queries.findByNumber(ref.number()))).isPresent();
    }

    @Test
    void emailReplyReopensResolvedTicketAndSlaDueIsStored() throws Exception {
        UUID requester = Ids.newId();
        TicketRef ref = inDemo(() -> commands.create(NewTicket.of(TicketType.INCIDENT, "Письмо", "", requester,
                Channel.EMAIL)));
        inDemo(() -> {
            jdbc.sql("update ticket set status = 'resolved', status_category = 'resolved', resolved_at = now() where id = :id")
                    .param("id", ref.id()).update();
            return null;
        });
        inDemo(() -> commands.addComment(ref.id(), requester, "Не помогло", Visibility.PUBLIC));
        assertThat(inDemo(() -> queries.find(ref.id())).orElseThrow().statusCategory()).isEqualTo(StatusCategory.IN_PROGRESS);

        Instant due = Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        inDemo(() -> {
            commands.updateSlaDue(ref.id(), due);
            commands.updateSlaDue(Ids.newId(), due); // неизвестная заявка — без ошибки
            return null;
        });
        Instant stored = inDemo(() -> jdbc.sql("select sla_due_at from ticket where id = :id").param("id", ref.id())
                .query(java.time.OffsetDateTime.class).single().toInstant());
        assertThat(stored).isEqualTo(due);
    }

    @Test
    void slaEscalatesFunctionally() throws Exception {
        UUID group = Ids.newId();
        TicketRef ref = inDemo(() -> commands.create(NewTicket.of(TicketType.INCIDENT, "Эскалация", "", Ids.newId(),
                Channel.MONITORING)));
        TicketRef escalated = inDemo(() -> commands.escalate(ref.id(), EscalationKind.FUNCTIONAL, group,
                "Нарушен срок реакции", null));
        assertThat(escalated.statusCode()).isEqualTo("assigned");
        assertThat(inDemo(() -> queries.find(ref.id())).orElseThrow().groupId()).isEqualTo(group);
    }

    @Test
    void autoCloseAfterBusinessDays() throws Exception {
        TicketRef ref = inDemo(() -> commands.create(NewTicket.of(TicketType.INCIDENT, "Автозакрытие", "", Ids.newId(),
                Channel.PORTAL)));
        inDemo(() -> jdbc.sql("""
                update ticket set status = 'resolved', status_category = 'resolved',
                       resolved_at = now() - interval '10 days' where id = :id""").param("id", ref.id()).update());
        Object job = ctx.getBean(Class.forName("ru.easyitsm.tickets.internal.TicketTimersJob"));
        var run = job.getClass().getDeclaredMethod("runOnce");
        run.setAccessible(true);
        run.invoke(job);
        var closed = inDemo(() -> queries.find(ref.id())).orElseThrow();
        assertThat(closed.statusCategory()).isEqualTo(StatusCategory.CLOSED);
        assertThat(closed.closedAt()).isNotNull();
    }
}
