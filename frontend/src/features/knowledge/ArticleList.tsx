import { Anchor, Badge, Button, Card, Group, Stack, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import { useArticleList, useCategories, type ListFilter } from './api';
import { StatusBadge, useDateFormat } from './ui';

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
        <Card key={a.id} withBorder padding="md" radius="md">
          <Group justify="space-between" align="flex-start" wrap="nowrap" gap="sm">
            <Stack gap={4} style={{ minWidth: 0 }}>
              <Anchor component={Link} to={linkTo(a.id)} fw={600} size="md" c="var(--mantine-color-text)">
                {a.title}
              </Anchor>
              {a.snippet && (
                <Text size="sm" c="dimmed" lineClamp={2}>
                  {a.snippet}
                </Text>
              )}
              <Group gap="xs">
                <Text size="xs" c="dimmed">
                  {a.key}
                </Text>
                {categoryName(a.categoryId) && (
                  <Badge size="xs" variant="light" color="gray">
                    {categoryName(a.categoryId)}
                  </Badge>
                )}
                <Text size="xs" c="dimmed">
                  {showStatus ? t('kb.list.updated', { date: fmt.date(a.updatedAt) }) : fmt.date(a.publishedAt)}
                </Text>
                {showStatus && a.publishedVersionNo != null && a.currentVersionNo > a.publishedVersionNo && (
                  <Text size="xs" c="blue">
                    {t('kb.list.publishedVersion', { no: a.publishedVersionNo })}
                  </Text>
                )}
              </Group>
            </Stack>
            {showStatus && <StatusBadge status={a.status} />}
          </Group>
        </Card>
      ))}
      {list.hasNextPage && (
        <Button variant="subtle" onClick={() => void list.fetchNextPage()} loading={list.isFetchingNextPage} w="fit-content" mx="auto">
          {t('kb.list.more')}
        </Button>
      )}
    </Stack>
  );
}
