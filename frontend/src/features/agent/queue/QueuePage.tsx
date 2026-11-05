import { ActionIcon, Button, Group, Menu, ScrollArea, Select, Stack, Text, Title, Tooltip } from '@mantine/core';
import { useHotkeys, useMediaQuery } from '@mantine/hooks';
import { notifications } from '@mantine/notifications';
import { useQueryClient } from '@tanstack/react-query';
import {
  IconArrowBigDownLines,
  IconBookmark,
  IconDots,
  IconDownload,
  IconPlayerTrackNext,
  IconRefresh,
  IconTrash,
} from '@tabler/icons-react';
import { useCallback, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import { ticketsApi } from '../api';
import { ticketKeys, useChangedCount, useDeleteView, useDirectory, useSaveView, useTicketList, useViews } from '../api/hooks';
import type { SortField, TicketQuery, TicketSummary } from '../api/model';
import { useNow } from '../ui/useNow';
import { showError } from '../ui/undoToast';
import { BulkBar } from './BulkBar';
import { useQueuePrefs } from './columns';
import { downloadCsv, ticketsToCsv } from './exportCsv';
import { FilterBar } from './FilterBar';
import { filterFromParams, paramsFromFilter, sameFilter, sortFromParams } from './filters';
import { ColumnsMenu, DensityToggle, SaveViewModal } from './QueueControls';
import { QueueCards, QueueTable } from './QueueTable';

const SORT_OPTIONS: { field: SortField; dir: 'asc' | 'desc' }[] = [
  { field: 'sla', dir: 'asc' },
  { field: 'priority', dir: 'asc' },
  { field: 'updatedAt', dir: 'desc' },
  { field: 'createdAt', dir: 'desc' },
  { field: 'createdAt', dir: 'asc' },
];

/** Навигационное состояние карточки: соседние заявки очереди для «следующая/предыдущая». */
export interface QueueNavState {
  queue: string[];
  backTo: string;
}

/**
 * Очередь агента (10.6): представления, поиск, фильтры, сортировка, колонки,
 * массовые действия, «Взять следующую» и плашка новых заявок без сдвига списка.
 */
export function QueuePage({ fixedView }: { fixedView?: string }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const location = useLocation();
  const qc = useQueryClient();
  const params = useParams();
  const [searchParams, setSearchParams] = useSearchParams();
  const viewId = fixedView ?? params.viewId ?? 'mine';
  const { views } = useViews();
  const directory = useDirectory();
  const view = views.find((v) => v.id === viewId);
  const filter = useMemo(() => filterFromParams(searchParams, view), [searchParams, view]);
  const sort = useMemo(() => sortFromParams(searchParams, view), [searchParams, view]);
  const query: TicketQuery = useMemo(() => ({ filter, sort, limit: 50 }), [filter, sort]);
  const list = useTicketList(query);
  const rows = useMemo(() => list.data?.pages.flatMap((p) => p.items) ?? [], [list.data]);
  const total = list.data?.pages[0]?.total ?? 0;
  const since = list.dataUpdatedAt ? new Date(list.dataUpdatedAt).toISOString() : undefined;
  const changed = useChangedCount(query, list.isFetching ? undefined : since);
  const now = useNow();
  const narrow = useMediaQuery('(max-width: 48em)');
  const prefs = useQueuePrefs();

  const [selected, setSelected] = useState<Map<string, TicketSummary>>(new Map());
  const [cursor, setCursor] = useState(-1);
  const lastToggled = useRef(-1);
  const [saveOpen, setSaveOpen] = useState(false);
  const [takingNext, setTakingNext] = useState(false);
  const saveView = useSaveView();
  const deleteView = useDeleteView();

  const modified = view ? !sameFilter(filter, view.filter) : false;
  const filtered = Boolean(filter.q) || modified;
  const state = listStateOf(list, rows.length, filtered);

  const setFilter = (next: typeof filter) => {
    setSearchParams(paramsFromFilter(next, searchParams), { replace: true });
    setCursor(-1);
  };
  const setSort = (field: SortField) => {
    const next = new URLSearchParams(searchParams);
    const dir = sort.field === field ? (sort.dir === 'asc' ? 'desc' : 'asc') : field === 'updatedAt' || field === 'createdAt' ? 'desc' : 'asc';
    next.set('sort', `${field}:${dir}`);
    setSearchParams(next, { replace: true });
  };

  const openTicket = useCallback(
    (row: TicketSummary) => {
      const state: QueueNavState = { queue: rows.map((r) => r.number), backTo: location.pathname + location.search };
      navigate(`/agent/tickets/${row.number}`, { state });
    },
    [rows, navigate, location],
  );

  const toggle = (id: string, index: number, shift: boolean) => {
    setSelected((prev) => {
      const next = new Map(prev);
      if (shift && lastToggled.current >= 0) {
        const [a, b] = [Math.min(lastToggled.current, index), Math.max(lastToggled.current, index)];
        for (const r of rows.slice(a, b + 1)) next.set(r.id, r);
      } else if (next.has(id)) next.delete(id);
      else {
        const row = rows.find((r) => r.id === id);
        if (row) next.set(id, row);
      }
      return next;
    });
    lastToggled.current = index;
  };
  const toggleAll = () =>
    setSelected((prev) => (rows.every((r) => prev.has(r.id)) ? new Map() : new Map(rows.map((r) => [r.id, r]))));

  const showNew = async () => {
    await qc.resetQueries({ queryKey: ticketKeys.list(query) });
    setCursor(-1);
  };

  const takeNext = async () => {
    setTakingNext(true);
    try {
      const ticket = await ticketsApi.takeNext();
      if (!ticket) {
        notifications.show({ message: t('workspace.queue.nothingToTake') });
        return;
      }
      void qc.invalidateQueries({ queryKey: ['tickets', 'list'] });
      navigate(`/agent/tickets/${ticket.number}`, { state: { queue: [], backTo: location.pathname + location.search } satisfies QueueNavState });
    } catch (e) {
      showError(e);
    } finally {
      setTakingNext(false);
    }
  };

  const exportRows = () => {
    const source = selected.size ? [...selected.values()] : rows;
    const headers = ['number', 'priority', 'title', 'status', 'requester', 'assignee', 'group', 'createdAt', 'updatedAt'].map((c) => t(`workspace.column.${c}`));
    downloadCsv(`tickets-${new Date().toISOString().slice(0, 10)}.csv`, ticketsToCsv(source, headers));
  };

  // Клавиатура очереди: J/K — по строкам, X — выбрать, Enter/O — открыть, Shift+T — взять следующую.
  useHotkeys([
    ['j', () => setCursor((c) => Math.min(rows.length - 1, c + 1))],
    ['k', () => setCursor((c) => Math.max(0, c - 1))],
    ['ArrowDown', () => setCursor((c) => Math.min(rows.length - 1, c + 1))],
    ['ArrowUp', () => setCursor((c) => Math.max(0, c - 1))],
    ['x', () => cursor >= 0 && rows[cursor] && toggle(rows[cursor].id, cursor, false)],
    ['Enter', () => cursor >= 0 && rows[cursor] && openTicket(rows[cursor])],
    ['o', () => cursor >= 0 && rows[cursor] && openTicket(rows[cursor])],
    ['f', () => document.getElementById('queue-search')?.focus()],
    ['shift+t', () => void takeNext()],
    ['Escape', () => setSelected(new Map())],
  ]);

  // Строка под курсором всегда видна.
  const scrollToCursor = useCallback((node: HTMLDivElement | null) => {
    node?.querySelector('[data-cursor="true"]')?.scrollIntoView({ block: 'nearest' });
  }, []);

  const viewName = (v: (typeof views)[number]) => (v.builtIn ? t(`workspace.views.builtin.${v.id}`) : v.name);
  const sortKey = `${sort.field}:${sort.dir}`;

  return (
    <Stack gap="sm" pb={selected.size ? 80 : 0}>
      <Group justify="space-between" wrap="wrap" gap="xs">
        <Group gap="xs" align="baseline">
          <Title order={2}>{view ? viewName(view) : t('workspace.queue.title')}</Title>
          {list.isSuccess && total >= 0 && (
            <Text c="dimmed" size="sm">
              {t('workspace.queue.total', { count: total })}
            </Text>
          )}
        </Group>
        <Tooltip label={t('workspace.queue.takeNextHint')} multiline w={260}>
          <Button leftSection={<IconPlayerTrackNext size={16} />} onClick={() => void takeNext()} loading={takingNext}>
            {t('workspace.queue.takeNext')}
          </Button>
        </Tooltip>
      </Group>

      {!fixedView && (
        <ScrollArea type="never" offsetScrollbars={false}>
          <Group gap={6} wrap="nowrap" role="tablist" aria-label={t('workspace.views.label')}>
            {views.map((v) => (
              <Button
                key={v.id}
                role="tab"
                aria-selected={v.id === viewId}
                size="compact-sm"
                radius="xl"
                variant={v.id === viewId ? 'light' : 'subtle'}
                color={v.id === viewId ? undefined : 'gray'}
                onClick={() => navigate(`/agent/queues/${v.id}`)}
                leftSection={v.builtIn ? undefined : <IconBookmark size={14} />}
                style={{ flexShrink: 0 }}
              >
                {viewName(v)}
              </Button>
            ))}
            {view && !view.builtIn && (
              <Menu position="bottom-end">
                <Menu.Target>
                  <ActionIcon variant="subtle" color="gray" aria-label={t('workspace.views.menu')}>
                    <IconDots size={16} />
                  </ActionIcon>
                </Menu.Target>
                <Menu.Dropdown>
                  <Menu.Item
                    color="red"
                    leftSection={<IconTrash size={14} />}
                    onClick={() =>
                      deleteView.mutate(view.id, {
                        onSuccess: () => navigate('/agent/queues/mine'),
                        onError: showError,
                      })
                    }
                  >
                    {t('workspace.views.delete')}
                  </Menu.Item>
                </Menu.Dropdown>
              </Menu>
            )}
          </Group>
        </ScrollArea>
      )}

      <Group justify="space-between" align="flex-start" wrap="wrap" gap="xs">
        <div style={{ flex: '1 1 420px', minWidth: 0 }}>
          <FilterBar filter={filter} directory={directory.data} onChange={setFilter} />
        </div>
        <Group gap={6} wrap="nowrap">
          {modified && (
            <Button variant="subtle" leftSection={<IconBookmark size={16} />} onClick={() => setSaveOpen(true)}>
              {t('workspace.views.save')}
            </Button>
          )}
          <Select
            aria-label={t('workspace.queue.sort')}
            w={190}
            value={sortKey}
            allowDeselect={false}
            onChange={(v) => {
              if (!v) return;
              const next = new URLSearchParams(searchParams);
              next.set('sort', v);
              setSearchParams(next, { replace: true });
            }}
            data={SORT_OPTIONS.map((o) => ({ value: `${o.field}:${o.dir}`, label: t(`workspace.sort.${o.field}_${o.dir}`) }))}
          />
          <ColumnsMenu columns={prefs.columns} onChange={prefs.setColumns} />
          <DensityToggle density={prefs.density} onChange={prefs.setDensity} />
          <Tooltip label={t('workspace.queue.export')}>
            <ActionIcon variant="default" size="lg" onClick={exportRows} aria-label={t('workspace.queue.export')} disabled={!rows.length}>
              <IconDownload size={18} />
            </ActionIcon>
          </Tooltip>
        </Group>
      </Group>

      {(changed.data ?? 0) > 0 && (
        <Button
          variant="light"
          fullWidth
          leftSection={<IconRefresh size={16} />}
          onClick={() => void showNew()}
          aria-live="polite"
          className="ew-new-banner"
        >
          {t('workspace.queue.showNew', { count: changed.data ?? 0 })}
        </Button>
      )}

      {state ? (
        <ListState
          state={state}
          error={list.error}
          onRetry={() => void list.refetch()}
          onResetFilters={() => setSearchParams(new URLSearchParams(), { replace: true })}
          title={state === 'empty' ? t(`workspace.queue.empty.${viewId}`, { defaultValue: t('workspace.queue.empty.default') }) : undefined}
          rows={8}
        />
      ) : (
        <div ref={scrollToCursor} key={cursor} style={{ opacity: list.isPlaceholderData ? 0.6 : 1, transition: 'opacity 120ms' }}>
          {narrow ? (
            <QueueCards rows={rows} now={now} selected={new Set(selected.keys())} onToggle={toggle} onOpen={openTicket} />
          ) : (
            <QueueTable
              rows={rows}
              columns={prefs.columns}
              density={prefs.density}
              now={now}
              selected={new Set(selected.keys())}
              cursor={cursor}
              sort={sort}
              onSort={setSort}
              onToggle={toggle}
              onToggleAll={toggleAll}
              onOpen={openTicket}
              onCursor={setCursor}
            />
          )}
          {list.hasNextPage && (
            <Group justify="center" mt="sm">
              <Button variant="subtle" leftSection={<IconArrowBigDownLines size={16} />} loading={list.isFetchingNextPage} onClick={() => void list.fetchNextPage()}>
                {t('workspace.queue.loadMore')}
              </Button>
            </Group>
          )}
          {!list.hasNextPage && rows.length > 20 && (
            <Text ta="center" c="dimmed" size="xs" mt="sm">
              {t('workspace.queue.end')}
            </Text>
          )}
        </div>
      )}

      <BulkBar selected={[...selected.values()]} onClear={() => setSelected(new Map())} directory={directory.data} />

      <SaveViewModal
        key={String(saveOpen)}
        opened={saveOpen}
        onClose={() => setSaveOpen(false)}
        saving={saveView.isPending}
        onSave={(name, visibility) =>
          saveView.mutate(
            { name, visibility, filter, sort },
            {
              onSuccess: (saved) => {
                setSaveOpen(false);
                notifications.show({ message: t('workspace.views.saved', { name }) });
                navigate(`/agent/queues/${saved.id}`);
              },
              onError: showError,
            },
          )
        }
      />
    </Stack>
  );
}
