/**
 * База знаний (KB-01…05, разделы 5.7 и 10.12 ТЗ): статьи, версии, жизненный цикл, аудитории,
 * статья из решённой заявки и подсказки статей при создании заявки (INC-05).
 * <ul>
 *   <li>{@code api} — {@link ru.easyitsm.knowledge.api.ArticleQueries}: подсказки и чтение статей для других модулей;</li>
 *   <li>{@code events} — публикация, отправка на проверку, пересмотр, архив, статья из заявки;</li>
 *   <li>{@code internal} — сущности, правила доступа, сервис, контроллеры и задача пересмотра.</li>
 * </ul>
 */
@org.springframework.modulith.ApplicationModule(displayName = "Knowledge")
package ru.easyitsm.knowledge;
