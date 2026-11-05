import { Box, Button, Chip, Group, NavLink, ScrollArea, SimpleGrid, Stack, TextInput, Title } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { IconSearch, IconStar } from '@tabler/icons-react';
import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import { type Category, useCatalogItems, useCategories, useTeams } from './api';
import { ItemTile } from './ItemTile';

/**
 * Каталог услуг на портале (10.4): дерево категорий фильтрует список, поиск по названию и описанию,
 * фильтр по команде, избранное. Состояние фильтров в адресе: ссылкой можно поделиться.
 */
export function CatalogPage() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const categoryId = params.get('category') ?? undefined;
  const team = params.get('team') ?? undefined;
  const favorites = params.get('favorites') === '1';
  const q = params.get('q') ?? '';
  const [debouncedQ] = useDebouncedValue(q, 250);

  const categories = useCategories();
  const teams = useTeams();
  const items = useCatalogItems({ categoryId, team, favorites, q: debouncedQ });
  const list = items.data?.pages.flatMap((p) => p.items) ?? [];
  const filtered = !!(categoryId || team || favorites || debouncedQ);
  const state = listStateOf(items, list.length, filtered);

  const set = (key: string, value: string | undefined) => {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    setParams(next, { replace: true });
  };
  const reset = () => setParams(new URLSearchParams(), { replace: true });

  return (
    <Stack maw={1200} mx="auto" gap="md" py="md">
      <Title order={2}>{t('catalog.title')}</Title>
      <Group align="flex-start" gap="lg" wrap="nowrap">
        <Box w={240} visibleFrom="sm" component="nav" aria-label={t('catalog.categories')}>
          <ScrollArea.Autosize mah="calc(100vh - 180px)">
            <NavLink label={t('catalog.allCategories')} active={!categoryId} onClick={() => set('category', undefined)} />
            <CategoryTree categories={categories.data ?? []} selected={categoryId} onSelect={(id) => set('category', id)} />
          </ScrollArea.Autosize>
        </Box>

        <Stack gap="sm" style={{ flex: 1, minWidth: 0 }}>
          <TextInput
            leftSection={<IconSearch size={16} />}
            placeholder={t('catalog.searchPlaceholder')}
            aria-label={t('catalog.searchPlaceholder')}
            value={q}
            onChange={(e) => set('q', e.currentTarget.value || undefined)}
          />
          <Group gap="xs">
            <Chip checked={favorites} onChange={(on) => set('favorites', on ? '1' : undefined)} icon={<IconStar size={14} />}>
              {t('catalog.favorites')}
            </Chip>
            {(teams.data ?? []).map((name) => (
              <Chip key={name} checked={team === name} onChange={(on) => set('team', on ? name : undefined)}>
                {name}
              </Chip>
            ))}
            <Box hiddenFrom="sm" w="100%">
              <CategoryChips categories={categories.data ?? []} selected={categoryId} onSelect={(id) => set('category', id)} />
            </Box>
          </Group>

          {state ? (
            <ListState
              state={state}
              title={state === 'empty' ? t('catalog.empty') : undefined}
              error={items.error}
              onRetry={() => void items.refetch()}
              onResetFilters={reset}
              rows={6}
            />
          ) : (
            <>
              <SimpleGrid cols={{ base: 1, xs: 2, lg: 3 }} spacing="md">
                {list.map((item) => (
                  <ItemTile key={item.id} item={item} />
                ))}
              </SimpleGrid>
              {items.hasNextPage && (
                <Button variant="subtle" onClick={() => void items.fetchNextPage()} loading={items.isFetchingNextPage} mx="auto">
                  {t('actions.showMore')}
                </Button>
              )}
            </>
          )}
        </Stack>
      </Group>
    </Stack>
  );
}

function CategoryTree({ categories, selected, onSelect, parentId = null }: {
  categories: Category[];
  selected?: string;
  onSelect: (id: string) => void;
  parentId?: string | null;
}) {
  const children = useMemo(() => categories.filter((c) => c.parentId === parentId), [categories, parentId]);
  return (
    <>
      {children.map((c) => {
        const hasChildren = categories.some((x) => x.parentId === c.id);
        return (
          <NavLink
            key={c.id}
            label={c.name}
            active={selected === c.id}
            defaultOpened={hasChildren}
            onClick={() => onSelect(c.id)}
            childrenOffset={16}
          >
            {hasChildren ? <CategoryTree categories={categories} selected={selected} onSelect={onSelect} parentId={c.id} /> : undefined}
          </NavLink>
        );
      })}
    </>
  );
}

function CategoryChips({ categories, selected, onSelect }: { categories: Category[]; selected?: string; onSelect: (id: string | undefined) => void }) {
  const roots = categories.filter((c) => c.parentId === null);
  return (
    <ScrollArea type="never">
      <Group gap="xs" wrap="nowrap">
        {roots.map((c) => (
          <Chip key={c.id} variant="outline" checked={selected === c.id} onChange={(on) => onSelect(on ? c.id : undefined)}>
            {c.name}
          </Chip>
        ))}
      </Group>
    </ScrollArea>
  );
}
