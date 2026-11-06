package ru.easyitsm.sla.internal;

import java.time.Instant;
import java.util.UUID;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Интервал, исключённый из счёта (SLA-04): пауза по статусу с причиной или период, когда заявка была решена
 * и затем возвращена в работу. Хранится в {@code sla_pause} внутри агрегата {@link SlaInstance}.
 */
class SlaPause {

    static final String PAUSE = "pause";
    static final String RESOLVED = "resolved";

    @SuppressWarnings("unused")
    private UUID tenantId;
    private String kind;
    private Instant startedAt;
    private Instant endedAt;
    private String reason;

    protected SlaPause() {
    }

    SlaPause(String kind, Instant startedAt, Instant endedAt, String reason) {
        this.tenantId = TenantContext.require();
        this.kind = kind;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.reason = reason;
    }

    SlaPause copy() {
        return new SlaPause(kind, startedAt, endedAt, reason);
    }

    boolean open() {
        return endedAt == null;
    }

    void close(Instant at) {
        this.endedAt = at.isBefore(startedAt) ? startedAt : at;
    }

    String kind() {
        return kind;
    }

    Instant startedAt() {
        return startedAt;
    }

    Instant endedAt() {
        return endedAt;
    }

    String reason() {
        return reason;
    }

    SlaViews.Pause toView() {
        return new SlaViews.Pause(kind, startedAt, endedAt, reason);
    }
}
