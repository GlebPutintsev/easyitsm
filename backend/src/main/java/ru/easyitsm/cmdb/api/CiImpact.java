package ru.easyitsm.cmdb.api;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Анализ влияния отказа КЕ (CFG-09) для инцидентов, изменений (CHG-06), проблем и крупных инцидентов.
 * Влияние идёт по активным связям в направлении, которое задаёт тип связи: сервер, на котором работает
 * приложение, влияет на приложение, а приложение — на услугу, которая от него зависит.
 * Без проверки прав пользователя, в арендаторе вызывающего.
 */
public interface CiImpact {

    int MAX_DEPTH = 5;

    /** Полный отчёт. Глубина приводится к 1…5. Неизвестные id пропускаются. */
    ImpactReport analyze(Collection<UUID> ciIds, int depth);

    /** Только затронутые услуги, включая сами исходные КЕ, если они услуги. */
    default List<ImpactedCi> impactedServices(Collection<UUID> ciIds, int depth) {
        return analyze(ciIds, depth).services();
    }
}
