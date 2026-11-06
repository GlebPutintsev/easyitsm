package ru.easyitsm.assets.internal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Команды и ответы REST модуля. Изменение частичное: null — «не менять», а поля из {@code clear} очищаются
 * (так форма убирает, например, серийный номер или ответственного).
 */
final class AssetDtos {

    static final String KINDS = "laptop|desktop|monitor|server|network|phone|tablet|printer|peripheral|other";
    static final String STATES = "in_stock|in_use|in_repair|retired";
    static final String CONTRACT_KINDS = "purchase|lease|support|license|service|other";
    static final String METRICS = "device|user|concurrent|site|subscription";
    static final String CURRENCY = "[A-Z]{3}";

    private AssetDtos() {
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    /** Частичное изменение с явным списком очищаемых полей. */
    interface Patch {
        Set<String> clear();

        default boolean clears(String field) {
            return clear() != null && clear().contains(field);
        }
    }

    // ------------------------------------------------------------------ оборудование

    record AssetFields(
            @Size(min = 1, max = 64) String tag,
            @Size(min = 1, max = 255) String name,
            @Pattern(regexp = KINDS) String kind,
            @Size(max = 200) String manufacturer,
            @Size(max = 200) String model,
            @Size(max = 200) String serial,
            UUID locationId,
            UUID ciId,
            UUID vendorId,
            UUID contractId,
            LocalDate purchasedOn,
            LocalDate warrantyTo,
            @Min(1) @Max(600) Integer serviceLifeMonths,
            @DecimalMin("0") BigDecimal cost,
            @Pattern(regexp = CURRENCY) String currency,
            @Size(max = 10_000) String notes,
            Set<@Pattern(regexp = "manufacturer|model|serial|locationId|ciId|vendorId|contractId|purchasedOn|"
                    + "warrantyTo|serviceLifeMonths|cost") String> clear) implements Patch {
    }

    /** Выдать или передать сотруднику. */
    record Assign(@NotNull UUID holderId, UUID locationId, @Size(max = 2000) String comment) {
    }

    /** Вернуть на склад или отдать в ремонт. */
    record Move(UUID locationId, @Size(max = 2000) String comment) {
    }

    record Retire(@NotBlank @Size(max = 2000) String reason) {
    }

    record LinkTicket(UUID ticketId, @Size(max = 32) String number) {
    }

    record Named(UUID id, String name) {
    }

    record CiInfo(UUID id, String name, String classCode, String className, String status) {
    }

    record AssetView(
            UUID id,
            String tag,
            String name,
            String kind,
            String manufacturer,
            String model,
            String serial,
            String state,
            Named holder,
            Named location,
            CiInfo ci,
            Named vendor,
            ContractRef contract,
            LocalDate purchasedOn,
            LocalDate warrantyTo,
            boolean warrantyExpired,
            Integer serviceLifeMonths,
            LocalDate endOfLife,
            boolean endOfLifeReached,
            BigDecimal cost,
            String currency,
            String notes,
            String retireReason,
            Instant retiredAt,
            Instant createdAt,
            Instant updatedAt,
            long version) {
    }

    record ContractRef(UUID id, String number, String title) {
    }

    /** Строка списка активов. */
    record AssetRow(UUID id, String tag, String name, String kind, String model, String serial, String state,
                    Named holder, Named location, LocalDate warrantyTo, BigDecimal cost, String currency) {
    }

    record AssetEventView(UUID id, String kind, String fromState, String toState, Named fromHolder, Named toHolder,
                          TicketInfo ticket, String comment, Named actor, Instant at) {
    }

    record TicketInfo(UUID id, String number, String title, String statusCategory) {
    }

    record AssetStats(long total, long inStock, long inUse, long inRepair, long retired, long warrantyExpiring,
                      long endOfLife, List<CostTotal> cost) {
    }

    record CostTotal(String currency, BigDecimal amount) {
    }

    // ------------------------------------------------------------------ справочники

    record LocationFields(@Size(min = 1, max = 200) String name, @Size(max = 500) String address, Boolean active) {
    }

    record LocationView(UUID id, String name, String address, boolean active, long version) {
    }

    record VendorFields(
            @Size(min = 1, max = 200) String name,
            @Pattern(regexp = "|\\d{10}|\\d{12}") String taxId,
            @Size(max = 200) String contactName,
            @Email @Size(max = 254) String email,
            @Size(max = 50) String phone,
            @Size(max = 500) @Pattern(regexp = "|https?://\\S+") String website,
            @Size(max = 10_000) String notes,
            Boolean active) {
    }

    record VendorView(UUID id, String name, String taxId, String contactName, String email, String phone,
                      String website, String notes, boolean active, long version) {
    }

    // ------------------------------------------------------------------ договоры

    record ContractFields(
            @Size(min = 1, max = 100) String number,
            @Size(min = 1, max = 255) String title,
            @Pattern(regexp = CONTRACT_KINDS) String kind,
            UUID vendorId,
            LocalDate startsOn,
            LocalDate endsOn,
            @Min(0) @Max(365) Integer renewalNoticeDays,
            Boolean autoRenew,
            @DecimalMin("0") BigDecimal amount,
            @Pattern(regexp = CURRENCY) String currency,
            UUID ownerId,
            @Size(max = 10_000) String notes,
            Boolean terminated,
            Set<@Pattern(regexp = "vendorId|endsOn|amount|ownerId") String> clear) implements Patch {
    }

    record ContractView(
            UUID id,
            String number,
            String title,
            String kind,
            Named vendor,
            LocalDate startsOn,
            LocalDate endsOn,
            Long daysLeft,
            int renewalNoticeDays,
            boolean autoRenew,
            BigDecimal amount,
            String currency,
            Named owner,
            String notes,
            boolean terminated,
            String status,
            LocalDate remindedFor,
            long assets,
            long licenses,
            long version) {
    }

    // ------------------------------------------------------------------ лицензии

    record LicenseFields(
            @Size(min = 1, max = 255) String product,
            @Size(max = 200) String publisher,
            @Pattern(regexp = METRICS) String metric,
            @Min(0) @Max(10_000_000) Integer quantity,
            LocalDate validFrom,
            LocalDate validTo,
            @Min(0) @Max(365) Integer renewalNoticeDays,
            UUID vendorId,
            UUID contractId,
            @DecimalMin("0") BigDecimal cost,
            @Pattern(regexp = CURRENCY) String currency,
            UUID ownerId,
            @Size(max = 10_000) String notes,
            Set<@Pattern(regexp = "publisher|quantity|validFrom|validTo|vendorId|contractId|cost|ownerId") String> clear)
            implements Patch {
    }

    record LicenseView(
            UUID id,
            String product,
            String publisher,
            String metric,
            Integer quantity,
            long used,
            Long available,
            boolean overused,
            LocalDate validFrom,
            LocalDate validTo,
            Long daysLeft,
            int renewalNoticeDays,
            String status,
            Named vendor,
            ContractRef contract,
            BigDecimal cost,
            String currency,
            Named owner,
            String notes,
            long version) {
    }

    /** Занять лицензию устройством или сотрудником: ровно одно из двух. */
    record Assignment(UUID assetId, UUID userId) {
    }

    record AssignmentView(UUID id, UUID licenseId, String product, Named asset, Named user, Named assignedBy,
                          Instant assignedAt) {
    }
}
