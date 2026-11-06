package ru.easyitsm.attachments.internal;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Настройки вложений.
 *
 * @param maxFileSize      предел размера файла (TCK-04: 100 МБ)
 * @param maxPerOwner      файлов у одной заявки или статьи
 * @param uploadsPerMinute загрузок одного пользователя в минуту (SEC-27)
 * @param uploadsPerHour   загрузок одного пользователя в час
 * @param uploadUrlTtl     срок жизни ссылки на загрузку
 * @param downloadUrlTtl   срок жизни ссылки на скачивание: короткий, ссылку нельзя переслать надолго
 * @param blockedExtensions расширения, которые не принимаются даже без исполняемой сигнатуры
 */
@ConfigurationProperties("easyitsm.attachments")
record AttachmentProperties(@DefaultValue S3 s3,
                            @DefaultValue Antivirus antivirus,
                            @DefaultValue Archives archives,
                            @DefaultValue("100MB") DataSize maxFileSize,
                            @DefaultValue("200") int maxPerOwner,
                            @DefaultValue("30") int uploadsPerMinute,
                            @DefaultValue("300") int uploadsPerHour,
                            @DefaultValue("PT15M") Duration uploadUrlTtl,
                            @DefaultValue("PT5M") Duration downloadUrlTtl,
                            @DefaultValue({"exe", "dll", "com", "scr", "msi", "msp", "bat", "cmd", "ps1", "psm1",
                                    "vbs", "vbe", "js", "jse", "wsf", "wsh", "hta", "lnk", "cpl", "jar", "reg", "sh",
                                    "app", "dmg", "pkg", "iso", "img"}) List<String> blockedExtensions,
                            @DefaultValue("PT5S") Duration scanInterval) {

    /**
     * Хранилище S3 (MinIO, Ceph, облачное S3).
     *
     * @param endpoint       адрес для ядра (внутренняя сеть)
     * @param publicEndpoint адрес для браузера в подписанных ссылках: отдельный домен файлов (SEC-25), например
     *                       {@code https://files.itsm.example.ru}; пусто — тот же, что {@code endpoint}
     * @param bucket         корзина файлов; карантин — префикс {@code quarantine/} в ней же или {@code quarantineBucket}
     * @param accessKey      ключ доступа; секрет — {@code EASYITSM_ATTACHMENTS_S3_SECRET_KEY} файлом
     * @param pathStyle      адреса вида {@code endpoint/bucket/key}: MinIO и Ceph
     * @param createBucket   создать корзину при старте, если её нет (разработка)
     */
    record S3(@DefaultValue("http://localhost:9000") String endpoint,
              String publicEndpoint,
              @DefaultValue("ru-central1") String region,
              @DefaultValue("easyitsm-files") String bucket,
              String quarantineBucket,
              String accessKey,
              String secretKey,
              @DefaultValue("true") boolean pathStyle,
              @DefaultValue("false") boolean createBucket) {

        String quarantine() {
            return quarantineBucket == null || quarantineBucket.isBlank() ? bucket : quarantineBucket;
        }

        String browserEndpoint() {
            return publicEndpoint == null || publicEndpoint.isBlank() ? endpoint : publicEndpoint;
        }
    }

    /**
     * Антивирус.
     *
     * @param mode    {@code clamd} — ClamAV по TCP; {@code none} — без антивируса, только для разработки:
     *                файл проходит проверки содержимого и архивов и помечается «не проверен антивирусом»
     * @param maxAttempts после стольких неудачных попыток (антивирус недоступен) файл остаётся в карантине с
     *                пометкой, повтор — по команде офицера безопасности
     */
    record Antivirus(@DefaultValue("clamd") String mode,
                     @DefaultValue("localhost") String host,
                     @DefaultValue("3310") int port,
                     @DefaultValue("PT5S") Duration connectTimeout,
                     @DefaultValue("PT2M") Duration readTimeout,
                     @DefaultValue("50") int maxAttempts) {
    }

    /**
     * Пределы проверки архивов (SEC-27, T-07 — архивные бомбы).
     *
     * @param maxDepth        вложенность архивов
     * @param maxEntries      файлов во всех архивах
     * @param maxUnpacked     суммарный распакованный размер
     * @param maxRatio        во сколько раз распакованный файл может быть больше сжатого
     * @param allowEncrypted  зашифрованные архивы антивирус не проверит: по умолчанию не принимаются
     */
    record Archives(@DefaultValue("3") int maxDepth,
                    @DefaultValue("10000") int maxEntries,
                    @DefaultValue("1GB") DataSize maxUnpacked,
                    @DefaultValue("200") int maxRatio,
                    @DefaultValue("false") boolean allowEncrypted) {
    }
}
