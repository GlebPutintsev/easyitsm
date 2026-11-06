package ru.easyitsm.assets.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.relational.core.mapping.Table;
import ru.easyitsm.assets.api.AssetState;
import ru.easyitsm.assets.api.AssetSummary;
import ru.easyitsm.platform.persistence.TenantEntity;
import ru.easyitsm.platform.web.ApiException;

/**
 * Единица оборудования (AST-01). Состояние меняется только действиями из раздела 10.14 ТЗ: выдать, вернуть,
 * передать, в ремонт, списать. Недопустимый переход — 409 {@code invalid_state}.
 */
@Table("asset")
class Asset extends TenantEntity {

    private String tag;
    private String name;
    private String kind;
    private String manufacturer;
    private String model;
    private String serial;
    private String state;
    private UUID holderId;
    private UUID locationId;
    private UUID ciId;
    private UUID vendorId;
    private UUID contractId;
    private LocalDate purchasedOn;
    private LocalDate warrantyTo;
    private Integer serviceLifeMonths;
    private BigDecimal cost;
    private String currency;
    private String notes;
    private String retireReason;
    private Instant retiredAt;

    protected Asset() {
    }

    Asset(String tag, String name, String kind) {
        this.tag = tag;
        this.name = name;
        this.kind = kind;
        this.state = AssetState.IN_STOCK.code();
        this.currency = "RUB";
        this.notes = "";
    }

    AssetState state() {
        return AssetState.fromCode(state);
    }

    // ------------------------------------------------------------------ действия

    /** Выдать сотруднику: со склада или из ремонта. */
    void issue(UUID holder, UUID location) {
        require(state() == AssetState.IN_STOCK || state() == AssetState.IN_REPAIR, "Выдать можно со склада или из ремонта");
        this.holderId = holder;
        this.state = AssetState.IN_USE.code();
        if (location != null) {
            this.locationId = location;
        }
    }

    /** Вернуть на склад: от сотрудника или из ремонта. */
    void giveBack(UUID location) {
        require(state() == AssetState.IN_USE || state() == AssetState.IN_REPAIR, "Вернуть можно выданный актив или из ремонта");
        this.holderId = null;
        this.state = AssetState.IN_STOCK.code();
        if (location != null) {
            this.locationId = location;
        }
    }

    /** Передать другому сотруднику без возврата на склад. */
    void transfer(UUID holder, UUID location) {
        require(state() == AssetState.IN_USE, "Передать можно только выданный актив");
        if (holder.equals(holderId)) {
            throw ApiException.badRequest("same_holder", "Актив уже у этого сотрудника");
        }
        this.holderId = holder;
        if (location != null) {
            this.locationId = location;
        }
    }

    /** Отдать в ремонт. Сотрудник остаётся, чтобы после ремонта вернуть актив ему. */
    void sendToRepair() {
        require(state() == AssetState.IN_STOCK || state() == AssetState.IN_USE, "В ремонт можно отдать актив со склада или от сотрудника");
        this.state = AssetState.IN_REPAIR.code();
    }

    /** Списать с обязательной причиной. */
    void retire(String reason, Instant at) {
        require(state() != AssetState.RETIRED, "Актив уже списан");
        this.holderId = null;
        this.state = AssetState.RETIRED.code();
        this.retireReason = reason;
        this.retiredAt = at;
    }

    private static void require(boolean ok, String detail) {
        if (!ok) {
            throw ApiException.conflict("invalid_state", detail);
        }
    }

    // ------------------------------------------------------------------ поля

    void setTag(String tag) {
        this.tag = tag;
    }

    void setName(String name) {
        this.name = name;
    }

    void setKind(String kind) {
        this.kind = kind;
    }

    void setManufacturer(String manufacturer) {
        this.manufacturer = manufacturer;
    }

    void setModel(String model) {
        this.model = model;
    }

    void setSerial(String serial) {
        this.serial = serial;
    }

    void setLocationId(UUID locationId) {
        this.locationId = locationId;
    }

    void setCiId(UUID ciId) {
        this.ciId = ciId;
    }

    void setVendorId(UUID vendorId) {
        this.vendorId = vendorId;
    }

    void setContractId(UUID contractId) {
        this.contractId = contractId;
    }

    void setPurchasedOn(LocalDate purchasedOn) {
        this.purchasedOn = purchasedOn;
    }

    void setWarrantyTo(LocalDate warrantyTo) {
        this.warrantyTo = warrantyTo;
    }

    void setServiceLifeMonths(Integer serviceLifeMonths) {
        this.serviceLifeMonths = serviceLifeMonths;
    }

    void setCost(BigDecimal cost) {
        this.cost = cost;
    }

    void setCurrency(String currency) {
        this.currency = currency;
    }

    void setNotes(String notes) {
        this.notes = notes == null ? "" : notes;
    }

    String tag() {
        return tag;
    }

    String name() {
        return name;
    }

    String kind() {
        return kind;
    }

    String manufacturer() {
        return manufacturer;
    }

    String model() {
        return model;
    }

    String serial() {
        return serial;
    }

    UUID holderId() {
        return holderId;
    }

    UUID locationId() {
        return locationId;
    }

    UUID ciId() {
        return ciId;
    }

    UUID vendorId() {
        return vendorId;
    }

    UUID contractId() {
        return contractId;
    }

    LocalDate purchasedOn() {
        return purchasedOn;
    }

    LocalDate warrantyTo() {
        return warrantyTo;
    }

    Integer serviceLifeMonths() {
        return serviceLifeMonths;
    }

    BigDecimal cost() {
        return cost;
    }

    String currency() {
        return currency;
    }

    String notes() {
        return notes;
    }

    String retireReason() {
        return retireReason;
    }

    Instant retiredAt() {
        return retiredAt;
    }

    /** Конец срока службы: дата покупки плюс срок службы. */
    LocalDate endOfLife() {
        return purchasedOn == null || serviceLifeMonths == null ? null : purchasedOn.plusMonths(serviceLifeMonths);
    }

    AssetSummary toSummary() {
        return new AssetSummary(getId(), tag, name, kind, state(), holderId, ciId);
    }
}
