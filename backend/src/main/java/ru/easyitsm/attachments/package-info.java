/**
 * Вложения заявок и статей (TCK-04, SEC-25…27): загрузка и скачивание по подписанным ссылкам S3, проверка по
 * содержимому, архивов и антивирусом, карантин до окончания проверки.
 * <p>
 * Права на файл — права на его владельца: заявку проверяет модуль tickets
 * ({@link ru.easyitsm.tickets.api.TicketQueries#permissions}), статью — knowledge
 * ({@link ru.easyitsm.knowledge.api.ArticleQueries#permissions}). Невидимый владелец — 404.
 * <p>
 * REST: {@code /api/v1/attachments/**}. События: {@code events} — файл прошёл проверку или попал в карантин.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Attachments")
package ru.easyitsm.attachments;
