import { Card, Checkbox, Group, Stack, Table, Text, UnstyledButton } from '@mantine/core';
import { IconArrowDown, IconArrowUp } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import type { SortDir, SortField, TicketSummary } from '../api/model';
import { PriorityBadge, SlaBadge, StatusBadge, TypeIcon } from '../ui/badges';
import { COLUMNS, type ColumnId, type Density } from './columns';

interface Props {
  rows: TicketSummary[];
  columns: ColumnId[];
  density: Density;
  now: number;
  selected: Set<string>;
  cursor: number;
  sort: { field: SortField; dir: SortDir };
  onSort: (field: SortField) => void;
  onToggle: (id: string, index: number, shift: boolean) => void;
  onToggleAll: () => void;
  onOpen: (row: TicketSummary) => void;
  onCursor: (index: number) => void;
}

/**
 * Таблица очереди (10.6). Клик по строке открывает карточку, флажок — выбор,
 * Shift+клик выбирает диапазон. Клавиатура обрабатывается на странице: J/K, X, Enter.
 */
export function QueueTable({ rows, columns, density, now, selected, cursor, sort, onSort, onToggle, onToggleAll, onOpen, onCursor }: Props) {
  const { t, i18n } = useTranslation();
  const all = rows.length > 0 && rows.every((r) => selected.has(r.id));
  const some = !all && rows.some((r) => selected.has(r.id));
  const ctx = { now, locale: i18n.language, t };

  return (
    <Table.ScrollContainer minWidth={36 + columns.reduce((sum, id) => sum + (COLUMNS[id].width ?? 260), 0)} type="native">
      <Table
        highlightOnHover
        stickyHeader
        verticalSpacing={density === 'compact' ? 4 : 'xs'}
        className="ew-queue"
        aria-label={t('workspace.queue.tableLabel')}
      >
        <Table.Thead>
          <Table.Tr>
            <Table.Th w={36}>
              <Checkbox
                size="xs"
                checked={all}
                indeterminate={some}
                onChange={onToggleAll}
                aria-label={t('workspace.queue.selectAll')}
              />
            </Table.Th>
            {columns.map((id) => {
              const col = COLUMNS[id];
              const active = col.sort && sort.field === col.sort;
              return (
                <Table.Th key={id} style={{ width: col.width, minWidth: col.width ?? 260 }} aria-sort={active ? (sort.dir === 'asc' ? 'ascending' : 'descending') : undefined}>
                  {col.sort ? (
                    <UnstyledButton onClick={() => col.sort && onSort(col.sort)} className="ew-th-sort">
                      <Group gap={4} wrap="nowrap">
                        <Text size="xs" fw={600} c={active ? undefined : 'dimmed'} tt="uppercase">
                          {t(`workspace.column.${id}`)}
                        </Text>
                        {active && (sort.dir === 'asc' ? <IconArrowUp size={12} /> : <IconArrowDown size={12} />)}
                      </Group>
                    </UnstyledButton>
                  ) : (
                    <Text size="xs" fw={600} c="dimmed" tt="uppercase">
                      {t(`workspace.column.${id}`)}
                    </Text>
                  )}
                </Table.Th>
              );
            })}
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {rows.map((row, index) => (
            <Table.Tr
              key={row.id}
              data-row-index={index}
              data-cursor={cursor === index || undefined}
              data-selected={selected.has(row.id) || undefined}
              data-unread={row.awaitingAgent || undefined}
              onClick={(e) => {
                onCursor(index);
                if (e.shiftKey || e.metaKey || e.ctrlKey) onToggle(row.id, index, e.shiftKey);
                else onOpen(row);
              }}
              style={{ cursor: 'pointer' }}
            >
              <Table.Td onClick={(e) => e.stopPropagation()}>
                <Checkbox
                  size="xs"
                  checked={selected.has(row.id)}
                  onChange={(e) => onToggle(row.id, index, (e.nativeEvent as MouseEvent).shiftKey)}
                  aria-label={t('workspace.queue.selectRow', { number: row.number })}
                />
              </Table.Td>
              {columns.map((id) => (
                <Table.Td key={id} style={id === 'title' ? { maxWidth: 0, width: '100%' } : { maxWidth: COLUMNS[id].width }}>
                  {COLUMNS[id].render(row, ctx)}
                </Table.Td>
              ))}
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </Table.ScrollContainer>
  );
}

/** Карточки вместо таблицы на узком экране (UI-08, от 360 px). */
export function QueueCards({ rows, now, selected, onToggle, onOpen }: Pick<Props, 'rows' | 'now' | 'selected' | 'onToggle' | 'onOpen'>) {
  const { t } = useTranslation();
  return (
    <Stack gap={8}>
      {rows.map((row, index) => (
        <Card
          key={row.id}
          withBorder
          padding="sm"
          onClick={() => onOpen(row)}
          style={{ cursor: 'pointer' }}
          data-selected={selected.has(row.id) || undefined}
          className="ew-queue-card"
        >
          <Group justify="space-between" wrap="nowrap" mb={4}>
            <Group gap={6} wrap="nowrap">
              <Checkbox
                size="xs"
                checked={selected.has(row.id)}
                onClick={(e) => e.stopPropagation()}
                onChange={() => onToggle(row.id, index, false)}
                aria-label={t('workspace.queue.selectRow', { number: row.number })}
              />
              <PriorityBadge priority={row.priority} />
              <TypeIcon type={row.type} size={14} />
              <Text size="xs" ff="monospace" c="dimmed">
                {row.number}
              </Text>
            </Group>
            <SlaBadge timer={row.sla} now={now} compact />
          </Group>
          <Text size="sm" fw={row.awaitingAgent ? 600 : 500} lineClamp={2}>
            {row.awaitingAgent && <span className="ew-unread-dot" aria-hidden />} {row.title}
          </Text>
          <Group justify="space-between" mt={6} wrap="nowrap">
            <Text size="xs" c="dimmed" lineClamp={1}>
              {row.requester.name} · {row.assignee?.name ?? t('workspace.unassigned')}
            </Text>
            <StatusBadge status={row.status} />
          </Group>
        </Card>
      ))}
    </Stack>
  );
}
