import {
  Anchor,
  Badge,
  Button,
  Checkbox,
  Group,
  Modal,
  NumberInput,
  Select,
  Stack,
  Table,
  Tabs,
  Text,
  TextInput,
  Textarea,
  Title,
} from '@mantine/core';
import { IconPlus } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import { type Category, useCatalogItems, useCategories, useSaveCategory } from '../api';
import { ErrorLine } from '@/features/portal/TicketPage';
import { FlowsPanel } from './FlowsPanel';

/** Управление каталогом (ADM): услуги, категории, маршруты согласования. */
export function CatalogAdminPage() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const tab = params.get('tab') ?? 'items';
  return (
    <Stack gap="md" maw={1200}>
      <Title order={2}>{t('admin.catalog.title')}</Title>
      <Tabs value={tab} onChange={(v) => setParams(v && v !== 'items' ? { tab: v } : {}, { replace: true })} keepMounted={false}>
        <Tabs.List>
          <Tabs.Tab value="items">{t('admin.catalog.tabs.items')}</Tabs.Tab>
          <Tabs.Tab value="categories">{t('admin.catalog.tabs.categories')}</Tabs.Tab>
          <Tabs.Tab value="flows">{t('admin.catalog.tabs.flows')}</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="items" pt="md">
          <ItemsPanel />
        </Tabs.Panel>
        <Tabs.Panel value="categories" pt="md">
          <CategoriesPanel />
        </Tabs.Panel>
        <Tabs.Panel value="flows" pt="md">
          <FlowsPanel />
        </Tabs.Panel>
      </Tabs>
    </Stack>
  );
}

function ItemsPanel() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [q, setQ] = useState('');
  const [categoryId, setCategoryId] = useState<string | null>(null);
  const categories = useCategories(true);
  const items = useCatalogItems({ q, categoryId: categoryId ?? undefined, all: true });
  const rows = items.data?.pages.flatMap((p) => p.items) ?? [];
  const state = listStateOf(items, rows.length, !!(q || categoryId));
  const catName = (id: string) => categories.data?.find((c) => c.id === id)?.name ?? '—';

  return (
    <Stack gap="sm">
      <Group justify="space-between">
        <Group gap="xs">
          <TextInput placeholder={t('admin.catalog.search')} aria-label={t('admin.catalog.search')} value={q} onChange={(e) => setQ(e.currentTarget.value)} />
          <Select
            placeholder={t('admin.catalog.allCategories')}
            aria-label={t('admin.catalog.category')}
            clearable
            value={categoryId}
            onChange={setCategoryId}
            data={(categories.data ?? []).map((c) => ({ value: c.id, label: c.name }))}
          />
        </Group>
        <Button leftSection={<IconPlus size={16} />} component={Link} to="/admin/catalog/items/new" disabled={!categories.data?.length}>
          {t('admin.catalog.newItem')}
        </Button>
      </Group>
      {!categories.isPending && !categories.data?.length && <Text c="dimmed">{t('admin.catalog.needCategory')}</Text>}
      {state ? (
        <ListState state={state} title={state === 'empty' ? t('admin.catalog.noItems') : undefined} error={items.error} onRetry={() => void items.refetch()} onResetFilters={() => { setQ(''); setCategoryId(null); }} />
      ) : (
        <Table highlightOnHover verticalSpacing="sm">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t('admin.catalog.name')}</Table.Th>
              <Table.Th>{t('admin.catalog.category')}</Table.Th>
              <Table.Th>{t('admin.catalog.team')}</Table.Th>
              <Table.Th>{t('admin.catalog.state')}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {rows.map((r) => (
              <Table.Tr key={r.id} style={{ cursor: 'pointer' }} onClick={() => navigate(`/admin/catalog/items/${r.id}`)}>
                <Table.Td>
                  <Anchor component={Link} to={`/admin/catalog/items/${r.id}`} onClick={(e) => e.stopPropagation()}>
                    {r.name}
                  </Anchor>
                  {r.kind === 'incident' && (
                    <Badge ml="xs" size="xs" color="orange" variant="light">
                      {t('admin.catalog.kinds.incident')}
                    </Badge>
                  )}
                </Table.Td>
                <Table.Td>{catName(r.categoryId)}</Table.Td>
                <Table.Td>{r.team ?? '—'}</Table.Td>
                <Table.Td>
                  <Badge variant="light" color={r.active ? 'teal' : 'gray'}>
                    {r.active ? t('admin.catalog.activeState') : t('admin.catalog.inactive')}
                  </Badge>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
      {items.hasNextPage && (
        <Button variant="subtle" mx="auto" onClick={() => void items.fetchNextPage()} loading={items.isFetchingNextPage}>
          {t('actions.showMore')}
        </Button>
      )}
    </Stack>
  );
}

function CategoriesPanel() {
  const { t } = useTranslation();
  const categories = useCategories(true);
  const [editing, setEditing] = useState<Partial<Category> | null>(null);
  const list = categories.data ?? [];
  const state = listStateOf(categories, list.length);
  const ordered = flatten(list);

  return (
    <Stack gap="sm">
      <Group justify="flex-end">
        <Button leftSection={<IconPlus size={16} />} onClick={() => setEditing({ name: '', active: true, sortOrder: 0, parentId: null })}>
          {t('admin.catalog.newCategory')}
        </Button>
      </Group>
      {state ? (
        <ListState state={state} title={state === 'empty' ? t('admin.catalog.noCategories') : undefined} error={categories.error} onRetry={() => void categories.refetch()} />
      ) : (
        <Table highlightOnHover verticalSpacing="xs">
          <Table.Tbody>
            {ordered.map(({ c, depth }) => (
              <Table.Tr key={c.id} style={{ cursor: 'pointer' }} onClick={() => setEditing(c)}>
                <Table.Td style={{ paddingLeft: 12 + depth * 24 }}>
                  <Text size="sm" c={c.active ? undefined : 'dimmed'}>
                    {c.name}
                  </Text>
                </Table.Td>
                <Table.Td w={120}>
                  {!c.active && (
                    <Badge variant="light" color="gray">
                      {t('admin.catalog.inactive')}
                    </Badge>
                  )}
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
      {editing && <CategoryDialog category={editing} all={list} onClose={() => setEditing(null)} />}
    </Stack>
  );
}

function flatten(list: Category[], parentId: string | null = null, depth = 0): { c: Category; depth: number }[] {
  return list
    .filter((c) => c.parentId === parentId)
    .flatMap((c) => [{ c, depth }, ...flatten(list, c.id, depth + 1)]);
}

function CategoryDialog({ category, all, onClose }: { category: Partial<Category>; all: Category[]; onClose: () => void }) {
  const { t } = useTranslation();
  const save = useSaveCategory();
  const [c, setC] = useState(category);
  const set = <K extends keyof Category>(k: K, v: Category[K]) => setC((x) => ({ ...x, [k]: v }));
  return (
    <Modal opened onClose={onClose} title={category.id ? t('admin.catalog.editCategory') : t('admin.catalog.newCategory')} centered>
      <Stack gap="sm">
        <TextInput label={t('admin.catalog.name')} required maxLength={120} value={c.name ?? ''} onChange={(e) => set('name', e.currentTarget.value)} data-autofocus />
        <Select
          label={t('admin.catalog.parent')}
          clearable
          value={c.parentId ?? null}
          onChange={(v) => set('parentId', v)}
          data={all.filter((x) => x.id !== c.id).map((x) => ({ value: x.id, label: x.name }))}
        />
        <Textarea label={t('admin.catalog.description')} autosize value={c.description ?? ''} onChange={(e) => set('description', e.currentTarget.value)} />
        <NumberInput label={t('admin.catalog.sortOrder')} value={c.sortOrder ?? 0} onChange={(v) => set('sortOrder', Number(v) || 0)} />
        <Checkbox label={t('admin.catalog.active')} checked={c.active ?? true} onChange={(e) => set('active', e.currentTarget.checked)} />
        <ErrorLine error={save.error} />
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            {t('actions.cancel')}
          </Button>
          <Button
            loading={save.isPending}
            disabled={!c.name?.trim()}
            onClick={() =>
              save.mutate(
                { id: c.id, version: c.version, input: { parentId: c.parentId ?? null, name: c.name, description: c.description, icon: c.icon, sortOrder: c.sortOrder, active: c.active } },
                { onSuccess: onClose },
              )
            }
          >
            {t('admin.catalog.save')}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
