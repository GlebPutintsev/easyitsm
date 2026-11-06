package ru.easyitsm.catalog.internal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;

interface CatalogItemRepository extends ListCrudRepository<CatalogItem, UUID> {

    String VISIBLE = """
            (:admin or (active and (cardinality(visible_roles) = 0 or visible_roles && cast(:roles as text[]))))""";

    /** Keyset по (name, id). Видимость по ролям проверяется в SQL, чтобы страницы были полными. */
    @Query("select * from catalog_item where " + VISIBLE + """
               and (:anyCategory or category_id in (:categoryIds))
               and (cast(:q as text) is null or name ilike :q or summary ilike :q or keywords ilike :q
                    or coalesce(team, '') ilike :q)
               and (cast(:team as text) is null or team = :team)
               and (cast(:favoritesOf as uuid) is null
                    or id in (select item_id from catalog_favorite where user_id = :favoritesOf))
               and (cast(:afterName as text) is null or (name, id) > (cast(:afterName as text), cast(:afterId as uuid)))
             order by name, id
             limit :limit""")
    List<CatalogItem> page(boolean admin, String[] roles, boolean anyCategory, Collection<UUID> categoryIds, String q,
                           String team, UUID favoritesOf, String afterName, UUID afterId, int limit);

    @Query("select * from catalog_item where active order by name, id")
    List<CatalogItem> allActive();

    /** Популярные услуги для главной портала (10.4): до 8 плиток. */
    @Query("select * from catalog_item where active and " + VISIBLE + " order by order_count desc, name, id limit :limit")
    List<CatalogItem> popular(boolean admin, String[] roles, int limit);

    @Query("select distinct team from catalog_item where team is not null and " + VISIBLE + " order by team")
    List<String> teams(boolean admin, String[] roles);

    /** Счётчик популярности не меняет версию: заказы не должны конфликтовать с правкой в редакторе. */
    @Modifying
    @Query("update catalog_item set order_count = order_count + 1 where id = :id")
    void incrementOrderCount(UUID id);

    boolean existsByApprovalFlowId(UUID flowId);
}
