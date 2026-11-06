import { Anchor, Button, Drawer, Group, Stack, Table, Text } from '@mantine/core';
import { useInfiniteQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { ListState } from '@/shared/ui/ListState';
import { PriorityBadge, StatusBadge, type StatusCategory } from '@/shared/ui/TicketBadges';
import { ApiError } from '@/shared/api/client';
import { reportsApi, type DrillParams, type ReportFilters } from './api';
import { ExportMenu } from './ExportMenu';

export interface DrillTarget {
  params: DrillParams;
  label: string;
}

/**
 * Заявки за точкой дашборда (10.15 «Провалиться»): в пределах прав, без конфиденциальных. Номер ведёт в
 * карточку заявки рабочего места — там права проверяет модуль заявок.
 */
export function DrillDrawer({
  target,
  filters,
  onClose,
}: {
  target: DrillTarget | null;
  filters: ReportFilters;
  onClose: () => void;
}) {
  const { t, i18n } = useTranslation();
  const lang = i18n.language;
  // Параметры строки уточняют фильтры дашборда: группа строки уже внутри фильтра группы.
  const merged = target ? { ...filters, ...target.params } : filters;
  const { metric, status, channel, date, age, score, ...rest } = merged as ReportFilters & DrillParams;
  const drill: DrillParams = Object.fromEntries(
    Object.entries({ metric, status, channel, date, age, score }).filter(([, v]) => v !== undefined),
  ) as DrillParams;
  const scoped = rest as ReportFilters;

  const query = useInfiniteQuery({
    queryKey: ['reports', 'tickets', scoped, drill, lang],
    queryFn: ({ pageParam, signal }) => reportsApi.tickets(scoped, drill, lang, pageParam, signal),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    enabled: target !== null,
  });
  const items = query.data?.pages.flatMap((p) => p.items) ?? [];
  const dt = new Intl.DateTimeFormat(lang, { dateStyle: 'short', timeStyle: 'short' });
  const forbidden = query.error instanceof ApiError && query.error.status === 403;

  return (
    <Drawer
      opened={target !== null}
      onClose={onClose}
      position="right"
      size="xl"
      title={
        <Text fw={600} component="span">
          {target?.label}
        </Text>
      }
    >
      <Stack gap="sm">
        <Group justify="space-between" wrap="nowrap" align="flex-start">
          <Text size="xs" c="var(--ei-text-3)">
            {t('reports.drill.scopeNote')}
          </Text>
          <ExportMenu
            size="xs"
            disabled={items.length === 0}
            onExport={(format, reason) => reportsApi.exportTickets(scoped, drill, lang, format, reason)}
          />
        </Group>
        {query.isPending ? (
          <ListState state="loading" rows={6} />
        ) : query.isError ? (
          <ListState state={forbidden ? 'forbidden' : 'error'} error={query.error} onRetry={() => query.refetch()} />
        ) : items.length === 0 ? (
          <ListState state="empty" title={t('reports.drill.empty')} />
        ) : (
          <Table.ScrollContainer minWidth={640}>
            <Table verticalSpacing={6} fz="sm" highlightOnHover>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t('reports.drill.number')}</Table.Th>
                  <Table.Th>{t('reports.drill.title')}</Table.Th>
                  <Table.Th>{t('reports.drill.status')}</Table.Th>
                  <Table.Th>{t('reports.drill.group')}</Table.Th>
                  <Table.Th>{t('reports.drill.created')}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {items.map((r) => (
                  <Table.Tr key={r.id}>
                    <Table.Td>
                      <Group gap={6} wrap="nowrap">
                        <PriorityBadge priority={r.priority} />
                        <Anchor component={Link} to={`/agent/tickets/${r.number}`} className="ei-mono" size="sm">
                          {r.number}
                        </Anchor>
                      </Group>
                    </Table.Td>
                    <Table.Td maw={260}>
                      <Text size="sm" truncate title={r.title}>
                        {r.title}
                      </Text>
                      {r.slaBreached && (
                        <Text size="xs" c="var(--ei-danger)">
                          {t('reports.drill.slaBreached')}
                        </Text>
                      )}
                    </Table.Td>
                    <Table.Td>
                      <StatusBadge category={r.statusCategory as StatusCategory} />
                    </Table.Td>
                    <Table.Td>
                      <Text size="sm" c="var(--ei-text-2)">
                        {r.groupName}
                      </Text>
                    </Table.Td>
                    <Table.Td>
                      <Text size="sm" c="var(--ei-text-2)" style={{ whiteSpace: 'nowrap' }}>
                        {dt.format(new Date(r.createdAt))}
                      </Text>
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Table.ScrollContainer>
        )}
        {query.hasNextPage && (
          <Button variant="default" onClick={() => query.fetchNextPage()} loading={query.isFetchingNextPage}>
            {t('reports.drill.more')}
          </Button>
        )}
      </Stack>
    </Drawer>
  );
}
