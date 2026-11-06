package ru.easyitsm.attachments.internal;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

/**
 * Хранилище файлов S3. Браузер загружает и скачивает файлы напрямую по подписанным ссылкам на отдельном
 * домене файлов (SEC-25), ядро только проверяет и перекладывает их. Без Netty: синхронный HTTP-клиент JDK.
 */
@Component
class ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(ObjectStorage.class);

    /** Ссылка на загрузку: метод PUT и заголовки, которые браузер обязан отправить как есть. */
    record UploadUrl(String url, String method, Map<String, String> headers) {
    }

    /** Ошибка хранилища: недоступно или отказало. */
    static class StorageUnavailable extends RuntimeException {
        StorageUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final AttachmentProperties.S3 props;
    private final S3Client s3;
    private final S3Presigner presigner;

    ObjectStorage(AttachmentProperties props) {
        this.props = props.s3();
        AwsCredentialsProvider creds = this.props.accessKey() == null || this.props.accessKey().isBlank()
                ? AnonymousCredentialsProvider.create()
                : StaticCredentialsProvider.create(AwsBasicCredentials.create(this.props.accessKey(),
                this.props.secretKey() == null ? "" : this.props.secretKey()));
        S3Configuration config = S3Configuration.builder().pathStyleAccessEnabled(this.props.pathStyle()).build();
        Region region = Region.of(this.props.region());
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(this.props.endpoint()))
                .region(region)
                .credentialsProvider(creds)
                .serviceConfiguration(config)
                // MinIO и Ceph старых версий не понимают контрольные суммы CRC по умолчанию SDK 2.30+.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5))
                        .socketTimeout(Duration.ofSeconds(60)))
                .build();
        this.presigner = S3Presigner.builder()
                .endpointOverride(URI.create(this.props.browserEndpoint()))
                .region(region)
                .credentialsProvider(creds)
                .serviceConfiguration(config)
                .build();
    }

    @PreDestroy
    void close() {
        s3.close();
        presigner.close();
    }

    /** Создаёт корзины, если их нет и это разрешено настройкой (разработка). */
    void ensureBuckets() {
        if (!props.createBucket()) {
            return;
        }
        for (String bucket : List.of(props.bucket(), props.quarantine()).stream().distinct().toList()) {
            try {
                s3.headBucket(b -> b.bucket(bucket));
            } catch (S3Exception e) {
                if (e.statusCode() != 404) {
                    throw new StorageUnavailable("Корзина " + bucket + ": " + e.getMessage(), e);
                }
                s3.createBucket(b -> b.bucket(bucket));
                log.info("Создана корзина S3 {}", bucket);
            }
        }
    }

    UploadUrl presignUpload(String key, String contentType, long size, Duration ttl) {
        try {
            PresignedPutObjectRequest p = presigner.presignPutObject(r -> r.signatureDuration(ttl)
                    .putObjectRequest(o -> o.bucket(props.quarantine()).key(key).contentType(contentType)
                            .contentLength(size)));
            Map<String, String> headers = new LinkedHashMap<>();
            p.signedHeaders().forEach((name, values) -> {
                // host и content-length браузер ставит сам, задавать их из JavaScript запрещено.
                if (!name.equalsIgnoreCase("host") && !name.equalsIgnoreCase("content-length")) {
                    headers.put(name, String.join(",", values));
                }
            });
            return new UploadUrl(p.url().toString(), "PUT", headers);
        } catch (SdkException e) {
            throw new StorageUnavailable("Подпись ссылки: " + e.getMessage(), e);
        }
    }

    /**
     * Ссылка на скачивание. {@code inline} — показать в браузере (только безопасные картинки), иначе файл
     * всегда скачивается как {@code application/octet-stream}: браузер его не исполнит и не отрисует.
     */
    String presignDownload(String key, String fileName, String contentType, boolean inline, Duration ttl) {
        String disposition = (inline ? "inline" : "attachment") + "; filename=\"" + asciiName(fileName)
                + "\"; filename*=UTF-8''" + URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        try {
            return presigner.presignGetObject(r -> r.signatureDuration(ttl)
                    .getObjectRequest(o -> o.bucket(props.bucket()).key(key)
                            .responseContentDisposition(disposition)
                            .responseContentType(inline ? contentType : "application/octet-stream")
                            .responseCacheControl("private, no-store")))
                    .url().toString();
        } catch (SdkException e) {
            throw new StorageUnavailable("Подпись ссылки: " + e.getMessage(), e);
        }
    }

    /** Размер загруженного в карантин файла; пусто — файла нет. */
    Optional<Long> quarantineSize(String key) {
        try {
            HeadObjectResponse head = s3.headObject(r -> r.bucket(props.quarantine()).key(key));
            return Optional.of(head.contentLength());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw new StorageUnavailable("S3: " + e.getMessage(), e);
        } catch (SdkException e) {
            throw new StorageUnavailable("S3 недоступно: " + e.getMessage(), e);
        }
    }

    /**
     * Скачивает файл карантина во временный файл для проверки, не больше {@code maxBytes}: файл, подменённый
     * после подтверждения загрузки, не заполнит диск.
     *
     * @return сколько байт записано; больше {@code maxBytes} — файл слишком большой и записан не полностью
     */
    long downloadQuarantine(String key, Path target, long maxBytes) {
        try (var in = s3.getObject(r -> r.bucket(props.quarantine()).key(key));
             var out = java.nio.file.Files.newOutputStream(target)) {
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > maxBytes) {
                    in.abort();
                    return total;
                }
                out.write(buf, 0, n);
            }
            return total;
        } catch (SdkException | java.io.IOException e) {
            throw new StorageUnavailable("S3: " + e.getMessage(), e);
        }
    }

    /** Файл от другого модуля (почта) — прямо в карантин, без подписанной ссылки. */
    void putQuarantine(String key, byte[] content, String contentType) {
        try {
            s3.putObject(r -> r.bucket(props.quarantine()).key(key).contentType(contentType)
                    .contentLength((long) content.length), RequestBody.fromBytes(content));
        } catch (SdkException e) {
            throw new StorageUnavailable("S3: " + e.getMessage(), e);
        }
    }

    /** Чистый файл переносится из карантина в корзину файлов. */
    void release(String quarantineKey, String cleanKey) {
        try {
            s3.copyObject(r -> r.sourceBucket(props.quarantine()).sourceKey(quarantineKey)
                    .destinationBucket(props.bucket()).destinationKey(cleanKey));
            s3.deleteObject(r -> r.bucket(props.quarantine()).key(quarantineKey));
        } catch (SdkException e) {
            throw new StorageUnavailable("S3: " + e.getMessage(), e);
        }
    }

    void deleteQuarantine(String key) {
        delete(props.quarantine(), key);
    }

    void deleteClean(String key) {
        delete(props.bucket(), key);
    }

    private void delete(String bucket, String key) {
        try {
            s3.deleteObject(r -> r.bucket(bucket).key(key));
        } catch (SdkException e) {
            throw new StorageUnavailable("S3: " + e.getMessage(), e);
        }
    }

    /** Имя для старых клиентов без filename*: только печатные ASCII без кавычек и обратной косой. */
    static String asciiName(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            sb.append(c >= 0x20 && c < 0x7f && c != '"' && c != '\\' ? c : '_');
        }
        return sb.toString();
    }
}
