package ru.easyitsm.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static ru.easyitsm.knowledge.KnowledgeApiSupport.API;
import static ru.easyitsm.knowledge.KnowledgeApiSupport.DEMO;
import static ru.easyitsm.knowledge.KnowledgeApiSupport.uniq;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import ru.easyitsm.knowledge.KnowledgeApiSupport;
import ru.easyitsm.knowledge.KnowledgeApiSupport.User;
import ru.easyitsm.knowledge.events.ArticleReviewDue;
import ru.easyitsm.knowledge.events.ArticleReviewReminder;
import ru.easyitsm.platform.tenant.TenantContext;

/** Срок пересмотра (KB-03): напоминание заранее и перевод в «На пересмотре» в срок. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@RecordApplicationEvents
class ArticleReviewJobTest {

    static final User AGENT = User.of("AGT");
    static final User KNW = User.of("KNW");

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ArticleReviewJob job;
    @Autowired
    ApplicationEvents events;

    @Test
    void dueArticleMovesToReviewDueAndUpcomingGetsOneReminder() throws Exception {
        var kb = new KnowledgeApiSupport(mvc);
        String due = kb.published(AGENT, KNW, uniq("Срок прошёл"), "Текст", "all");
        String soon = kb.published(AGENT, KNW, uniq("Срок скоро"), "Текст", "all");
        String later = kb.published(AGENT, KNW, uniq("Срок не скоро"), "Текст", "all");
        setReviewAt(due, "now() - interval '1 day'");
        setReviewAt(soon, "now() + interval '3 days'");

        job.run();
        job.run();

        kb.perform(AGENT, get(API + "/" + due)).andExpect(jsonPath("$.status").value("review_due"));
        kb.perform(AGENT, get(API + "/" + soon)).andExpect(jsonPath("$.status").value("published"));
        kb.perform(AGENT, get(API + "/" + later)).andExpect(jsonPath("$.status").value("published"));
        assertThat(events.stream(ArticleReviewDue.class).filter(e -> e.articleId().toString().equals(due))).hasSize(1);
        assertThat(events.stream(ArticleReviewReminder.class).filter(e -> e.articleId().toString().equals(soon)))
                .hasSize(1);
        assertThat(events.stream(ArticleReviewReminder.class).filter(e -> e.articleId().toString().equals(later)))
                .isEmpty();
    }

    private void setReviewAt(String id, String expr) {
        TenantContext.runAs(UUID.fromString(DEMO), () -> jdbc.sql("update article set review_at = " + expr
                + " where id = :id").param("id", UUID.fromString(id)).update());
    }
}
