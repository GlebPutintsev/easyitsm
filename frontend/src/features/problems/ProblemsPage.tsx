import { Anchor, Badge, Button, Group, NumberInput, SegmentedControl, Select, Stack, Table, Tabs, Text, TextInput, Title } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { IconPlus, IconSearch } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { hasRole, useMe } from '@/features/auth/api';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import { PriorityBadge, StatusBadge } from '@/shared/ui/TicketBadges';
import { useKnownErrors, useProblemList, useRecurring, type ProblemListState, type RecurringCluster } from './api';
import { CreateProblemDialog, type ProblemDraft } from './CreateProblemDialog';
import { KnownErrorCard } from './KnownErrorsHint';
import classes from './problems.module.css';
import { LinkedTicketRow } from './ui';

/** «Проблемы» рабочего места (PRB-01…05): список, база известных ошибок, отчёт повторяющихся инцидентов. */
export function ProblemsPage() {
  const { t } = useTranslation();
  const me = useMe().data;
  const [params, setParams] = useSearchParams();
  const tab = params.get('tab') ?? 'list';
  const [draft, setDraft] = useState<ProblemDraft | null>(null);
  const canRecurring = hasRole(me, 'PRB', 'LEAD', 'MIM', 'ANL');
  const canCreate = hasRole(me, 'AGT', 'LEAD', 'MIM', 'CHG', 'PRB', 'CFG', 'AST');
  return (
    <Stack gap="md" maw={1200}>
      <Group justify="space-between" align="flex-start" wrap="wrap">
        <div>
          <Title order={2}>{t('problems.title')}</Title>
          <Text size="sm" c="dimmed">
            {t('problems.subtitle')}
          </Text>
        </div>
        {canCreate && (
          <Button leftSection={<IconPlus size={16} />} onClick={() => setDraft({ title: '', description: '', incidentIds: [], source: 'manual' })}>
            {t('problems.new')}
          </Button>
        )}
      </Group>
      <Tabs value={tab} onChange={(v) => setParams(v && v !== 'list' ? { tab: v } : {}, { replace: true })} keepMounted={false}>
        <Tabs.List>
          <Tabs.Tab value="list">{t('problems.tabs.list')}</Tabs.Tab>
          <Tabs.Tab value="knownErrors">{t('problems.tabs.knownErrors')}</Tabs.Tab>
          {canRecurring && <Tabs.Tab value="recurring">{t('problems.tabs.recurring')}</Tabs.Tab>}
        </Tabs.List>
        <Tabs.Panel value="list" pt="md">
          <ProblemList />
        </Tabs.Panel>
        <Tabs.Panel value="knownErrors" pt="md">
          <KnownErrorsBase drafts={hasRole(me, 'PRB', 'LEAD', 'MIM')} />
        </Tabs.Panel>
        {canRecurring && (
          <Tabs.Panel value="recurring" pt="md">
            <Recurring onCreate={setDraft} canCreate={canCreate} />
          </Tabs.Panel>
        )}
      </Tabs>
      <CreateProblemDialog opened={draft !== null} onClose={() => setDraft(null)} draft={draft ?? { title: '', description: '', incidentIds: [], source: 'manual' }} />
    </Stack>
  );
}

function ProblemList() {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const [state, setState] = useState<ProblemListState>('active');
  const list = useProblemList(state);
  const rows = list.data?.pages.flatMap((p) => p.items) ?? [];
  const ls = listStateOf(list, rows.length);
  return (
    <Stack gap="sm">
      <SegmentedControl
        value={state}
        onChange={(v) => setState(v as ProblemListState)}
        data={(['active', 'waiting', 'closed', 'all'] as const).map((s) => ({ value: s, label: t(`problems.states.${s}`) }))}
        style={{ alignSelf: 'flex-start' }}
      />
      {ls ? (
        <ListState
          state={ls}
          title={ls === 'empty' ? t('problems.empty') : undefined}
          error={list.error}
          onRetry={() => void list.refetch()}
          action={ls === 'empty' ? <Text size="sm" c="dimmed">{t('problems.emptyHint')}</Text> : undefined}
        />
      ) : (
        <div style={{ overflowX: 'auto' }}>
          <Table highlightOnHover verticalSpacing="sm" miw={640}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th w={70}>{t('problems.columns.priority')}</Table.Th>
                <Table.Th w={120}>{t('problems.columns.number')}</Table.Th>
                <Table.Th>{t('problems.columns.title')}</Table.Th>
                <Table.Th w={150}>{t('problems.columns.status')}</Table.Th>
                <Table.Th w={120}>{t('problems.columns.created')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((r) => (
                <Table.Tr key={r.id} style={{ cursor: 'pointer' }} onClick={() => void navigate(`/agent/problems/${r.number}`)}>
                  <Table.Td>
                    <PriorityBadge priority={r.priority} />
                  </Table.Td>
                  <Table.Td>
                    <Anchor component={Link} to={`/agent/problems/${r.number}`} className={classes.mono} onClick={(e) => e.stopPropagation()}>
                      {r.number}
                    </Anchor>
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm" lineClamp={1}>
                      {r.title}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <StatusBadge category={r.status.category} label={r.status.name} />
                  </Table.Td>
                  <Table.Td>
                    <Text size="xs" c="dimmed">
                      {new Date(r.createdAt).toLocaleDateString(i18n.language)}
                    </Text>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </div>
      )}
      {list.hasNextPage && (
        <Group justify="center">
          <Button variant="subtle" loading={list.isFetchingNextPage} onClick={() => void list.fetchNextPage()}>
            {t('problems.loadMore')}
          </Button>
        </Group>
      )}
    </Stack>
  );
}

function KnownErrorsBase({ drafts }: { drafts: boolean }) {
  const { t } = useTranslation();
  const [q, setQ] = useState('');
  const [debounced] = useDebouncedValue(q, 300);
  const list = useKnownErrors({ q: debounced.trim() || undefined, includeDrafts: drafts });
  const items = list.data ?? [];
  const ls = listStateOf(list, items.length, debounced.trim() !== '');
  return (
    <Stack gap="sm">
      <TextInput
        leftSection={<IconSearch size={16} />}
        placeholder={t('problems.knownError.search')}
        aria-label={t('problems.knownError.search')}
        value={q}
        onChange={(e) => setQ(e.currentTarget.value)}
        maw={480}
      />
      {ls ? (
        <ListState state={ls} title={t('problems.knownError.emptySearch')} error={list.error} onRetry={() => void list.refetch()} onResetFilters={() => setQ('')} />
      ) : (
        <Stack gap="sm">
          {items.map((k) => (
            <KnownErrorCard key={k.id} knownError={k} showStatus />
          ))}
        </Stack>
      )}
    </Stack>
  );
}

function Recurring({ onCreate, canCreate }: { onCreate: (d: ProblemDraft) => void; canCreate: boolean }) {
  const { t, i18n } = useTranslation();
  const [dimension, setDimension] = useState('service');
  const [days, setDays] = useState('30');
  const [minCount, setMinCount] = useState<number>(3);
  const report = useRecurring({ dimension, days: Number(days), minCount });
  const items = report.data ?? [];
  const ls = listStateOf(report, items.length);
  const name = (c: RecurringCluster) => c.name ?? (c.dimension === 'ci' ? c.key.slice(0, 8) : t('problems.recurring.unknown'));
  return (
    <Stack gap="sm">
      <Text size="sm" c="dimmed">
        {t('problems.recurring.hint')}
      </Text>
      <Group align="flex-end" wrap="wrap">
        <SegmentedControl
          value={dimension}
          onChange={setDimension}
          aria-label={t('problems.recurring.dimension')}
          data={(['service', 'category', 'ci'] as const).map((d) => ({ value: d, label: t(`problems.recurring.dimensions.${d}`) }))}
        />
        <Select
          w={140}
          label={t('problems.recurring.days')}
          value={days}
          onChange={(v) => setDays(v ?? '30')}
          allowDeselect={false}
          data={['7', '30', '90', '180'].map((d) => ({ value: d, label: t('problems.recurring.daysValue', { count: Number(d) }) }))}
        />
        <NumberInput w={110} label={t('problems.recurring.minCount')} min={2} max={1000} value={minCount} onChange={(v) => setMinCount(typeof v === 'number' && v >= 2 ? v : 2)} />
      </Group>
      {ls ? (
        <ListState state={ls} title={t('problems.recurring.empty')} error={report.error} onRetry={() => void report.refetch()} />
      ) : (
        <Stack gap="sm">
          {items.map((c) => (
            <div key={c.key} className={classes.cluster}>
              <div>
                <div className={classes.clusterCount}>{c.count}</div>
                <Text size="xs" c="dimmed">
                  {t('problems.recurring.open', { count: c.open })}
                </Text>
              </div>
              <Stack gap={8} style={{ minWidth: 0 }}>
                <Group gap={8}>
                  {c.dimension === 'ci' ? (
                    <Anchor component={Link} to={`/agent/cmdb/${c.key}`} fw={600}>
                      {name(c)}
                    </Anchor>
                  ) : (
                    <Text fw={600}>{name(c)}</Text>
                  )}
                  <Text size="xs" c="dimmed">
                    {t('problems.recurring.last', { date: new Date(c.lastAt).toLocaleDateString(i18n.language) })}
                  </Text>
                </Group>
                {c.sample.map((s) => (
                  <LinkedTicketRow key={s.id} ticket={s} />
                ))}
                {c.problems.length > 0 && (
                  <Group gap={6}>
                    <Text size="xs" c="dimmed">
                      {t('problems.recurring.existing')}:
                    </Text>
                    {c.problems.map((p) => (
                      <Badge key={p.id} variant="light" component={Link} to={`/agent/problems/${p.number}`} style={{ cursor: 'pointer' }}>
                        {p.number}
                      </Badge>
                    ))}
                  </Group>
                )}
              </Stack>
              {canCreate && c.incidentIds.length > 0 && (
                <Button
                  variant={c.problems.length > 0 ? 'default' : 'filled'}
                  onClick={() =>
                    onCreate({
                      title: t('problems.recurring.problemTitle', { name: name(c) }),
                      description: c.sample.map((s) => `- ${s.number} ${s.title ?? ''}`).join('\n'),
                      incidentIds: c.incidentIds,
                      source: 'recurring',
                    })
                  }
                >
                  {t('problems.recurring.createProblem')}
                </Button>
              )}
            </div>
          ))}
        </Stack>
      )}
    </Stack>
  );
}
