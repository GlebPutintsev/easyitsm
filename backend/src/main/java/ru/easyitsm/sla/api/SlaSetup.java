package ru.easyitsm.sla.api;

import java.time.ZoneId;
import java.util.UUID;

/** Первичная настройка SLA для мастера (ADM-01). */
public interface SlaSetup {

    /**
     * Создаёт рабочий график пн–пт 09:00–18:00 в заданном поясе и политику SLA со сроками по умолчанию
     * из раздела 5.3 ТЗ: P1 и P2 круглосуточно, P3 и P4 по рабочему графику.
     *
     * @return id созданной политики
     */
    UUID installDefaults(ZoneId zone);
}
