package ru.easyitsm.cmdb.api;

import java.util.List;
import java.util.UUID;

/**
 * Результат анализа влияния (CFG-09, CHG-06).
 *
 * @param roots         исходные КЕ, которые нашлись
 * @param impacted      все затронутые КЕ без исходных, по возрастанию глубины
 * @param services      затронутые бизнес- и технические услуги, включая исходные КЕ-услуги (глубина 0)
 * @param openTicketIds открытые заявки (не решённые, не закрытые, не отменённые) на исходные или затронутые КЕ,
 *                      новые первыми, до 100. Права пользователя не проверяются: показывать через
 *                      {@code TicketQueries.findVisible}
 * @param depth         глубина обхода, 1…5
 * @param truncated     обход остановлен на лимите в 2000 КЕ
 */
public record ImpactReport(List<CiRef> roots, List<ImpactedCi> impacted, List<ImpactedCi> services,
                           List<UUID> openTicketIds, int depth, boolean truncated) {
}
