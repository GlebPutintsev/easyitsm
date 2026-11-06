/**
 * Проблемы и известные ошибки (PRB-01…05, 4.5 ТЗ).
 * <p>
 * Проблема — заявка типа {@code problem} модуля tickets: номер PRB-…, цикл, группа и лента остаются там. Модуль
 * хранит то, что есть только у проблемы: анализ причин («5 почему», Исикава, хронология, заключение), известную
 * ошибку с обходным решением, изменения-исправления. Инциденты связываются с проблемой связью {@code caused_by}
 * через {@code tickets.api.TicketLinks}.
 * <ul>
 *   <li>{@code api} — {@link ru.easyitsm.problems.api.ProblemCommands}: создание проблемы другими модулями
 *       (разбор крупного инцидента, MIM-05);</li>
 *   <li>{@code events} — {@code ProblemResolved}, {@code KnownErrorPublished};</li>
 *   <li>слушает {@code tickets.events.TicketStatusChanged}: решённая проблема решает связанные инциденты (PRB-04),
 *       завершённое изменение-исправление отмечается в проблеме.</li>
 * </ul>
 * REST: {@code /api/v1/problems}, {@code /api/v1/known-errors}. Контракт — {@code docs/api/problems-contract.md}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Problems")
package ru.easyitsm.problems;
