import { Button, Chip, Group, ScrollArea, Skeleton, TextInput } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { IconSearch, IconStar } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import { type Category, useCatalogItems, useCategories, useTeams } from './api';
import { ItemTile } from './ItemTile';
import '@/features/portal/portal.css';

/**
 * Каталог услуг на портале (10.4, макет «Каталог услуг»): дерево категорий слева, поиск, чипы команд,
 * карточки услуг. Состояние фильтров в адресе: ссылкой можно поделиться.
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

  const set = (changes: Record<string, string | undefined>) => {
    const next = new URLSearchParams(params);
    for (const [key, value] of Object.entries(changes)) {
      if (value) next.set(key, value);
      else next.delete(key);
    }
    setParams(next, { replace: true });
  };
  const reset = () => setParams(new URLSearchParams(), { replace: true });
  const cats = categories.data ?? [];

  return (
    <div className="ep-page">
      <nav className="ep-crumbs" aria-label={t('portal.breadcrumbs')}>
        <Link to="/portal">{t('nav.home')}</Link> / {t('catalog.title')}
      </nav>
      <Group justify="space-between" align="flex-end" gap="md">
        <div>
          <h1 className="ep-h1">{t('catalog.title')}</h1>
          <p className="ep-lead">{t('catalog.lead')}</p>
        </div>
        <TextInput
          style={{ flex: '0 1 380px' }}
          leftSection={<IconSearch size={16} />}
          placeholder={t('catalog.searchPlaceholder')}
          aria-label={t('catalog.searchPlaceholder')}
          value={q}
          onChange={(e) => set({ q: e.currentTarget.value || undefined })}
        />
      </Group>

      <div className="ep-catalog">
        <nav className="ep-cats" aria-label={t('catalog.categories')}>
          <button
            type="button"
            className="ep-cat"
            aria-current={!categoryId && !favorites ? 'true' : undefined}
            onClick={() => set({ category: undefined, favorites: undefined })}
          >
            {t('catalog.allCategories')}
          </button>
          <button
            type="button"
            className="ep-cat"
            aria-current={favorites ? 'true' : undefined}
            onClick={() => set({ favorites: favorites ? undefined : '1', category: undefined })}
          >
            <IconStar size={16} aria-hidden="true" />
            {t('catalog.favorites')}
          </button>
          {cats.length > 0 && <div className="ep-divider" />}
          {categories.isPending && <Skeleton height={36} />}
          <CategoryTree categories={cats} selected={categoryId} onSelect={(id) => set({ category: id, favorites: undefined })} />
        </nav>

        <div className="ep-catalog-main">
          <Group gap="xs" wrap="wrap">
            <ScrollArea type="never" hiddenFrom="sm" w="100%">
              <Group gap="xs" wrap="nowrap">
                <Chip checked={favorites} onChange={(on) => set({ favorites: on ? '1' : undefined })} icon={<IconStar size={14} />}>
                  {t('catalog.favorites')}
                </Chip>
                {cats
                  .filter((c) => c.parentId === null)
                  .map((c) => (
                    <Chip key={c.id} checked={categoryId === c.id} onChange={(on) => set({ category: on ? c.id : undefined })}>
                      {c.name}
                    </Chip>
                  ))}
              </Group>
            </ScrollArea>
            {(teams.data ?? []).map((name) => (
              <Chip key={name} variant="outline" checked={team === name} onChange={(on) => set({ team: on ? name : undefined })}>
                {t('catalog.teamChip', { name })}
              </Chip>
            ))}
            <div style={{ flex: 1 }} />
            {!state && !items.hasNextPage && (
              <span style={{ fontSize: 13, color: 'var(--ei-text-3)' }}>{t('catalog.count', { count: list.length })}</span>
            )}
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
              <div className="ep-services">
                {list.map((item) => (
                  <ItemTile key={item.id} item={item} />
                ))}
              </div>
              {items.hasNextPage && (
                <Button variant="subtle" onClick={() => void items.fetchNextPage()} loading={items.isFetchingNextPage} mx="auto">
                  {t('actions.showMore')}
                </Button>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
}

/** Дерево категорий: вложенные с отступом, до трёх уровней. */
function CategoryTree({ categories, selected, onSelect, parentId = null, level = 0 }: {
  categories: Category[];
  selected?: string;
  onSelect: (id: string) => void;
  parentId?: string | null;
  level?: number;
}) {
  const children = categories.filter((c) => c.parentId === parentId);
  return (
    <>
      {children.map((c) => (
        <div key={c.id} style={{ display: 'contents' }}>
          <button
            type="button"
            className="ep-cat"
            data-level={Math.min(level, 2)}
            aria-current={selected === c.id ? 'true' : undefined}
            onClick={() => onSelect(c.id)}
          >
            {c.name}
          </button>
          <CategoryTree categories={categories} selected={selected} onSelect={onSelect} parentId={c.id} level={level + 1} />
        </div>
      ))}
    </>
  );
}
