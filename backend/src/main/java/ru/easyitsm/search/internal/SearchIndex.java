package ru.easyitsm.search.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Индекс OpenSearch: псевдоним {@code <префикс>-v1} указывает на индекс {@code <префикс>-v1-000001}. Запись
 * идёт только через псевдоним ({@code require_alias}), поэтому удалённый вручную индекс не пересоздастся
 * сам со схемой по умолчанию, а будет создан заново со своей схемой и полной загрузкой из БД.
 */
@Component
class SearchIndex {

    static final String SCHEMA_VERSION = "v1";
    private static final Logger log = LoggerFactory.getLogger(SearchIndex.class);

    /** Одна операция bulk: индексировать документ или удалить его. */
    record Op(String id, Map<String, Object> doc) {
        static Op delete(String id) {
            return new Op(id, null);
        }
    }

    /** Итог операции: успех или текст ошибки. */
    record OpResult(String id, boolean ok, String error) {
    }

    private final OpenSearchClient client;
    private final SearchProperties.OpenSearch props;
    private final JsonMapper json;

    SearchIndex(OpenSearchClient client, SearchProperties props, JsonMapper json) {
        this.client = client;
        this.props = props.opensearch();
        this.json = json;
    }

    String alias() {
        return props.indexPrefix() + "-" + SCHEMA_VERSION;
    }

    /**
     * Проверяет, что псевдоним есть, и создаёт индекс, если его нет.
     *
     * @return true — индекс только что создан и пуст: всем арендаторам нужна полная загрузка
     */
    boolean ensure() {
        OpenSearchClient.Response alias = client.head("/_alias/" + alias());
        if (alias.ok()) {
            return false;
        }
        String index = alias() + "-000001";
        ObjectNode body = definition();
        body.putObject("aliases").putObject(alias());
        OpenSearchClient.Response created = client.put("/" + index, body);
        if (!created.ok()) {
            String type = created.body().path("error").path("type").asString("");
            if (!"resource_already_exists_exception".equals(type)) {
                throw new OpenSearchClient.SearchUnavailable("Не удалось создать индекс " + index + ": "
                        + created.body(), null);
            }
            // Индекс есть, но без псевдонима (удалили псевдоним): вернуть псевдоним, данные могли устареть.
            client.post("/_aliases", Map.of("actions", List.of(Map.of("add", Map.of("index", index, "alias", alias())))));
        }
        log.info("Создан поисковый индекс {} (псевдоним {})", index, alias());
        return true;
    }

    /** Схема индекса с числом шардов и реплик из настроек. */
    ObjectNode definition() {
        try (InputStream in = new ClassPathResource("search/index-" + SCHEMA_VERSION + ".json").getInputStream()) {
            ObjectNode node = (ObjectNode) json.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            ObjectNode index = (ObjectNode) node.path("settings").path("index");
            index.put("number_of_shards", props.shards());
            index.put("number_of_replicas", props.replicas());
            return node;
        } catch (IOException e) {
            throw new IllegalStateException("Нет схемы поискового индекса", e);
        }
    }

    /** Отправка пачки. Ошибка сети — исключение, ошибки отдельных документов — в результате. */
    List<OpResult> bulk(List<Op> ops) {
        if (ops.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (Op op : ops) {
            if (op.doc() == null) {
                lines.add(client.toJson(Map.of("delete", Map.of("_index", alias(), "_id", op.id()))));
            } else {
                lines.add(client.toJson(Map.of("index", Map.of("_index", alias(), "_id", op.id()))));
                lines.add(client.toJson(op.doc()));
            }
        }
        OpenSearchClient.Response r = client.bulkRequireAlias(lines);
        if (!r.ok()) {
            throw new OpenSearchClient.SearchUnavailable("bulk " + r.status() + ": " + r.body(), null);
        }
        List<OpResult> results = new ArrayList<>(ops.size());
        JsonNode items = r.body().path("items");
        for (int i = 0; i < ops.size(); i++) {
            JsonNode item = items.path(i);
            JsonNode res = item.has("delete") ? item.path("delete") : item.path("index");
            int status = res.path("status").asInt(0);
            boolean deleteMissing = ops.get(i).doc() == null && status == 404;
            boolean ok = (status >= 200 && status < 300) || deleteMissing;
            results.add(new OpResult(ops.get(i).id(), ok, ok ? null : res.path("error").toString()));
        }
        return results;
    }
}
