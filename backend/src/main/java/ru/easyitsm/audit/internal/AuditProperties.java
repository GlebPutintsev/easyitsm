package ru.easyitsm.audit.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки журнала аудита.
 *
 * @param verifyCron ночная проверка целостности по всем арендаторам (cron Spring, по умолчанию 03:15)
 * @param exportMax  сколько записей не больше уходит в одну выгрузку
 */
@ConfigurationProperties("easyitsm.audit")
record AuditProperties(
        @DefaultValue("0 15 3 * * *") String verifyCron,
        @DefaultValue("100000") int exportMax,
        @DefaultValue Siem siem) {

    /**
     * Передача в SIEM (SEC-32, раздел 8.4): Syslog по TCP с TLS (RFC 5425, рамка с длиной), тело CEF или JSON.
     * Доставка «хотя бы один раз»: при сбое повторяется с растущей паузой, получатель отбрасывает повторы по
     * {@code externalId} (CEF) или {@code id} (JSON).
     *
     * @param enabled        включить отправку
     * @param host           адрес приёмника SIEM
     * @param port           порт (6514 — стандартный для Syslog TLS)
     * @param transport      TLS или TCP (TCP без шифрования — только внутри доверенного сегмента и для проверки)
     * @param format         CEF или JSON
     * @param caFile         PEM с сертификатами ЦС приёмника; пусто — доверенные сертификаты JVM
     * @param appName        APP-NAME в заголовке Syslog
     * @param interval       как часто искать недоставленные записи
     * @param batchSize      записей за одну отправку
     * @param connectTimeout ожидание соединения и записи
     * @param maxBackoff     наибольшая пауза между повторами после сбоев
     */
    record Siem(
            @DefaultValue("false") boolean enabled,
            String host,
            @DefaultValue("6514") int port,
            @DefaultValue("TLS") Transport transport,
            @DefaultValue("CEF") Format format,
            String caFile,
            @DefaultValue("easyitsm") String appName,
            @DefaultValue("5s") Duration interval,
            @DefaultValue("500") int batchSize,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("5m") Duration maxBackoff) {

        boolean configured() {
            return enabled && host != null && !host.isBlank();
        }
    }

    enum Transport { TLS, TCP }

    enum Format { CEF, JSON }
}
