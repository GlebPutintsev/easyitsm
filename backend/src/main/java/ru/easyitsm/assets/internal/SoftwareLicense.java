package ru.easyitsm.assets.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.relational.core.mapping.Table;
import ru.easyitsm.platform.persistence.TenantEntity;
import ru.easyitsm.platform.web.ApiException;

/**
 * Лицензия ПО (AST-03): право на продукт по метрике и количеству, срок действия. Использование — назначения
 * на устройства или сотрудников ({@code license_assignment}).
 */
@Table("software_license")
class SoftwareLicense extends TenantEntity {

    private String product;
    private String publisher;
    private String metric;
    private Integer quantity;
    private LocalDate validFrom;
    private LocalDate validTo;
    private int renewalNoticeDays;
    private UUID vendorId;
    private UUID contractId;
    private BigDecimal cost;
    private String currency;
    private UUID ownerId;
    private String notes;
    private LocalDate remindedFor;

    protected SoftwareLicense() {
    }

    SoftwareLicense(String product, String metric) {
        this.product = product;
        this.metric = metric;
        this.renewalNoticeDays = 30;
        this.currency = "RUB";
        this.notes = "";
    }

    void apply(AssetDtos.LicenseFields f) {
        if (f.product() != null) {
            product = f.product().strip();
        }
        publisher = f.clears("publisher") ? null : f.publisher() != null ? AssetDtos.blankToNull(f.publisher()) : publisher;
        if (f.metric() != null) {
            metric = f.metric();
        }
        quantity = f.clears("quantity") ? null : f.quantity() != null ? f.quantity() : quantity;
        validFrom = f.clears("validFrom") ? null : f.validFrom() != null ? f.validFrom() : validFrom;
        validTo = f.clears("validTo") ? null : f.validTo() != null ? f.validTo() : validTo;
        if (f.renewalNoticeDays() != null) {
            renewalNoticeDays = f.renewalNoticeDays();
        }
        vendorId = f.clears("vendorId") ? null : f.vendorId() != null ? f.vendorId() : vendorId;
        contractId = f.clears("contractId") ? null : f.contractId() != null ? f.contractId() : contractId;
        cost = f.clears("cost") ? null : f.cost() != null ? f.cost() : cost;
        if (f.currency() != null) {
            currency = f.currency();
        }
        ownerId = f.clears("ownerId") ? null : f.ownerId() != null ? f.ownerId() : ownerId;
        if (f.notes() != null) {
            notes = f.notes();
        }
        if (validFrom != null && validTo != null && validTo.isBefore(validFrom)) {
            throw ApiException.badRequest("invalid_period", "Окончание лицензии раньше начала");
        }
    }

    /** Ограничено ли использование количеством: на устройство и на пользователя. Конкурентные считаются отдельно. */
    boolean enforcesQuantity() {
        return quantity != null && ("device".equals(metric) || "user".equals(metric) || "subscription".equals(metric));
    }

    void markReminded() {
        this.remindedFor = validTo;
    }

    String product() {
        return product;
    }

    String publisher() {
        return publisher;
    }

    String metric() {
        return metric;
    }

    Integer quantity() {
        return quantity;
    }

    LocalDate validFrom() {
        return validFrom;
    }

    LocalDate validTo() {
        return validTo;
    }

    int renewalNoticeDays() {
        return renewalNoticeDays;
    }

    UUID vendorId() {
        return vendorId;
    }

    UUID contractId() {
        return contractId;
    }

    BigDecimal cost() {
        return cost;
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

    /** Состояние на дату: active, expiring, expired. */
    String status(LocalDate today) {
        if (validTo == null) {
            return "active";
        }
        if (validTo.isBefore(today)) {
            return "expired";
        }
        return validTo.minusDays(renewalNoticeDays).isAfter(today) ? "active" : "expiring";
    }
}
