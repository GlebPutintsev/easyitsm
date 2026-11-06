import { Button, Group, Stack, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import { useArticleList, useCategories, type ListFilter } from './api';
import { StatusBadge, useDateFormat } from './ui';
import './knowledge.css';

interface Props {
  filter: ListFilter;
  /** Куда ведёт карточка: портал или рабочее место */
  linkTo: (id: string) => string;
  filtered: boolean;
  onResetFilters?: () => void;
  emptyTitle: string;
  showStatus?: boolean;
}

/** Список статей карточками с подгрузкой по курсору и обязательными состояниями (UI-02). */
export function ArticleList({ filter, linkTo, filtered, onResetFilters, emptyTitle, showStatus }: Props) {
  const { t } = useTranslation();
  const fmt = useDateFormat();
  const list = useArticleList(filter);
  const categories = useCategories();
  const items = list.data?.pages.flatMap((p) => p.items) ?? [];
  const state = listStateOf(list, items.length, filtered);
  const categoryName = (id: string | null) => categories.data?.find((c) => c.id === id)?.name;

  if (state) {
    return (
      <ListState
        state={state}
        title={state === 'empty' ? emptyTitle : undefined}
        error={list.error}
        onRetry={() => void list.refetch()}
        onResetFilters={onResetFilters}
        rows={4}
      />
    );
  }

  return (
    <Stack gap="sm" style={{ opacity: list.isPlaceholderData ? 0.6 : 1 }}>
      {items.map((a) => (
        <Link key={a.id} to={linkTo(a.id)} className="kb-card">
          <Group justify="space-between" align="flex-start" wrap="nowrap" gap="sm">
            <Stack gap={4} style={{ minWidth: 0 }}>
              <span className="kb-card-title">{a.title}</span>
              {a.snippet && (
                <Text size="sm" c="var(--ei-text-2)" lineClamp={2}>
                  {a.snippet}
                </Text>
              )}
              <Group gap="xs" className="kb-meta" mb={0}>
                <span className="ei-mono">{a.key}</span>
                {categoryName(a.categoryId) && <span>{categoryName(a.categoryId)}</span>}
                <span>{showStatus ? t('kb.list.updated', { date: fmt.date(a.updatedAt) }) : fmt.date(a.publishedAt)}</span>
                {showStatus && a.publishedVersionNo != null && a.currentVersionNo > a.publishedVersionNo && (
                  <span>{t('kb.list.publishedVersion', { no: a.publishedVersionNo })}</span>
                )}
              </Group>
            </Stack>
            {showStatus && <StatusBadge status={a.status} />}
          </Group>
        </Link>
      ))}
      {list.hasNextPage && (
        <Button variant="subtle" onClick={() => void list.fetchNextPage()} loading={list.isFetchingNextPage} w="fit-content" mx="auto">
          {t('kb.list.more')}
        </Button>
      )}
    </Stack>
  );
}
