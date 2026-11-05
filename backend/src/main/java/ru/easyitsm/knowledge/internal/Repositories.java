package ru.easyitsm.knowledge.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;

interface ArticleRepository extends ListCrudRepository<Article, UUID> {

    /** Следующий номер статьи арендатора. Строка счётчика блокируется до конца транзакции. */
    @Query("""
            insert into kb_counter (tenant_id, last_number) values (app_current_tenant(), 1)
            on conflict (tenant_id) do update set last_number = kb_counter.last_number + 1
            returning last_number""")
    long nextNumber();

    /** Поисковый документ из опубликованной версии: заголовок важнее текста. */
    @Modifying
    @Query("""
            update article a
               set search_vector = setweight(to_tsvector('russian', v.title), 'A')
                                || setweight(to_tsvector('russian', v.body), 'B')
              from article_version v
             where a.id = :articleId and v.article_id = a.id and v.version_no = a.published_version_no""")
    void refreshSearchVector(UUID articleId);

    @Modifying
    @Query("update article set search_vector = null where id = :articleId")
    void clearSearchVector(UUID articleId);
}

interface ArticleVersionRepository extends ListCrudRepository<ArticleVersion, UUID> {

    Optional<ArticleVersion> findByArticleIdAndVersionNo(UUID articleId, int versionNo);

    @Query("""
            select * from article_version where article_id = :articleId order by version_no desc""")
    List<ArticleVersion> findAllOf(UUID articleId);
}

interface KbCategoryRepository extends ListCrudRepository<KbCategory, UUID> {

    @Query("select * from kb_category order by sort_order, name")
    List<KbCategory> findAllOrdered();
}

interface ArticleFeedbackRepository extends ListCrudRepository<ArticleFeedback, UUID> {

    Optional<ArticleFeedback> findByArticleIdAndUserId(UUID articleId, UUID userId);
}
