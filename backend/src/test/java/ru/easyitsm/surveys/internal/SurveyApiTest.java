package ru.easyitsm.surveys.internal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ru.easyitsm.platform.id.Ids;

/** Опрос удовлетворённости (PRT-06): один раз, только участник, только после закрытия. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SurveyApiTest {

    static final String DEMO = "01930000-0000-7000-8000-000000000001";
    static final String OTHER = "01930000-0000-7000-8000-000000000002";

    static class FakeLookup implements TicketLookup {
        final Map<UUID, TicketFacts> tickets = new ConcurrentHashMap<>();

        @Override
        public Optional<TicketFacts> find(UUID id) {
            return Optional.ofNullable(tickets.get(id));
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeLookup fakeLookup() {
            return new FakeLookup();
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    FakeLookup lookup;

    @Test
    void requesterRatesClosedTicketOnce() throws Exception {
        UUID requester = Ids.newId();
        UUID ticket = ticket(requester, "closed");
        submit(requester, ticket, 5, "Быстро и вежливо").andExpect(status().isCreated())
                .andExpect(jsonPath("$.score").value(5));
        submit(requester, ticket, 1, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("already_rated"));
        mvc.perform(get("/api/v1/surveys/" + ticket).header("X-Dev-User", requester.toString())
                        .header("X-Dev-Tenant", DEMO).header("X-Dev-Roles", "REQ"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.comment").value("Быстро и вежливо"));
        mvc.perform(get("/api/v1/surveys/" + ticket).header("X-Dev-User", Ids.newId().toString())
                        .header("X-Dev-Tenant", DEMO).header("X-Dev-Roles", "AGT"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/surveys/" + ticket).header("X-Dev-User", Ids.newId().toString())
                        .header("X-Dev-Tenant", DEMO).header("X-Dev-Roles", "REQ"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/surveys/" + ticket).header("X-Dev-User", requester.toString())
                        .header("X-Dev-Tenant", OTHER).header("X-Dev-Roles", "REQ"))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyClosedTicketsOfParticipants() throws Exception {
        UUID requester = Ids.newId();
        submit(requester, ticket(requester, "resolved"), 4, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ticket_not_closed"));
        submit(Ids.newId(), ticket(requester, "closed"), 4, null).andExpect(status().isNotFound());
        submit(requester, Ids.newId(), 4, null).andExpect(status().isNotFound());
        submit(requester, ticket(requester, "closed"), 6, null).andExpect(status().isBadRequest());
    }

    private UUID ticket(UUID requester, String category) {
        UUID id = Ids.newId();
        lookup.tickets.put(id, new TicketLookup.TicketFacts(id, requester, null, category));
        return id;
    }

    private ResultActions submit(UUID user, UUID ticket, int score, String comment) throws Exception {
        String body = "{\"ticketId\":\"" + ticket + "\",\"score\":" + score
                + (comment == null ? "" : ",\"comment\":\"" + comment + "\"") + "}";
        return mvc.perform(post("/api/v1/surveys").header("X-Dev-User", user.toString()).header("X-Dev-Tenant", DEMO)
                .header("X-Dev-Roles", "REQ").contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
