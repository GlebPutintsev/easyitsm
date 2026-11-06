package ru.easyitsm.assets.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;

interface VendorRepository extends ListCrudRepository<Vendor, UUID> {

    @Query("select * from asset_vendor order by lower(name)")
    List<Vendor> findAllOrdered();
}
