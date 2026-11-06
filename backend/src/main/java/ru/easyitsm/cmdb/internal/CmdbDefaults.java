package ru.easyitsm.cmdb.internal;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.easyitsm.platform.id.Ids;
import ru.easyitsm.platform.tenant.TenantContext;

/**
 * Классы КЕ и типы связей по умолчанию (CFG-01…03). Создаются для арендатора при первом обращении к CMDB
 * в отдельной транзакции, поэтому работают и из транзакций только для чтения. Системные записи нельзя удалить,
 * но можно переименовать и дополнить атрибутами.
 */
@Component
class CmdbDefaults {

    record ClassSeed(String code, String parent, String name, String layer, boolean isAbstract,
                     List<AttributeDef> attributes, List<List<String>> rules) {
    }

    record RelationSeed(String code, String name, String inverseName, String impactDirection) {
    }

    static final List<ClassSeed> CLASSES = List.of(
            new ClassSeed("business_service", null, "Бизнес-услуга", "business_service", false, List.of(
                    AttributeDef.of("service_hours", "Часы обслуживания", "string"),
                    AttributeDef.of("customers", "Потребители", "text")), List.of()),
            new ClassSeed("technical_service", null, "Техническая услуга", "technical_service", false, List.of(
                    AttributeDef.of("service_hours", "Часы обслуживания", "string")), List.of()),
            new ClassSeed("application", null, "Приложение", "application", false, List.of(
                    AttributeDef.of("vendor", "Производитель", "string"),
                    AttributeDef.of("product_version", "Версия", "string"),
                    AttributeDef.of("url", "Адрес", "string")), List.of(List.of("url"))),
            new ClassSeed("database", "application", "База данных", "application", false, List.of(
                    AttributeDef.of("engine", "СУБД", "string"),
                    AttributeDef.of("port", "Порт", "number")), List.of()),
            new ClassSeed("infrastructure", null, "Инфраструктура", "infrastructure", true, List.of(
                    AttributeDef.of("location", "Размещение", "string"),
                    AttributeDef.of("serial_number", "Серийный номер", "string"),
                    AttributeDef.of("vendor", "Производитель", "string"),
                    AttributeDef.of("model", "Модель", "string")), List.of(List.of("serial_number"))),
            new ClassSeed("server", "infrastructure", "Сервер", "infrastructure", false, List.of(
                    AttributeDef.of("hostname", "Имя хоста", "string"),
                    AttributeDef.of("domain", "Домен", "string"),
                    AttributeDef.of("fqdn", "FQDN", "string"),
                    AttributeDef.of("ip_address", "IP-адрес", "ip"),
                    AttributeDef.of("os", "ОС", "string"),
                    AttributeDef.of("os_version", "Версия ОС", "string"),
                    AttributeDef.of("cpu_count", "Процессоров", "number"),
                    AttributeDef.of("ram_gb", "ОЗУ, ГБ", "number")),
                    List.of(List.of("serial_number"), List.of("fqdn"), List.of("hostname", "domain"))),
            new ClassSeed("physical_server", "server", "Физический сервер", "infrastructure", false, List.of(),
                    List.of()),
            new ClassSeed("virtual_machine", "server", "Виртуальная машина", "infrastructure", false, List.of(
                    AttributeDef.of("vm_uuid", "UUID ВМ", "string"),
                    AttributeDef.of("hypervisor", "Гипервизор", "string")),
                    List.of(List.of("vm_uuid"), List.of("fqdn"), List.of("hostname", "domain"))),
            new ClassSeed("network_device", "infrastructure", "Сетевое устройство", "infrastructure", false, List.of(
                    AttributeDef.of("hostname", "Имя хоста", "string"),
                    AttributeDef.of("ip_address", "IP-адрес", "ip"),
                    AttributeDef.of("mac_address", "MAC-адрес", "string")),
                    List.of(List.of("serial_number"), List.of("mac_address"))),
            new ClassSeed("storage", "infrastructure", "Система хранения", "infrastructure", false, List.of(
                    AttributeDef.of("capacity_tb", "Ёмкость, ТБ", "number")), List.of()),
            new ClassSeed("workstation", "infrastructure", "Рабочее место", "infrastructure", false, List.of(
                    AttributeDef.of("hostname", "Имя хоста", "string"),
                    AttributeDef.of("os", "ОС", "string")),
                    List.of(List.of("serial_number"), List.of("hostname"))),
            new ClassSeed("k8s_cluster", "infrastructure", "Кластер Kubernetes", "infrastructure", false, List.of(
                    AttributeDef.of("api_url", "Адрес API", "string"),
                    AttributeDef.of("k8s_version", "Версия", "string")), List.of(List.of("api_url"))),
            new ClassSeed("cloud_resource", "infrastructure", "Облачный ресурс", "infrastructure", false, List.of(
                    AttributeDef.of("provider", "Провайдер", "string"),
                    AttributeDef.of("region", "Регион", "string"),
                    AttributeDef.of("resource_id", "Идентификатор ресурса", "string")),
                    List.of(List.of("provider", "resource_id"))));

    static final List<RelationSeed> RELATION_TYPES = List.of(
            new RelationSeed("depends_on", "Зависит от", "Нужна для", "target_to_source"),
            new RelationSeed("runs_on", "Работает на", "Выполняет", "target_to_source"),
            new RelationSeed("hosts", "Размещает", "Размещена на", "source_to_target"),
            new RelationSeed("contains", "Содержит", "Входит в", "source_to_target"),
            new RelationSeed("connected_to", "Связана с", "Связана с", "none"));

    private final JdbcClient jdbc;
    private final CmdbJson json;
    private final TransactionTemplate tx;
    private final Set<UUID> seeded = ConcurrentHashMap.newKeySet();

    CmdbDefaults(JdbcClient jdbc, CmdbJson json, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.json = json;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Создаёт недостающие записи по умолчанию для текущего арендатора; повторный вызов почти бесплатен. */
    void ensure() {
        UUID tenant = TenantContext.require();
        if (seeded.contains(tenant)) {
            return;
        }
        tx.executeWithoutResult(s -> seed(tenant));
        seeded.add(tenant);
    }

    private void seed(UUID tenant) {
        // Параллельный первый запрос того же арендатора: ON CONFLICT пропускает уже созданные записи.
        jdbc.sql("select pg_advisory_xact_lock(hashtext('cmdb_defaults:' || :tenant))")
                .param("tenant", tenant.toString()).query().listOfRows();
        for (ClassSeed c : CLASSES) {
            jdbc.sql("""
                    insert into ci_class (id, tenant_id, parent_id, code, name, layer, abstract, system, attributes,
                                          identification_rules, created_at, updated_at, version)
                    values (:id, :tenant, (select id from ci_class where code = :parent), :code, :name, :layer,
                            :abstract, true, cast(:attributes as jsonb), cast(:rules as jsonb), now(), now(), 0)
                    on conflict (tenant_id, code) do nothing""")
                    .param("id", Ids.newId()).param("tenant", tenant).param("parent", c.parent())
                    .param("code", c.code()).param("name", c.name()).param("layer", c.layer())
                    .param("abstract", c.isAbstract()).param("attributes", json.write(c.attributes()))
                    .param("rules", json.write(c.rules()))
                    .update();
        }
        for (RelationSeed r : RELATION_TYPES) {
            jdbc.sql("""
                    insert into ci_relation_type (id, tenant_id, code, name, inverse_name, impact_direction, system,
                                                  created_at, updated_at, version)
                    values (:id, :tenant, :code, :name, :inverse, :impact, true, now(), now(), 0)
                    on conflict (tenant_id, code) do nothing""")
                    .param("id", Ids.newId()).param("tenant", tenant).param("code", r.code())
                    .param("name", r.name()).param("inverse", r.inverseName()).param("impact", r.impactDirection())
                    .update();
        }
    }
}
