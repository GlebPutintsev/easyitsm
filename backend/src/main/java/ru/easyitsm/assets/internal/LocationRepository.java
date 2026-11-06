package ru.easyitsm.assets.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;

interface LocationRepository extends ListCrudRepository<Location, UUID> {

    @Query("select * from asset_location order by lower(name)")
    List<Location> findAllOrdered();
}
