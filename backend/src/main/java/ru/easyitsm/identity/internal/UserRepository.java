package ru.easyitsm.identity.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;

interface UserRepository extends ListCrudRepository<UserAccount, UUID> {

    @Query("select * from user_account where lower(login) = lower(:login) or lower(email) = lower(:login) limit 1")
    Optional<UserAccount> findByLoginOrEmail(String login);

    @Query("select * from user_account where source = :source and external_id = :externalId")
    Optional<UserAccount> findByExternalId(String source, String externalId);

    @Query("select * from user_account where source = :source")
    List<UserAccount> findAllBySource(String source);

    /** Keyset по (lower(display_name), id). Поиск по имени, логину и почте. */
    @Query("""
            select * from user_account
             where (cast(:q as text) is null
                    or lower(display_name) like :q or lower(login) like :q or lower(coalesce(email, '')) like :q)
               and (cast(:state as text) is null or state = :state)
               and (cast(:afterName as text) is null or (lower(display_name), id) > (:afterName, cast(:afterId as uuid)))
             order by lower(display_name), id
             limit :limit""")
    List<UserAccount> page(String q, String state, String afterName, UUID afterId, int limit);

    @Query("select count(*) from user_account")
    long countAll();

    @Modifying
    @Query("update user_account set last_login_at = now() where id = :id")
    void touchLastLogin(UUID id);
}
