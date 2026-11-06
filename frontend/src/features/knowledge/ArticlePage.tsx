import { Group, SegmentedControl, Stack, Text } from '@mantine/core';
import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { ApiError } from '@/shared/api/client';
import { ListState } from '@/shared/ui/ListState';
import { useArticle, useArticleSuggestions, useCategories, type ArticleDetails } from './api';
import { ArticleActions } from './ArticleActions';
import { ArticleFeedback } from './ArticleFeedback';
import { ArticleMarkdown } from './ArticleMarkdown';
import { KbNav } from './KbNav';
import { outline, readingMinutes, type OutlineItem } from './outline';
import { ServiceOutage } from './ServiceOutage';
import { AudienceBadge, Notice, StatusBadge, useDateFormat } from './ui';
import './knowledge.css';

/**
 * Страница статьи (DESIGN.md, «База знаний»): разделы слева, статья шириной до 720 px, справа
 * «На этой странице», похожие статьи и метаданные версии. Внизу «Статья помогла?».
 * На портале — опубликованная версия, в рабочем месте — ещё статус, черновик изменений и кнопки
 * жизненного цикла.
 */
export function ArticlePage({ space }: { space: 'portal' | 'agent' }) {
  const { t } = useTranslation();
  const { id } = useParams();
  const fmt = useDateFormat();
  const [showDraft, setShowDraft] = useState(false);
  const published = useArticle(id);
  const hasDraft =
    !!published.data?.currentVersionNo &&
    published.data.publishedVersionNo != null &&
    published.data.currentVersionNo > published.data.publishedVersionNo;
  const draft = useArticle(showDraft && hasDraft ? id : undefined, true);
  const categories = useCategories();
  const base = space === 'portal' ? '/portal/kb' : '/agent/kb';
  const shown = showDraft && draft.data ? draft.data : published.data;
  const toc = useMemo(() => outline(shown?.body ?? ''), [shown?.body]);

  if (published.isPending) {
    return (
      <div className="kb-shell">
        <div className="kb-page">
          <div className="kb-main">
            <ListState state="loading" rows={6} />
          </div>
        </div>
      </div>
    );
  }
  if (published.error) {
    const notFound = published.error instanceof ApiError && published.error.status === 404;
    return notFound ? (
      <ListState state="empty" title={t('kb.article.notFound')} />
    ) : (
      <ListState state="error" error={published.error} onRetry={() => void published.refetch()} />
    );
  }

  const meta = published.data;
  const a = shown ?? meta;
  const agent = space === 'agent';
  const category = categories.data?.find((c) => c.id === a.categoryId);

  return (
    <div className="kb-shell">
      <div className="kb-page">
        <KbNav base={base} categoryId={a.categoryId ?? ''} />

        <article className="kb-main" aria-labelledby="kb-article-title">
          <ServiceOutage serviceId={a.serviceId ?? null} />
          <nav className="kb-crumbs" aria-label={t('kb.article.breadcrumbs')}>
            <Link to={base}>{t('kb.title')}</Link>
            {category && (
              <>
                {' / '}
                <Link to={`${base}?category=${encodeURIComponent(category.id)}`}>{category.name}</Link>
              </>
            )}
          </nav>
          <h1 id="kb-article-title" className="kb-title">
            {a.title}
          </h1>
          <div className="kb-meta">
            <span className="ei-mono">{a.key}</span>
            <span>
              {meta.publishedAt
                ? t('kb.article.updatedAt', { date: fmt.date(meta.publishedAt) })
                : t('kb.article.notPublished')}
            </span>
            <span>{t('kb.article.readingTime', { count: readingMinutes(a.body) })}</span>
            {agent ? <AudienceBadge audience={a.audience} /> : <span>{t(`kb.audienceFor.${a.audience}`)}</span>}
            {agent && <StatusBadge status={meta.status} />}
          </div>

          {agent && (
          <Stack gap="sm" mb="lg">
            <ArticleActions article={meta} />
            <StatusNotices article={meta} />
            {hasDraft && (
              <Notice tone="accent">
                <Group justify="space-between" gap="sm">
                  <Text size="sm">
                    {t('kb.article.hasDraft', { draft: meta.currentVersionNo, published: meta.publishedVersionNo })}
                  </Text>
                  <SegmentedControl
                    size="xs"
                    value={showDraft ? 'draft' : 'published'}
                    onChange={(v) => setShowDraft(v === 'draft')}
                    data={[
                      { value: 'published', label: t('kb.article.showPublished') },
                      { value: 'draft', label: t('kb.article.showDraft') },
                    ]}
                  />
                </Group>
              </Notice>
            )}
          </Stack>
          )}

          {a.body.trim() ? (
            <ArticleMarkdown>{a.body}</ArticleMarkdown>
          ) : (
            <Text c="dimmed">{t('kb.article.emptyBody')}</Text>
          )}

          {meta.publishedVersionNo != null && meta.status !== 'archived' && <ArticleFeedback article={meta} />}
        </article>

        <aside className="kb-aside" aria-label={t('kb.aside.label')}>
          {toc.length > 1 && <Toc items={toc} />}
          <Related article={meta} base={base} />
          <div className="kb-version">
            <span>{t('kb.aside.version', { no: a.shownVersionNo })}</span>
            {meta.reviewAt && (agent || meta.status === 'published') && (
              <span>{t('kb.aside.reviewUntil', { date: fmt.date(meta.reviewAt) })}</span>
            )}
            {agent && meta.sourceTicketRef && (
              <Link to={`/agent/tickets/${encodeURIComponent(meta.sourceTicketRef)}`}>
                {t('kb.article.fromTicket', { ref: meta.sourceTicketRef })}
              </Link>
            )}
            {agent && meta.permissions.viewHistory && (
              <Link to={`/agent/kb/${meta.id}/history`}>{t('kb.actions.history')}</Link>
            )}
          </div>
        </aside>
      </div>
    </div>
  );
}

/** «На этой странице»: подсвечивается раздел, который сейчас наверху экрана. */
function Toc({ items }: { items: OutlineItem[] }) {
  const { t } = useTranslation();
  const active = useActiveHeading(items);
  return (
    <nav className="kb-toc" aria-label={t('kb.aside.toc')}>
      <div className="kb-label">{t('kb.aside.toc')}</div>
      {items.map((h) => (
        <a key={h.id} href={`#${h.id}`} data-level={h.level} aria-current={h.id === active ? 'location' : undefined}>
          {h.text}
        </a>
      ))}
    </nav>
  );
}

function useActiveHeading(items: OutlineItem[]): string | undefined {
  const [active, setActive] = useState<string | undefined>(items[0]?.id);
  useEffect(() => {
    if (typeof IntersectionObserver === 'undefined' || items.length === 0) return;
    const visible = new Set<string>();
    const observer = new IntersectionObserver(
      (entries) => {
        for (const e of entries) {
          if (e.isIntersecting) visible.add(e.target.id);
          else visible.delete(e.target.id);
        }
        const first = items.find((h) => visible.has(h.id));
        if (first) setActive(first.id);
      },
      { rootMargin: '-72px 0px -60% 0px' },
    );
    for (const h of items) {
      const el = document.getElementById(h.id);
      if (el) observer.observe(el);
    }
    return () => observer.disconnect();
  }, [items]);
  return active;
}

/** Похожие статьи: подсказки по названию, без самой статьи. */
function Related({ article, base }: { article: ArticleDetails; base: string }) {
  const { t } = useTranslation();
  const related = useArticleSuggestions(article.status === 'published' ? article.title : '', 5);
  const items = (related.data ?? []).filter((x) => x.id !== article.id).slice(0, 4);
  if (items.length === 0) return null;
  return (
    <div className="kb-related">
      <div className="kb-label">{t('kb.aside.related')}</div>
      <Stack gap={8}>
        {items.map((x) => (
          <Link key={x.id} to={`${base}/${x.id}`}>
            {x.title}
          </Link>
        ))}
      </Stack>
    </div>
  );
}

function StatusNotices({ article: a }: { article: ArticleDetails }) {
  const { t } = useTranslation();
  const fmt = useDateFormat();
  return (
    <>
      {a.status === 'draft' && a.reviewComment && (
        <Notice tone="warn" title={t('kb.article.returned')}>
          {a.reviewComment}
        </Notice>
      )}
      {a.status === 'review' && <Notice tone="wait">{t('kb.article.inReview')}</Notice>}
      {a.status === 'review_due' && (
        <Notice tone="warn">{t('kb.article.reviewDue', { date: fmt.date(a.reviewAt) })}</Notice>
      )}
      {a.status === 'archived' && <Notice tone="neutral">{t('kb.article.archived')}</Notice>}
    </>
  );
}
