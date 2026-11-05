package ru.easyitsm.identity.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;

interface GroupRepository extends ListCrudRepository<UserGroup, UUID> {

    @Query("select * from user_group where source = :source and external_id = :externalId")
    Optional<UserGroup> findByExternalId(String source, String externalId);

    @Query("""
            select * from user_group
             where (cast(:q as text) is null or lower(name) like :q)
               and (cast(:afterName as text) is null or (lower(name), id) > (:afterName, cast(:afterId as uuid)))
             order by lower(name), id
             limit :limit""")
    List<UserGroup> page(String q, String afterName, UUID afterId, int limit);
}
