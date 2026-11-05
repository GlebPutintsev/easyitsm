package ru.easyitsm.catalog.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;

interface FavoriteRepository extends ListCrudRepository<Favorite, UUID> {

    @Query("select item_id from catalog_favorite where user_id = :userId")
    List<UUID> itemIds(UUID userId);

    boolean existsByUserIdAndItemId(UUID userId, UUID itemId);

    @Modifying
    @Query("delete from catalog_favorite where user_id = :userId and item_id = :itemId")
    void remove(UUID userId, UUID itemId);
}
