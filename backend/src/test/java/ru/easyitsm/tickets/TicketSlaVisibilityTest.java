package ru.easyitsm.tickets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Счётчики SLA видны только вместе с заявкой (ACC-01, AT-01): агент чужой группы получает 404 и на список
 * счётчиков заявки, и на отдельный счётчик.
 */
class TicketSlaVisibilityTest extends TicketApiSupport {

    @Test
    void slaInstancesFollowTicketVisibility() throws Exception {
        String id = read(createIncident("SLA видна только своей группе"), "$.id");
        String outsider = Ids.newId().toString();

        perform(asAgent(get("/api/v1/sla/instances").param("ticketId", id))).andExpect(status().isOk());
        perform(as(get("/api/v1/sla/instances").param("ticketId", id), outsider, "AGT"))
                .andExpect(status().isNotFound());
        perform(as(get("/api/v1/sla/instances").param("ticketId", id), outsider, "AUD"))
                .andExpect(status().isOk());

        List<UUID> instances = TenantContext.callAs(UUID.fromString(tenant), () ->
                jdbc.sql("select id from sla_instance where ticket_id = cast(:t as uuid)").param("t", id)
                        .query(UUID.class).list());
        for (UUID instance : instances) {
            perform(asAgent(get("/api/v1/sla/instances/" + instance))).andExpect(status().isOk());
            perform(as(get("/api/v1/sla/instances/" + instance), outsider, "AGT")).andExpect(status().isNotFound());
        }
    }
}
