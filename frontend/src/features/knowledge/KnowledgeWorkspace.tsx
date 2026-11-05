import { Button, Group, ScrollArea, Select, Stack, Tabs, TextInput, Title } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { IconPlus, IconSearch } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router';
import { STATUSES, useCategories, type ArticleStatus, type ListFilter } from './api';
import { ArticleList } from './ArticleList';

const TABS = ['all', 'mine', 'awaitingReview', ...STATUSES] as const;
type Tab = (typeof TABS)[number];

function filterOf(tab: Tab): Partial<ListFilter> {
  if (tab === 'mine') return { mine: true };
  if (tab === 'awaitingReview') return { awaitingMyReview: true };
  if (tab === 'all') return {};
  return { status: tab as ArticleStatus };
}

/** База знаний в рабочем месте: все статусы, мои статьи, очередь проверки (10.12). */
export function KnowledgeWorkspace() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const tab = (TABS as readonly string[]).includes(params.get('tab') ?? '') ? (params.get('tab') as Tab) : 'all';
  const q = params.get('q') ?? '';
  const category = params.get('category') ?? '';
  const [debounced] = useDebouncedValue(q, 300);
  const categories = useCategories();

  const set = (key: string, value: string | null) =>
    setParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        if (value) next.set(key, value);
        else next.delete(key);
        return next;
      },
      { replace: true },
    );

  return (
    <Stack gap="md">
      <Group justify="space-between">
        <Title order={2}>{t('kb.title')}</Title>
        <Button component={Link} to="/agent/kb/new" leftSection={<IconPlus size={16} />}>
          {t('kb.actions.new')}
        </Button>
      </Group>
      <Tabs value={tab} onChange={(v) => set('tab', v === 'all' ? null : v)}>
        <ScrollArea type="never">
          <Tabs.List style={{ flexWrap: 'nowrap' }}>
            {TABS.map((tb) => (
              <Tabs.Tab key={tb} value={tb}>
                {t(`kb.tabs.${tb}`)}
              </Tabs.Tab>
            ))}
          </Tabs.List>
        </ScrollArea>
      </Tabs>
      <Group gap="sm" grow preventGrowOverflow={false}>
        <TextInput
          value={q}
          onChange={(e) => set('q', e.currentTarget.value)}
          placeholder={t('kb.workspace.searchPlaceholder')}
          aria-label={t('kb.workspace.searchPlaceholder')}
          leftSection={<IconSearch size={16} />}
          style={{ flex: 2 }}
        />
        <Select
          value={category || null}
          onChange={(v) => set('category', v)}
          placeholder={t('kb.fields.category')}
          aria-label={t('kb.fields.category')}
          data={(categories.data ?? []).map((c) => ({ value: c.id, label: c.name }))}
          clearable
          style={{ flex: 1 }}
        />
      </Group>
      <ArticleList
        filter={{ view: 'workspace', q: debounced || undefined, categoryId: category || undefined, ...filterOf(tab) }}
        linkTo={(id) => `/agent/kb/${id}`}
        filtered={!!debounced || !!category || tab !== 'all'}
        onResetFilters={() => setParams({}, { replace: true })}
        emptyTitle={t('kb.workspace.empty')}
        showStatus
      />
    </Stack>
  );
}
