package ru.easyitsm.assets.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.relational.core.mapping.Table;
import ru.easyitsm.platform.persistence.TenantEntity;
import ru.easyitsm.platform.web.ApiException;

/** Договор с поставщиком (AST-04): сроки, сумма, ответственный и напоминание о продлении. */
@Table("asset_contract")
class Contract extends TenantEntity {

    private String number;
    private String title;
    private String kind;
    private UUID vendorId;
    private LocalDate startsOn;
    private LocalDate endsOn;
    private int renewalNoticeDays;
    private boolean autoRenew;
    private BigDecimal amount;
    private String currency;
    private UUID ownerId;
    private String notes;
    private boolean terminated;
    private LocalDate remindedFor;

    protected Contract() {
    }

    Contract(String number, String title, String kind, LocalDate startsOn) {
        this.number = number;
        this.title = title;
        this.kind = kind;
        this.startsOn = startsOn;
        this.renewalNoticeDays = 30;
        this.currency = "RUB";
        this.notes = "";
    }

    void apply(AssetDtos.ContractFields f) {
        if (f.number() != null) {
            number = f.number().strip();
        }
        if (f.title() != null) {
            title = f.title().strip();
        }
        if (f.kind() != null) {
            kind = f.kind();
        }
        vendorId = f.clears("vendorId") ? null : f.vendorId() != null ? f.vendorId() : vendorId;
        if (f.startsOn() != null) {
            startsOn = f.startsOn();
        }
        endsOn = f.clears("endsOn") ? null : f.endsOn() != null ? f.endsOn() : endsOn;
        if (f.renewalNoticeDays() != null) {
            renewalNoticeDays = f.renewalNoticeDays();
        }
        if (f.autoRenew() != null) {
            autoRenew = f.autoRenew();
        }
        amount = f.clears("amount") ? null : f.amount() != null ? f.amount() : amount;
        if (f.currency() != null) {
            currency = f.currency();
        }
        ownerId = f.clears("ownerId") ? null : f.ownerId() != null ? f.ownerId() : ownerId;
        if (f.notes() != null) {
            notes = f.notes();
        }
        if (f.terminated() != null) {
            terminated = f.terminated();
        }
        if (endsOn != null && endsOn.isBefore(startsOn)) {
            throw ApiException.badRequest("invalid_period", "Окончание договора раньше начала");
        }
    }

    void markReminded() {
        this.remindedFor = endsOn;
    }

    String number() {
        return number;
    }

    String title() {
        return title;
    }

    String kind() {
        return kind;
    }

    UUID vendorId() {
        return vendorId;
    }

    LocalDate startsOn() {
        return startsOn;
    }

    LocalDate endsOn() {
        return endsOn;
    }

    int renewalNoticeDays() {
        return renewalNoticeDays;
    }

    boolean autoRenew() {
        return autoRenew;
    }

    BigDecimal amount() {
        return amount;
    }

    String currency() {
        return currency;
    }

    UUID ownerId() {
        return ownerId;
    }

    String notes() {
        return notes;
    }

    boolean terminated() {
        return terminated;
    }

    LocalDate remindedFor() {
        return remindedFor;
    }

    /** Состояние на дату: active, expiring (наступил срок напоминания), expired, terminated. */
    String status(LocalDate today) {
        if (terminated) {
            return "terminated";
        }
        if (endsOn == null) {
            return "active";
        }
        if (endsOn.isBefore(today)) {
            return "expired";
        }
        return endsOn.minusDays(renewalNoticeDays).isAfter(today) ? "active" : "expiring";
    }
}
