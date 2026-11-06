package ru.easyitsm.announcements.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.easyitsm.announcements.api.AnnouncementKind;
import ru.easyitsm.announcements.api.AnnouncementQueries;
import ru.easyitsm.announcements.api.AnnouncementView;
import ru.easyitsm.announcements.events.AnnouncementPublished;
import ru.easyitsm.platform.web.ApiException;
import ru.easyitsm.platform.web.CursorPage;
import ru.easyitsm.platform.web.ETags;
import ru.easyitsm.platform.web.KeysetCursor;
import ru.easyitsm.platform.web.PageParams;

@Service
class AnnouncementService implements AnnouncementQueries {

    private final AnnouncementRepository repository;
    private final ApplicationEventPublisher events;

    AnnouncementService(AnnouncementRepository repository, ApplicationEventPublisher events) {
        this.repository = repository;
        this.events = events;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AnnouncementView> activeNow() {
        return repository.page(true, null, null, PageParams.MAX_LIMIT).stream().map(Announcement::toView).toList();
    }

    @Transactional(readOnly = true)
    public CursorPage<AnnouncementView> list(boolean onlyActive, PageParams page) {
        KeysetCursor after = page.keyset();
        Instant afterStartsAt = after == null ? null : Instant.parse(after.sortValue());
        UUID afterId = after == null ? null : after.id();
        List<Announcement> rows = repository.page(onlyActive, afterStartsAt, afterId, page.limit() + 1);
        return CursorPage.of(rows, page.limit(), a -> new KeysetCursor(a.startsAt().toString(), a.getId()))
                .map(Announcement::toView);
    }

    @Transactional(readOnly = true)
    public AnnouncementView get(UUID id) {
        return find(id).toView();
    }

    @Transactional
    public AnnouncementView create(AnnouncementCommands.Create cmd) {
        var a = new Announcement(AnnouncementKind.fromCode(cmd.kind()), cmd.title(), cmd.body(), cmd.startsAt(),
                cmd.endsAt());
        a.setServiceIds(cmd.serviceIds());
        boolean published = a.setPublished(cmd.published());
        a = repository.save(a);
        if (published) {
            publish(a);
        }
        return a.toView();
    }

    @Transactional
    public AnnouncementView update(UUID id, String ifMatch, AnnouncementCommands.Update cmd) {
        Announcement a = find(id);
        ETags.requireMatch(ifMatch, a.getVersion());
        if (cmd.kind() != null) {
            a.setKind(AnnouncementKind.fromCode(cmd.kind()));
        }
        if (cmd.title() != null) {
            a.setTitle(cmd.title());
        }
        if (cmd.body() != null) {
            a.setBody(cmd.body());
        }
        if (cmd.serviceIds() != null) {
            a.setServiceIds(cmd.serviceIds());
        }
        if (cmd.startsAt() != null || cmd.endsAt() != null) {
            a.setWindow(cmd.startsAt() != null ? cmd.startsAt() : a.startsAt(),
                    cmd.endsAt() != null ? cmd.endsAt() : a.endsAt());
        }
        boolean published = cmd.published() != null && a.setPublished(cmd.published());
        a = repository.save(a);
        if (published) {
            publish(a);
        }
        return a.toView();
    }

    private Announcement find(UUID id) {
        // Чужая запись отсекается RLS и выглядит как отсутствующая: 404, а не 403 (AT-01).
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("Объявление"));
    }

    private void publish(Announcement a) {
        events.publishEvent(new AnnouncementPublished(a.getTenantId(), a.getId(), a.kind(), a.title()));
    }
}
