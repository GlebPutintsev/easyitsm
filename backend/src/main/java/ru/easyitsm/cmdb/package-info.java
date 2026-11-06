/**
 * CMDB (раздел 4.9 ТЗ, CFG-01…09): классы конфигурационных единиц (КЕ) с наследованием атрибутов, КЕ, типизированные
 * связи, сверка данных из источников, история, показатели качества и анализ влияния.
 * <ul>
 *   <li>{@code api} — {@link ru.easyitsm.cmdb.api.CiDirectory} (поиск КЕ), {@link ru.easyitsm.cmdb.api.CiImpact}
 *       (влияние отказа на КЕ, услуги и заявки);</li>
 *   <li>{@code events} — {@code CiCreated}, {@code CiUpdated}, {@code CiStatusChanged}, {@code CiMerged},
 *       {@code CiImpactChanged}.</li>
 * </ul>
 * Контракт: {@code docs/api/cmdb-contract.md}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "CMDB")
package ru.easyitsm.cmdb;
