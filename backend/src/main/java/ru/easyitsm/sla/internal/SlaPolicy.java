package ru.easyitsm.sla.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;
import ru.easyitsm.platform.persistence.TenantEntity;
import ru.easyitsm.platform.web.ApiException;

/**
 * Политика SLA, OLA или UC (SLA-01) с условиями применения (SLA-02), графиком (SLA-03),
 * статусами паузы (SLA-04) и целями по приоритетам.
 * <p>
 * Для заявки в каждом виде (sla, ola, uc) действует одна политика: первая подходящая по {@code rank}.
 * Пустой список условия означает «любое значение».
 */
@Table("sla_policy")
class SlaPolicy extends TenantEntity {

    static final List<String> KINDS = List.of("sla", "ola", "uc");

    private String name;
    private String description;
    private String kind;
    private boolean active;
    private int rank;
    private UUID scheduleId;
    private List<String> ticketTypes = new ArrayList<>();
    private List<String> priorities = new ArrayList<>();
    private List<UUID> serviceIds = new ArrayList<>();
    private List<UUID> clientIds = new ArrayList<>();
    private List<UUID> groupIds = new ArrayList<>();
    private List<String> pauseStatuses = new ArrayList<>();
    @MappedCollection(idColumn = "policy_id", keyColumn = "seq")
    private List<SlaTarget> targets = new ArrayList<>();

    protected SlaPolicy() {
    }

    SlaPolicy(String name, String kind) {
        this.name = name;
        this.kind = kind;
        this.description = "";
        this.active = true;
        this.rank = 100;
        this.pauseStatuses = new ArrayList<>(List.of(StatusCategory.PENDING));
    }

    boolean matches(TicketFacts f) {
        return active
                && anyOf(ticketTypes, f.type())
                && anyOf(priorities, f.priority())
                && anyOf(serviceIds, f.serviceId())
                && anyOf(clientIds, f.clientId())
                && anyOf(groupIds, f.groupId());
    }

    boolean pausesOn(String statusCode, String statusCategory) {
        return pauseStatuses.contains(statusCategory) || pauseStatuses.contains(statusCode);
    }

    List<SlaTarget> targetsFor(String priority) {
        return targets.stream().filter(t -> t.priority().equals(priority)).toList();
    }

    Optional<SlaTarget> target(String metric, String priority) {
        return targets.stream().filter(t -> t.metric().equals(metric) && t.priority().equals(priority)).findFirst();
    }

    private static <T> boolean anyOf(List<T> allowed, T value) {
        return allowed.isEmpty() || (value != null && allowed.contains(value));
    }

    void setName(String name) {
        this.name = name;
    }

    void setDescription(String description) {
        this.description = description == null ? "" : description;
    }

    void setActive(boolean active) {
        this.active = active;
    }

    void setRank(int rank) {
        this.rank = rank;
    }

    void setScheduleId(UUID scheduleId) {
        this.scheduleId = scheduleId;
    }

    void setConditions(List<String> ticketTypes, List<String> priorities, List<UUID> serviceIds,
                       List<UUID> clientIds, List<UUID> groupIds) {
        if (ticketTypes != null) {
            this.ticketTypes = new ArrayList<>(ticketTypes);
        }
        if (priorities != null) {
            this.priorities = new ArrayList<>(priorities);
        }
        if (serviceIds != null) {
            this.serviceIds = new ArrayList<>(serviceIds);
        }
        if (clientIds != null) {
            this.clientIds = new ArrayList<>(clientIds);
        }
        if (groupIds != null) {
            this.groupIds = new ArrayList<>(groupIds);
        }
    }

    void setPauseStatuses(List<String> pauseStatuses) {
        this.pauseStatuses = new ArrayList<>(pauseStatuses);
    }

    void setTargets(List<SlaTarget> targets) {
        long distinct = targets.stream().map(t -> t.metric() + "|" + t.priority()).distinct().count();
        if (distinct != targets.size()) {
            throw ApiException.badRequest("duplicate_target", "Для показателя и приоритета может быть только одна цель");
        }
        this.targets = new ArrayList<>(targets);
    }

    String name() {
        return name;
    }

    String kind() {
        return kind;
    }

    boolean active() {
        return active;
    }

    int rank() {
        return rank;
    }

    UUID scheduleId() {
        return scheduleId;
    }

    List<SlaTarget> targets() {
        return targets;
    }

    SlaViews.Policy toView() {
        return new SlaViews.Policy(getId(), name, description, kind, active, rank, scheduleId,
                new SlaViews.Conditions(ticketTypes, priorities, serviceIds, clientIds, groupIds),
                pauseStatuses, targets.stream().map(SlaTarget::toView).toList(), getUpdatedAt(), getVersion());
    }
}
