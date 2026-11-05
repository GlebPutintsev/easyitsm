import { Anchor, Group, Text } from '@mantine/core';
import { useLocalStorage } from '@mantine/hooks';
import type { ReactNode } from 'react';
import type { TFunction } from 'i18next';
import { Link } from 'react-router';
import type { SortField, TicketSummary } from '../api/model';
import { ConfidentialIcon, PriorityBadge, SlaBadge, StatusBadge, TypeIcon } from '../ui/badges';
import { formatRelative } from '../ui/format';

export type ColumnId =
  | 'priority'
  | 'number'
  | 'title'
  | 'requester'
  | 'status'
  | 'assignee'
  | 'group'
  | 'sla'
  | 'updatedAt'
  | 'createdAt'
  | 'service'
  | 'type';

export interface CellContext {
  now: number;
  locale: string;
  t: TFunction;
}

interface ColumnDef {
  id: ColumnId;
  width?: number;
  sort?: SortField;
  render: (row: TicketSummary, ctx: CellContext) => ReactNode;
}

export const COLUMNS: Record<ColumnId, ColumnDef> = {
  priority: { id: 'priority', width: 56, sort: 'priority', render: (r) => <PriorityBadge priority={r.priority} /> },
  number: {
    id: 'number',
    width: 118,
    sort: 'number',
    render: (r) => (
      <Group gap={6} wrap="nowrap">
        <TypeIcon type={r.type} size={14} />
        <Text size="sm" ff="monospace" c="dimmed" style={{ whiteSpace: 'nowrap' }}>
          {r.number}
        </Text>
      </Group>
    ),
  },
  title: {
    id: 'title',
    render: (r) => (
      <Group gap={6} wrap="nowrap" miw={0}>
        {r.awaitingAgent && <span className="ew-unread-dot" aria-hidden />}
        {r.confidential && <ConfidentialIcon />}
        <Anchor
          component={Link}
          to={`/agent/tickets/${r.number}`}
          c="var(--mantine-color-text)"
          size="sm"
          fw={r.awaitingAgent ? 600 : 500}
          lineClamp={1}
          // Ссылка нужна для Ctrl+клик и средней кнопки; обычный клик обрабатывает строка.
          onClick={(e) => e.stopPropagation()}
        >
          {r.title}
        </Anchor>
      </Group>
    ),
  },
  requester: { id: 'requester', width: 160, render: (r) => <Text size="sm" lineClamp={1}>{r.requester.name}</Text> },
  status: { id: 'status', width: 130, render: (r) => <StatusBadge status={r.status} /> },
  assignee: {
    id: 'assignee',
    width: 150,
    render: (r, { t }) =>
      r.assignee ? (
        <Text size="sm" lineClamp={1}>{r.assignee.name}</Text>
      ) : (
        <Text size="sm" c="dimmed" fs="italic" style={{ whiteSpace: 'nowrap' }}>{t('workspace.unassigned')}</Text>
      ),
  },
  group: { id: 'group', width: 180, render: (r) => <Text size="sm" lineClamp={1}>{r.group?.name ?? '—'}</Text> },
  sla: { id: 'sla', width: 130, sort: 'sla', render: (r, { now }) => <SlaBadge timer={r.sla} now={now} startAt={r.createdAt} /> },
  updatedAt: {
    id: 'updatedAt',
    width: 130,
    sort: 'updatedAt',
    render: (r, { now, locale, t }) => <Text size="sm" c="dimmed" style={{ whiteSpace: 'nowrap' }}>{formatRelative(r.updatedAt, now, locale, t)}</Text>,
  },
  createdAt: {
    id: 'createdAt',
    width: 120,
    sort: 'createdAt',
    render: (r, { now, locale, t }) => <Text size="sm" c="dimmed" style={{ whiteSpace: 'nowrap' }}>{formatRelative(r.createdAt, now, locale, t)}</Text>,
  },
  service: { id: 'service', width: 160, render: (r) => <Text size="sm" lineClamp={1}>{r.service?.name ?? '—'}</Text> },
  type: { id: 'type', width: 110, render: (r, { t }) => <Text size="sm">{t(`workspace.type.${r.type}`)}</Text> },
};

export const ALL_COLUMNS = Object.keys(COLUMNS) as ColumnId[];
export const DEFAULT_COLUMNS: ColumnId[] = ['priority', 'number', 'title', 'requester', 'status', 'assignee', 'sla', 'updatedAt'];

export type Density = 'comfortable' | 'compact';

/** Колонки и плотность сохраняются у пользователя (10.6). Пока в браузере, позже в профиле на сервере. */
export function useQueuePrefs() {
  const [columns, setColumns] = useLocalStorage<ColumnId[]>({
    key: 'easyitsm.queue.columns',
    defaultValue: DEFAULT_COLUMNS,
    getInitialValueInEffect: false,
  });
  const [density, setDensity] = useLocalStorage<Density>({
    key: 'easyitsm.queue.density',
    defaultValue: 'comfortable',
    getInitialValueInEffect: false,
  });
  const valid = columns.filter((c) => c in COLUMNS);
  return { columns: valid.length ? valid : DEFAULT_COLUMNS, setColumns, density, setDensity };
}
