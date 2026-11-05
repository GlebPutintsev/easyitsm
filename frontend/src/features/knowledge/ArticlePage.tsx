import { Alert, Anchor, Breadcrumbs, Divider, Group, Paper, SegmentedControl, Stack, Text, Title } from '@mantine/core';
import { IconAlertTriangle, IconArchive, IconInfoCircle, IconTicket } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { ApiError } from '@/shared/api/client';
import { ListState } from '@/shared/ui/ListState';
import { useArticle, useCategories } from './api';
import { ArticleActions } from './ArticleActions';
import { ArticleFeedback } from './ArticleFeedback';
import { ArticleMarkdown } from './ArticleMarkdown';
import { AudienceBadge, StatusBadge, useDateFormat } from './ui';

/**
 * Страница статьи. На портале — опубликованная версия и оценка, в рабочем месте — ещё статус,
 * черновик изменений и кнопки жизненного цикла.
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

  if (published.isPending) return <ListState state="loading" rows={6} />;
  if (published.error) {
    const notFound = published.error instanceof ApiError && published.error.status === 404;
    return notFound ? (
      <ListState state="empty" title={t('kb.article.notFound')} />
    ) : (
      <ListState state="error" error={published.error} onRetry={() => void published.refetch()} />
    );
  }

  const meta = published.data;
  const a = showDraft && draft.data ? draft.data : meta;
  const agent = space === 'agent';
  const category = categories.data?.find((c) => c.id === a.categoryId)?.name;

  return (
    <Stack maw={920} mx="auto" gap="md" py="md">
      <Breadcrumbs>
        <Anchor component={Link} to={base} size="sm">
          {t('kb.title')}
        </Anchor>
        {category && <Text size="sm">{category}</Text>}
      </Breadcrumbs>

      <Stack gap={6}>
        <Group justify="space-between" align="flex-start" wrap="nowrap">
          <Title order={1} fz={{ base: 24, sm: 30 }}>
            {a.title}
          </Title>
          {agent && <StatusBadge status={meta.status} />}
        </Group>
        <Group gap="xs">
          <Text size="sm" c="dimmed">
            {a.key} · {t('kb.article.version', { no: a.shownVersionNo })} ·{' '}
            {meta.publishedAt ? t('kb.article.publishedAt', { date: fmt.date(meta.publishedAt) }) : t('kb.article.notPublished')}
          </Text>
          {agent && <AudienceBadge audience={a.audience} />}
        </Group>
      </Stack>

      {agent && <ArticleActions article={meta} />}

      {agent && <StatusNotices article={meta} />}

      {agent && hasDraft && (
        <Alert color="blue" icon={<IconInfoCircle />} p="sm">
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
        </Alert>
      )}

      <Paper withBorder radius="md" p={{ base: 'md', sm: 'xl' }}>
        {a.body.trim() ? <ArticleMarkdown>{a.body}</ArticleMarkdown> : <Text c="dimmed">{t('kb.article.emptyBody')}</Text>}
      </Paper>

      {meta.publishedVersionNo != null && meta.status !== 'archived' && (
        <>
          <Divider />
          <ArticleFeedback article={meta} />
        </>
      )}
    </Stack>
  );
}

function StatusNotices({ article: a }: { article: NonNullable<ReturnType<typeof useArticle>['data']> }) {
  const { t } = useTranslation();
  const fmt = useDateFormat();
  return (
    <>
      {a.status === 'draft' && a.reviewComment && (
        <Alert color="orange" icon={<IconAlertTriangle />} title={t('kb.article.returned')}>
          {a.reviewComment}
        </Alert>
      )}
      {a.status === 'review' && (
        <Alert color="yellow" icon={<IconInfoCircle />}>
          {t('kb.article.inReview')}
        </Alert>
      )}
      {a.status === 'review_due' && (
        <Alert color="orange" icon={<IconAlertTriangle />}>
          {t('kb.article.reviewDue', { date: fmt.date(a.reviewAt) })}
        </Alert>
      )}
      {a.status === 'archived' && (
        <Alert color="gray" icon={<IconArchive />}>
          {t('kb.article.archived')}
        </Alert>
      )}
      {a.status === 'published' && a.reviewAt && (
        <Text size="xs" c="dimmed">
          {t('kb.article.nextReview', { date: fmt.date(a.reviewAt) })}
        </Text>
      )}
      {a.sourceTicketId && (
        <Text size="xs" c="dimmed">
          <IconTicket size={14} style={{ verticalAlign: 'middle' }} /> {t('kb.article.fromTicket', { ref: a.sourceTicketRef ?? a.sourceTicketId })}
        </Text>
      )}
    </>
  );
}
