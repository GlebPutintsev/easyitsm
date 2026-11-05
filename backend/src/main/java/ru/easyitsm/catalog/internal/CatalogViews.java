package ru.easyitsm.catalog.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ru.easyitsm.catalog.internal.form.FormSchema;

final class CatalogViews {

    private CatalogViews() {
    }

    record CategoryView(UUID id, UUID parentId, String name, String description, String icon, int sortOrder,
                        boolean active, long version) {
    }

    /** Плитка услуги в каталоге (10.4). */
    record ItemCard(UUID id, UUID categoryId, String kind, String name, String summary, String icon, String team,
                    BigDecimal cost, String currency, Integer leadTimeHours, boolean favorite, boolean active) {
    }

    record ItemDetail(UUID id, UUID categoryId, String kind, String name, String summary, String description,
                      String icon, String team, String keywords, FormSchema form, UUID approvalFlowId,
                      List<String> visibleRoles, boolean allowOnBehalf, BigDecimal cost, String currency,
                      Integer leadTimeHours, int sortOrder, boolean active, boolean favorite, long orderCount,
                      Instant updatedAt, long version) {
    }

    record FlowView(UUID id, String name, String description, List<StepDef> steps, boolean active,
                    Instant updatedAt, long version) {
    }

    /** Результат заказа: экран «Заявка создана» с номером и ожидаемым сроком (10.5). */
    record OrderResult(UUID ticketId, String number, String status, boolean awaitingApproval, Instant expectedBy) {
    }

    /** Строка списка согласований (10.8). */
    record ApprovalView(UUID id, UUID ticketId, String ticketNumber, String title, UUID requesterId,
                        UUID createdForId, String stepName, int stepIndex, String decision, String comment,
                        Instant infoRequestedAt, Instant dueAt, Instant decidedAt, Instant createdAt,
                        UUID delegateFrom, boolean allowDelegation) {
    }

    record RouteApproval(UUID id, UUID approverId, String decision, String comment, Instant decidedAt,
                         UUID delegateFrom) {
    }

    record RouteStep(int index, String name, String mode, int required, String state,
                     List<RouteApproval> approvals) {
    }

    /** Маршрут согласования заявки для карточки (шкала шагов). */
    record RouteView(UUID ticketId, String state, int currentStep, Instant decidedAt, List<RouteStep> steps) {
    }
}
