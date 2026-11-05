package ru.easyitsm.licensing.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.licensing.api.LicenseQueries;
import ru.easyitsm.licensing.api.LicenseState;
import ru.easyitsm.licensing.api.LicenseStatus;
import ru.easyitsm.licensing.events.LicenseInstalled;
import ru.easyitsm.platform.web.ApiException;

/**
 * Действующая лицензия инсталляции. Источники: файл из {@code easyitsm.license.file} и последний файл,
 * загруженный администратором. Из двух проверенных действует выпущенный позже ({@code issuedAt}).
 * <p>
 * Состояние кэшируется на минуту на каждом поде: новая лицензия доходит до всех реплик без перезапуска
 * и без фоновых задач.
 */
@Service
class LicenseService implements LicenseQueries {

    private static final Logger log = LoggerFactory.getLogger(LicenseService.class);
    static final Duration CACHE_TTL = Duration.ofMinutes(1);

    private final LicenseCodec codec;
    private final LicenseStore store;
    private final LicenseProperties properties;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    private volatile Cached cached;

    @Autowired
    LicenseService(LicenseStore store, LicenseProperties properties, ApplicationEventPublisher events,
                   ObjectProvider<MeterRegistry> meters) {
        this(new LicenseCodec(TrustedKeys.loadFromClasspath()), store, properties, events, Clock.systemUTC());
        meters.ifAvailable(this::registerMetrics);
    }

    LicenseService(LicenseCodec codec, LicenseStore store, LicenseProperties properties,
                   ApplicationEventPublisher events, Clock clock) {
        this.codec = codec;
        this.store = store;
        this.properties = properties;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public LicenseStatus current() {
        Instant now = clock.instant();
        Cached c = cached;
        if (c == null || c.loadedAt.plus(CACHE_TTL).isBefore(now)) {
            c = new Cached(load(), now);
            cached = c;
        }
        return statusOf(c.result, now);
    }

    @Override
    public void requireAgentSeats(int agentsAfterChange) {
        LicenseStatus status = current();
        if (agentsAfterChange > status.maxAgents()) {
            throw new ApiException(HttpStatus.CONFLICT, "license_agent_limit",
                    "Лицензия допускает не больше " + status.maxAgents() + " агентов");
        }
    }

    /** Проверяет и устанавливает файл, загруженный администратором. Недействующую лицензию не принимает. */
    @Transactional
    public LicenseStatus install(String content, UUID installedBy) {
        Instant now = clock.instant();
        VerifiedLicense license;
        try {
            license = codec.verify(content);
        } catch (LicenseException e) {
            throw ApiException.badRequest(e.code(), e.getMessage());
        }
        LicenseStatus status = statusOf(new LoadResult(license, null), now);
        if (status.state() == LicenseState.EXPIRED) {
            throw ApiException.badRequest("license_expired", "Срок действия лицензии закончился");
        }
        store.save(content, license.licenseId(), installedBy, now);
        events.publishEvent(new LicenseInstalled(license.licenseId(), license.licensee(), license.maxAgents(),
                license.notAfter(), installedBy, now));
        cached = null;
        log.info("Установлена лицензия {} ({} агентов, до {})", license.licenseId(), license.maxAgents(),
                license.notAfter());
        return current();
    }

    private LoadResult load() {
        LoadResult fromFile = fromFile();
        LoadResult fromStore = fromStore();
        if (fromFile.license != null && fromStore.license != null) {
            return fromFile.license.issuedAt().isAfter(fromStore.license.issuedAt()) ? fromFile : fromStore;
        }
        if (fromStore.license != null) {
            return fromStore;
        }
        if (fromFile.license != null) {
            return fromFile;
        }
        return fromFile.problem != null ? fromFile : fromStore;
    }

    private LoadResult fromFile() {
        if (properties.file() == null || !Files.isRegularFile(properties.file())) {
            return LoadResult.NONE;
        }
        try {
            return verified(Files.readString(properties.file(), StandardCharsets.UTF_8), "файл");
        } catch (IOException e) {
            log.warn("Не удалось прочитать файл лицензии {}", properties.file());
            return new LoadResult(null, "license_unreadable");
        }
    }

    private LoadResult fromStore() {
        try {
            return store.latest().map(content -> verified(content, "база")).orElse(LoadResult.NONE);
        } catch (RuntimeException e) {
            // Недоступность БД не должна превращать лицензию в пробную на всё время кэша.
            log.warn("Не удалось прочитать лицензию из БД: {}", e.getClass().getSimpleName());
            Cached previous = cached;
            return previous != null ? previous.result : LoadResult.NONE;
        }
    }

    private LoadResult verified(String content, String source) {
        try {
            return new LoadResult(codec.verify(content), null);
        } catch (LicenseException e) {
            log.warn("Лицензия ({}) не прошла проверку: {}", source, e.code());
            return new LoadResult(null, e.code());
        }
    }

    static LicenseStatus statusOf(LoadResult result, Instant now) {
        VerifiedLicense l = result.license;
        if (l == null) {
            LicenseState state = result.problem == null ? LicenseState.TRIAL : LicenseState.INVALID;
            return new LicenseStatus(state, null, null, null, null, LicenseStatus.TRIAL_AGENTS, null, List.of(),
                    result.problem);
        }
        if (now.isBefore(l.notBefore())) {
            return new LicenseStatus(LicenseState.NOT_YET_VALID, l.licenseId(), l.licensee(), l.notBefore(),
                    l.notAfter(), LicenseStatus.TRIAL_AGENTS, null, l.features(), null);
        }
        if (!now.isBefore(l.notAfter())) {
            return new LicenseStatus(LicenseState.EXPIRED, l.licenseId(), l.licensee(), l.notBefore(),
                    l.notAfter(), LicenseStatus.TRIAL_AGENTS, 0L, l.features(), "license_expired");
        }
        long daysLeft = Duration.between(now, l.notAfter()).toDays();
        return new LicenseStatus(LicenseState.VALID, l.licenseId(), l.licensee(), l.notBefore(), l.notAfter(),
                l.maxAgents(), daysLeft, l.features(), null);
    }

    /** Метрики для оповещения об окончании лицензии (раздел 12.5). */
    private void registerMetrics(MeterRegistry registry) {
        Gauge.builder("easyitsm.license.days.left", this, s -> {
                    Long days = s.current().daysLeft();
                    return days == null ? -1 : days;
                })
                .description("Дней до окончания лицензии, -1 без действующей лицензии")
                .register(registry);
        Gauge.builder("easyitsm.license.max.agents", this, s -> s.current().maxAgents())
                .description("Действующий лимит агентов")
                .register(registry);
    }

    record LoadResult(VerifiedLicense license, String problem) {
        static final LoadResult NONE = new LoadResult(null, null);
    }

    private record Cached(LoadResult result, Instant loadedAt) {
    }
}
