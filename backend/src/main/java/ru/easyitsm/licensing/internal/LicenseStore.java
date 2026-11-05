package ru.easyitsm.licensing.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.easyitsm.platform.id.Ids;

/** Файлы лицензий, загруженные администратором. Хранится история, действует последний. */
@Repository
class LicenseStore {

    private final JdbcClient jdbc;

    LicenseStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<String> latest() {
        return jdbc.sql("select content from license_file order by installed_at desc, id desc limit 1")
                .query(String.class)
                .optional();
    }

    void save(String content, String licenseId, UUID installedBy, Instant installedAt) {
        jdbc.sql("""
                        insert into license_file (id, license_id, content, installed_at, installed_by)
                        values (:id, :licenseId, :content, :installedAt, :installedBy)""")
                .param("id", Ids.newId())
                .param("licenseId", licenseId)
                .param("content", content)
                .param("installedAt", java.sql.Timestamp.from(installedAt))
                .param("installedBy", installedBy)
                .update();
    }
}
