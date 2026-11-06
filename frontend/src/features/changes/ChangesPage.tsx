import { Anchor, Button, Group, Select, Stack, Table, Tabs, Text, TextInput, Title } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { IconCalendarEvent, IconPlus, IconSearch, IconUsersGroup } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { usePersonName } from '@/shared/api/people';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import { type ChangeFilter, type ChangeItem, useChanges, usePendingApprovals } from './api';
import { ChangeNumber, RiskBadge, StateBadge, TypeBadge } from './ChangeBadges';
import classes from './changes.module.css';
import { formatWindow, STATES } from './model';

/** «Изменения» (10.11): списки по вкладкам, фильтры и переходы в календарь и CAB. */
export function ChangesPage() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const tab = params.get('tab') ?? 'active';
  const setTab = (v: string | null) => setParams(v && v !== 'active' ? { tab: v } : {}, { replace: true });

  return (
    <Stack gap="md" maw={1280}>
      <Group justify="space-between" align="flex-end" wrap="wrap">
        <div>
          <Title order={1} fz={24}>
            {t('changes.title')}
          </Title>
          <Text size="sm" c="dimmed">
            {t('changes.subtitle')}
          </Text>
        </div>
        <Group gap="xs">
          <Button variant="default" leftSection={<IconCalendarEvent size={16} />} component={Link} to="/agent/changes/calendar">
            {t('changes.calendar.title')}
          </Button>
          <Button variant="default" leftSection={<IconUsersGroup size={16} />} component={Link} to="/agent/changes/cab">
            {t('changes.cab.title')}
          </Button>
          <Button leftSection={<IconPlus size={16} />} component={Link} to="/agent/changes/new">
            {t('changes.create')}
          </Button>
        </Group>
      </Group>
      <Tabs value={tab} onChange={setTab} keepMounted={false}>
        <Tabs.List>
          <Tabs.Tab value="active">{t('changes.tabs.active')}</Tabs.Tab>
          <Tabs.Tab value="mine">{t('changes.tabs.mine')}</Tabs.Tab>
          <Tabs.Tab value="approvals">{t('changes.tabs.approvals')}</Tabs.Tab>
          <Tabs.Tab value="all">{t('changes.tabs.all')}</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="active" pt="md">
          <ChangeList base={{ state: 'assessment,approval,scheduled,implementation,verification,review' }} />
        </Tabs.Panel>
        <Tabs.Panel value="mine" pt="md">
          <ChangeList base={{ mine: true }} />
        </Tabs.Panel>
        <Tabs.Panel value="approvals" pt="md">
          <PendingList />
        </Tabs.Panel>
        <Tabs.Panel value="all" pt="md">
          <ChangeList base={{}} withState />
        </Tabs.Panel>
      </Tabs>
    </Stack>
  );
}

function ChangeList({ base, withState = false }: { base: ChangeFilter; withState?: boolean }) {
  const { t } = useTranslation();
  const [q, setQ] = useState('');
  const [debounced] = useDebouncedValue(q, 300);
  const [type, setType] = useState<string | null>(null);
  const [risk, setRisk] = useState<string | null>(null);
  const [state, setState] = useState<string | null>(null);
  const filter: ChangeFilter = {
    ...base,
    q: debounced.trim() || undefined,
    type: type ?? undefined,
    risk: risk ?? undefined,
    state: state ?? base.state,
  };
  const query = useChanges(filter);
  const items = query.data?.pages.flatMap((p) => p.items) ?? [];
  const filtered = !!(debounced || type || risk || state);
  const listState = listStateOf(query, items.length, filtered);
  const reset = () => {
    setQ('');
    setType(null);
    setRisk(null);
    setState(null);
  };

  return (
    <Stack gap="sm">
      <Group gap="xs" wrap="wrap">
        <TextInput
          leftSection={<IconSearch size={16} />}
          placeholder={t('changes.filters.search')}
          aria-label={t('changes.filters.search')}
          value={q}
          onChange={(e) => setQ(e.currentTarget.value)}
          w={280}
        />
        <Select
          placeholder={t('changes.filters.type')}
          aria-label={t('changes.filters.type')}
          clearable
          value={type}
          onChange={setType}
          data={(['standard', 'normal', 'emergency'] as const).map((v) => ({ value: v, label: t(`changes.type.${v}`) }))}
          w={180}
        />
        <Select
          placeholder={t('changes.filters.risk')}
          aria-label={t('changes.filters.risk')}
          clearable
          value={risk}
          onChange={setRisk}
          data={(['low', 'medium', 'high'] as const).map((v) => ({ value: v, label: t(`changes.risk.level.${v}`) }))}
          w={160}
        />
        {withState && (
          <Select
            placeholder={t('changes.filters.state')}
            aria-label={t('changes.filters.state')}
            clearable
            value={state}
            onChange={setState}
            data={STATES.map((v) => ({ value: v, label: t(`changes.state.${v}`) }))}
            w={200}
          />
        )}
      </Group>
      {listState ? (
        <ListState
          state={listState}
          title={listState === 'empty' ? t('changes.empty') : undefined}
          error={query.error}
          onRetry={() => void query.refetch()}
          onResetFilters={reset}
          action={
            listState === 'empty' ? (
              <Button size="xs" variant="light" component={Link} to="/agent/changes/new">
                {t('changes.create')}
              </Button>
            ) : undefined
          }
        />
      ) : (
        <>
          <ChangeTable items={items} />
          {query.hasNextPage && (
            <Button variant="subtle" onClick={() => void query.fetchNextPage()} loading={query.isFetchingNextPage} w="fit-content">
              {t('changes.more')}
            </Button>
          )}
        </>
      )}
    </Stack>
  );
}

export function ChangeTable({ items }: { items: ChangeItem[] }) {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  return (
    <div className={classes.tableScroll}>
      <Table highlightOnHover verticalSpacing="sm" miw={860}>
        <Table.Thead>
          <Table.Tr>
            <Table.Th w={120}>{t('changes.columns.number')}</Table.Th>
            <Table.Th>{t('changes.columns.title')}</Table.Th>
            <Table.Th w={130}>{t('changes.columns.type')}</Table.Th>
            <Table.Th w={150}>{t('changes.columns.state')}</Table.Th>
            <Table.Th w={110}>{t('changes.columns.risk')}</Table.Th>
            <Table.Th w={220}>{t('changes.columns.window')}</Table.Th>
            <Table.Th w={160}>{t('changes.columns.author')}</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {items.map((c) => (
            <Table.Tr key={c.id} style={{ cursor: 'pointer' }} onClick={() => navigate(`/agent/changes/${c.id}`)}>
              <Table.Td>
                <ChangeNumber number={c.number} />
              </Table.Td>
              <Table.Td>
                <Anchor component={Link} to={`/agent/changes/${c.id}`} onClick={(e) => e.stopPropagation()} c="var(--ei-text)" fw={600}>
                  {c.title}
                </Anchor>
              </Table.Td>
              <Table.Td>
                <TypeBadge type={c.type} />
              </Table.Td>
              <Table.Td>
                <StateBadge state={c.state} />
              </Table.Td>
              <Table.Td>
                <RiskBadge level={c.riskLevel} />
              </Table.Td>
              <Table.Td>
                <Text size="sm">{formatWindow(c, i18n.language)}</Text>
              </Table.Td>
              <Table.Td>
                <Person id={c.authorId} />
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </div>
  );
}

export function Person({ id }: { id: string | null | undefined }) {
  const name = usePersonName(id);
  return (
    <Text size="sm" c="var(--ei-text-2)" truncate>
      {id ? name : '—'}
    </Text>
  );
}

/** «Ждут моего решения»: слоты согласования, которые может решить пользователь (CHG-04). */
function PendingList() {
  const { t } = useTranslation();
  const pending = usePendingApprovals();
  const items = pending.data ?? [];
  const state = listStateOf(pending, items.length);
  if (state) {
    return (
      <ListState
        state={state}
        title={state === 'empty' ? t('changes.pendingEmpty') : undefined}
        error={pending.error}
        onRetry={() => void pending.refetch()}
      />
    );
  }
  return <ChangeTable items={items.map((p) => p.change)} />;
}
