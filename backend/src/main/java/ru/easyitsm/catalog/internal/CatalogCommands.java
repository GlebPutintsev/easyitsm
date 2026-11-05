package ru.easyitsm.catalog.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ru.easyitsm.catalog.internal.form.Condition;

final class CatalogCommands {

    private CatalogCommands() {
    }

    record CategoryInput(
            UUID parentId,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 2_000) String description,
            @Size(max = 60) String icon,
            Integer sortOrder,
            Boolean active) {
    }

    /** Полное описание предложения: создаётся и заменяется целиком из редактора каталога. */
    record ItemFields(
            @NotNull UUID categoryId,
            @Pattern(regexp = "request|incident") String kind,
            @NotBlank @Size(max = 160) String name,
            @Size(max = 300) String summary,
            @Size(max = 20_000) String description,
            @Size(max = 60) String icon,
            @Size(max = 80) String team,
            @Size(max = 500) String keywords,
            Map<String, Object> form,
            UUID approvalFlowId,
            @Size(max = 20) List<@Pattern(regexp = "[A-Z]{2,5}") String> visibleRoles,
            Boolean allowOnBehalf,
            @PositiveOrZero BigDecimal cost,
            @Pattern(regexp = "[A-Z]{3}") String currency,
            @Positive @Max(8_760) Integer leadTimeHours,
            Integer sortOrder,
            Boolean active) {
    }

    record StepInput(
            @NotBlank @Size(max = 120) String name,
            @NotNull @Pattern(regexp = "all|any|quorum") String mode,
            @Min(1) @Max(50) Integer quorum,
            @NotNull @Size(min = 1, max = 50) List<@NotNull UUID> approvers,
            Condition condition,
            @Positive @Max(525_600) Integer dueMinutes,
            Boolean allowDelegation) {
    }

    record FlowInput(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 2_000) String description,
            @NotNull @Size(max = 10) List<@Valid @NotNull StepInput> steps,
            Boolean active) {
    }

    /** Заказ услуги (10.5). {@code forUserId} — заявка от имени другого сотрудника (REQ-07). */
    record Order(
            UUID forUserId,
            @Size(max = 255) String title,
            @Size(max = 20_000) String description,
            @Pattern(regexp = "only_me|department|everyone") String impact,
            Map<String, Object> values) {
    }

    record Decision(
            @NotNull @Pattern(regexp = "approve|reject|request_info") String decision,
            @Size(max = 4_000) String comment) {
    }

    record Delegate(@NotNull UUID toUserId, @Size(max = 4_000) String comment) {
    }
}
