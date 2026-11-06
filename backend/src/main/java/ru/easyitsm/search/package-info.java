/**
 * Глобальный поиск (TCK-15, PRT-02, NFR-04): заявки, статьи базы знаний и услуги каталога одной строкой, с
 * учётом прав и русской морфологии.
 * <p>
 * Основной движок — OpenSearch: индекс строится по событиям модулей tickets и knowledge через очередь в
 * PostgreSQL и воссоздаётся из БД. Права применяются фильтром в запросе к индексу по атрибутам доступа
 * документа и перепроверяются модулем-владельцем ({@code TicketSearchIndex.visibleAmong},
 * {@code ArticleSearchIndex.readableAmong}), поэтому отставание индекса не открывает чужие записи.
 * <p>
 * Без OpenSearch (пилот, сбой, первичная загрузка индекса) работает резервный поиск: каждый модуль ищет в своих
 * таблицах по {@code tsvector}, ответ помечается {@code degraded}, если OpenSearch настроен, но недоступен (AT-08).
 * <p>
 * REST: {@code GET /api/v1/search}, служебные {@code GET /api/v1/search/status} и
 * {@code POST /api/v1/search/reindex} для администратора.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Search")
package ru.easyitsm.search;
