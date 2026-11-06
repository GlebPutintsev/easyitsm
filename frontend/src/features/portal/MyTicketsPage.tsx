import { Anchor, Badge, Button, Group, Select, Stack, Table, Tabs, Text, TextInput } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { IconSearch } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import { useApprovalCount } from '@/features/catalog/api';
import { ApprovalsList } from '@/features/catalog/ApprovalsPage';
import { usePersonName } from '@/shared/api/people';
import type { Ticket, TicketSort } from '@/features/tickets/api';
import { shortDateTime } from './format';
import { type MyTicketsTab, useMyTickets } from './requester';
import { TicketStatus } from './ui';
import './portal.css';

type Tab = MyTicketsTab | 'approvals';
const PERIODS = { '7': 7, '30': 30, '90': 90, '365': 365 } as const;

/**
 * Мои заявки (10.5): вкладки «Активные», «Ждут меня», «Закрытые», «Запросы на согласование»,
 * поиск, фильтр по типу и дате, сортировка. Фильтры хранятся в адресе.
 */
export function MyTicketsPage() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const tab = (params.get('tab') as Tab) ?? 'active';
  const approvals = useApprovalCount();
  const set = (key: string, value: string | null) => {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    setParams(next, { replace: true });
  };

  return (
    <div className="ep-page">
      <Group justify="space-between">
        <h1 className="ep-h1">{t('myTickets.title')}</h1>
        <Group gap="xs">
          <Button variant="default" component={Link} to="/portal/report">
            {t('portal.reportProblem')}
          </Button>
          <Button component={Link} to="/portal/catalog">
            {t('portal.orderService')}
          </Button>
        </Group>
      </Group>
      <Tabs value={tab} onChange={(v) => set('tab', v === 'active' ? null : v)} keepMounted={false}>
        <Tabs.List>
          <Tabs.Tab value="active">{t('myTickets.tabs.active')}</Tabs.Tab>
          <Tabs.Tab value="waiting">{t('myTickets.tabs.waiting')}</Tabs.Tab>
          <Tabs.Tab value="closed">{t('myTickets.tabs.closed')}</Tabs.Tab>
          <Tabs.Tab
            value="approvals"
            rightSection={approvals.data ? <Badge size="xs" circle>{approvals.data}</Badge> : undefined}
          >
            {t('myTickets.tabs.approvals')}
          </Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="approvals" pt="md">
          <ApprovalsList tab="pending" />
        </Tabs.Panel>
        {(['active', 'waiting', 'closed'] as const).map((s) => (
          <Tabs.Panel key={s} value={s} pt="md">
            <TicketList state={s} params={params} set={set} />
          </Tabs.Panel>
        ))}
      </Tabs>
    </div>
  );
}

function TicketList({ state, params, set }: { state: MyTicketsTab; params: URLSearchParams; set: (k: string, v: string | null) => void }) {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const q = params.get('q') ?? '';
  const [debouncedQ] = useDebouncedValue(q, 300);
  const type = params.get('type') ?? undefined;
  const period = params.get('period') as keyof typeof PERIODS | null;
  const sort = (params.get('sort') as TicketSort | null) ?? '-updated';
  // Граница периода — начало дня: ключ запроса не меняется при каждой отрисовке.
  const [today] = useState(() => new Date().setHours(0, 0, 0, 0));
  const createdFrom = period ? new Date(today - PERIODS[period] * 86_400_000).toISOString() : undefined;
  const tickets = useMyTickets({ state, q: debouncedQ, type, createdFrom }, sort);
  const rows = tickets.data?.pages.flatMap((p) => p.items) ?? [];
  const listState = listStateOf(tickets, rows.length, !!(debouncedQ || type || period));

  return (
    <Stack gap="sm">
      <Group gap="xs" wrap="wrap">
        <TextInput
          leftSection={<IconSearch size={16} />}
          placeholder={t('myTickets.search')}
          aria-label={t('myTickets.search')}
          value={q}
          onChange={(e) => set('q', e.currentTarget.value || null)}
          style={{ flex: 1, minWidth: 200 }}
        />
        <Select
          aria-label={t('myTickets.type')}
          placeholder={t('myTickets.anyType')}
          clearable
          w={170}
          value={type ?? null}
          onChange={(v) => set('type', v)}
          data={[
            { value: 'incident', label: t('myTickets.types.incident') },
            { value: 'request', label: t('myTickets.types.request') },
          ]}
        />
        <Select
          aria-label={t('myTickets.period')}
          placeholder={t('myTickets.anyPeriod')}
          clearable
          w={170}
          value={period}
          onChange={(v) => set('period', v)}
          data={Object.keys(PERIODS).map((d) => ({ value: d, label: t('myTickets.lastDays', { count: Number(d) }) }))}
        />
        <Select
          aria-label={t('myTickets.sort')}
          w={220}
          value={sort}
          onChange={(v) => set('sort', v === '-updated' ? null : v)}
          data={[
            { value: '-updated', label: t('myTickets.sorts.updated') },
            { value: '-created', label: t('myTickets.sorts.newest') },
            { value: 'created', label: t('myTickets.sorts.oldest') },
          ]}
        />
      </Group>

      {listState ? (
        <ListState
          state={listState}
          title={listState === 'empty' ? t(`myTickets.empty.${state}`) : undefined}
          error={tickets.error}
          onRetry={() => void tickets.refetch()}
          onResetFilters={() => {
            set('q', null);
            set('type', null);
            set('period', null);
          }}
        />
      ) : (
        <>
          <Table.ScrollContainer minWidth={720} className="ep-card ep-table">
            <Table highlightOnHover verticalSpacing="sm" horizontalSpacing="md">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t('myTickets.cols.number')}</Table.Th>
                  <Table.Th>{t('myTickets.cols.title')}</Table.Th>
                  <Table.Th>{t('myTickets.cols.status')}</Table.Th>
                  <Table.Th>{t('myTickets.cols.assignee')}</Table.Th>
                  <Table.Th>{t('myTickets.cols.due')}</Table.Th>
                  <Table.Th>{t('myTickets.cols.updated')}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {rows.map((r) => (
                  <Table.Tr key={r.id} onClick={() => navigate(`/portal/tickets/${r.number}`)}>
                    <Table.Td w={130}>
                      <Anchor component={Link} to={`/portal/tickets/${r.number}`} onClick={(e) => e.stopPropagation()} ff="monospace" size="sm">
                        {r.number}
                      </Anchor>
                    </Table.Td>
                    <Table.Td>
                      <Text size="sm" fw={500} lineClamp={1}>
                        {r.title}
                      </Text>
                    </Table.Td>
                    <Table.Td>
                      <TicketStatus status={r.status} />
                    </Table.Td>
                    <Table.Td>
                      <Assignee row={r} />
                    </Table.Td>
                    <Table.Td>
                      <Text size="sm">{r.dueAt ? shortDateTime(r.dueAt, i18n.language) : '—'}</Text>
                    </Table.Td>
                    <Table.Td>
                      <Text size="sm" c="dimmed">
                        {shortDateTime(r.updatedAt, i18n.language)}
                      </Text>
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Table.ScrollContainer>
          {tickets.hasNextPage && (
            <Button variant="subtle" mx="auto" onClick={() => void tickets.fetchNextPage()} loading={tickets.isFetchingNextPage}>
              {t('actions.showMore')}
            </Button>
          )}
        </>
      )}
    </Stack>
  );
}

function Assignee({ row }: { row: Ticket }) {
  const { t } = useTranslation();
  const name = usePersonName(row.assigneeId);
  return <Text size="sm">{row.assigneeId ? name : t('myTickets.notAssigned')}</Text>;
}
