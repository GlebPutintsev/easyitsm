package ru.easyitsm.search.internal;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки глобального поиска.
 *
 * @param opensearch  подключение к OpenSearch; выключено — только резервный поиск PostgreSQL (пилот, 12.1 ТЗ)
 * @param indexing    очередь индексации
 * @param maxLimit    сколько результатов одного вида отдаётся за запрос
 */
@ConfigurationProperties("easyitsm.search")
record SearchProperties(@DefaultValue OpenSearch opensearch,
                        @DefaultValue Indexing indexing,
                        @DefaultValue("20") int maxLimit) {

    /**
     * @param urls          адреса узлов, запросы идут по кругу; первый недоступный пропускается
     * @param username      пользователь OpenSearch; пароль — секретом {@code EASYITSM_SEARCH_OPENSEARCH_PASSWORD}
     * @param indexPrefix   префикс имени индекса; версия схемы добавляется сама ({@code easyitsm-search-v1})
     * @param shards        число шардов нового индекса
     * @param replicas      число реплик нового индекса: 0 на одном узле, 1 в HA
     * @param timeout       таймаут запроса: поиск не ждёт медленный OpenSearch дольше (NFR-04 — p95 700 мс)
     * @param retryAfter    после сбоя запросы не идут в OpenSearch это время, выдача из резервного поиска
     */
    record OpenSearch(@DefaultValue("false") boolean enabled,
                      @DefaultValue("http://localhost:9200") List<String> urls,
                      String username,
                      String password,
                      @DefaultValue("easyitsm-search") String indexPrefix,
                      @DefaultValue("1") int shards,
                      @DefaultValue("0") int replicas,
                      @DefaultValue("PT2S") Duration connectTimeout,
                      @DefaultValue("PT3S") Duration timeout,
                      @DefaultValue("PT30S") Duration retryAfter) {
    }

    /**
     * @param interval  как часто обработчик забирает очередь; запись появляется в поиске за interval + refresh 1 с
     * @param batchSize сколько записей отправляется одним bulk-запросом
     * @param lease     аренда пачки: если под упал, её заберёт другой
     * @param reconcile ночная сверка индекса с БД (cron), «-» — выключена
     */
    record Indexing(@DefaultValue("PT1S") Duration interval,
                    @DefaultValue("200") int batchSize,
                    @DefaultValue("PT2M") Duration lease,
                    @DefaultValue("0 30 3 * * *") String reconcile) {
    }
}
