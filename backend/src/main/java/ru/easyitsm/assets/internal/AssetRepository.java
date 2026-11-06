package ru.easyitsm.assets.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;

interface AssetRepository extends ListCrudRepository<Asset, UUID> {

    @Query("select * from asset where lower(tag) = lower(:tag)")
    Optional<Asset> findByTagIgnoreCase(String tag);

    @Query("select * from asset where holder_id = :holderId and state <> 'retired' order by tag")
    List<Asset> findHeldBy(UUID holderId);

    @Query("select * from asset where ci_id = :ciId order by tag")
    List<Asset> findByCi(UUID ciId);

    @Query("select a.* from asset a join asset_ticket t on t.asset_id = a.id where t.ticket_id = :ticketId order by a.tag")
    List<Asset> findByTicket(UUID ticketId);
}
