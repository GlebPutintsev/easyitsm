package ru.easyitsm.announcements.internal;

import java.time.Instant;
import org.springframework.data.relational.core.mapping.Table;
import ru.easyitsm.announcements.api.AnnouncementKind;
import ru.easyitsm.announcements.api.AnnouncementView;
import ru.easyitsm.platform.persistence.TenantEntity;
import ru.easyitsm.platform.web.ApiException;

@Table("announcement")
class Announcement extends TenantEntity {

    private String kind;
    private String title;
    private String body;
    private Instant startsAt;
    private Instant endsAt;
    private boolean published;

    protected Announcement() {
    }

    Announcement(AnnouncementKind kind, String title, String body, Instant startsAt, Instant endsAt) {
        this.kind = kind.code();
        this.title = title;
        this.body = body == null ? "" : body;
        setWindow(startsAt, endsAt);
    }

    void setKind(AnnouncementKind kind) {
        this.kind = kind.code();
    }

    void setTitle(String title) {
        this.title = title;
    }

    void setBody(String body) {
        this.body = body;
    }

    void setWindow(Instant startsAt, Instant endsAt) {
        if (endsAt != null && !endsAt.isAfter(startsAt)) {
            throw ApiException.badRequest("invalid_window", "Окончание должно быть позже начала");
        }
        this.startsAt = startsAt;
        this.endsAt = endsAt;
    }

    /** @return true, если объявление только что стало опубликованным */
    boolean setPublished(boolean published) {
        boolean becamePublished = published && !this.published;
        this.published = published;
        return becamePublished;
    }

    String kind() {
        return kind;
    }

    String title() {
        return title;
    }

    Instant startsAt() {
        return startsAt;
    }

    Instant endsAt() {
        return endsAt;
    }

    AnnouncementView toView() {
        return new AnnouncementView(getId(), kind, title, body, startsAt, endsAt, published, getUpdatedAt(),
                getVersion());
    }
}
