package ru.easyitsm.admin.api;

import java.util.Collection;
import java.util.List;

/** Справочник организаций и подразделений арендатора (ADM-02): на них ссылаются пользователи и заявки. */
public interface OrgUnits {

    List<OrgUnitSummary> findAll(Collection<java.util.UUID> ids);

    /** kind — organization, department или null для всех. */
    List<OrgUnitSummary> list(String kind);
}
