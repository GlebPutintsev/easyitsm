package ru.easyitsm.admin.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;

interface OrgUnitRepository extends ListCrudRepository<OrgUnit, UUID> {

    @Query("""
            select * from org_unit
             where (cast(:kind as text) is null or kind = :kind)
               and (cast(:q as text) is null or lower(name) like :q or lower(coalesce(code, '')) like :q)
             order by kind desc, lower(name), id""")
    List<OrgUnit> search(String kind, String q);

    @Query("select * from org_unit where kind = :kind and lower(name) = lower(:name) order by created_at limit 1")
    Optional<OrgUnit> findByName(String kind, String name);
}
