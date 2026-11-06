import { Anchor, Badge, Button, Group, Select, Stack, Table, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router';
import { hasRole, useMe } from '@/features/auth/api';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import { DELIVERY_STATUSES, type DeliveryStatus, useDeliveries, useRetryDelivery, useWebhooks } from './api';

const COLORS: Record<DeliveryStatus, string> = { pending: 'blue', delivered: 'green', dead: 'red' };

/** Журнал доставок вебхуков: попытки, ответ получателя и ручной повтор неудачных. */
export function DeliveriesPanel() {
  const { t, i18n } = useTranslation();
  const me = useMe();
  const canRetry = hasRole(me.data, 'ADM');
  const [params, setParams] = useSearchParams();
  const webhookId = params.get('webhook') ?? undefined;
  const status = params.get('status') ?? undefined;
  const hooks = useWebhooks();
  const deliveries = useDeliveries(webhookId, status);
  const retry = useRetryDelivery();
  const rows = deliveries.data?.pages.flatMap((p) => p.items) ?? [];
  const state = listStateOf(deliveries, rows.length);
  const names = new Map((hooks.data ?? []).map((h) => [h.id, h.name]));
  const time = (iso: string) => new Date(iso).toLocaleString(i18n.language, { dateStyle: 'short', timeStyle: 'medium' });

  const setFilter = (key: string, value: string | null) => {
    const next = new URLSearchParams(params);
    next.set('tab', 'deliveries');
    if (value) next.set(key, value);
    else next.delete(key);
    setParams(next, { replace: true });
  };

  return (
    <Stack gap="sm">
      <Group gap="sm" wrap="wrap">
        <Select
          label={t('integrations.deliveries.webhook')}
          placeholder={t('integrations.deliveries.allWebhooks')}
          data={(hooks.data ?? []).map((h) => ({ value: h.id, label: h.name }))}
          value={webhookId ?? null}
          clearable
          w={260}
          onChange={(v) => setFilter('webhook', v)}
        />
        <Select
          label={t('integrations.deliveries.status')}
          placeholder={t('integrations.deliveries.allStatuses')}
          data={DELIVERY_STATUSES.map((s) => ({ value: s, label: t(`integrations.deliveries.statuses.${s}`) }))}
          value={status ?? null}
          clearable
          w={220}
          onChange={(v) => setFilter('status', v)}
        />
      </Group>
      <ErrorLine error={retry.error} />
      {state ? (
        <ListState
          state={state}
          title={state === 'empty' ? t('integrations.deliveries.empty') : undefined}
          error={deliveries.error}
          onRetry={() => void deliveries.refetch()}
        />
      ) : (
        <Table.ScrollContainer minWidth={820}>
          <Table verticalSpacing="xs" data-testid="deliveries-table">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('integrations.events.time')}</Table.Th>
                <Table.Th>{t('integrations.deliveries.webhook')}</Table.Th>
                <Table.Th>{t('integrations.deliveries.event')}</Table.Th>
                <Table.Th>{t('integrations.deliveries.status')}</Table.Th>
                <Table.Th>{t('integrations.events.ticket')}</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((d) => (
                <Table.Tr key={d.id}>
                  <Table.Td>
                    <Text size="sm" c="dimmed" style={{ whiteSpace: 'nowrap' }}>
                      {time(d.createdAt)}
                    </Text>
                  </Table.Td>
                  <Table.Td>{names.get(d.webhookId) ?? '—'}</Table.Td>
                  <Table.Td>
                    <Text size="sm" ff="monospace">
                      {d.eventType}
                    </Text>
                    {d.source && (
                      <Text size="xs" c="dimmed">
                        {d.source}
                      </Text>
                    )}
                  </Table.Td>
                  <Table.Td>
                    <Badge variant="light" color={COLORS[d.status]}>
                      {t(`integrations.deliveries.statuses.${d.status}`)}
                    </Badge>
                    <Text size="xs" c="dimmed">
                      {t('integrations.deliveries.attempts', { count: d.attempts })}
                      {d.lastStatus ? ` · HTTP ${d.lastStatus}` : ''}
                      {d.status === 'pending' && d.nextAttemptAt ? ` · ${t('integrations.deliveries.next', { time: time(d.nextAttemptAt) })}` : ''}
                    </Text>
                    {d.lastError && d.status !== 'delivered' && (
                      <Text size="xs" c="red">
                        {d.lastError}
                      </Text>
                    )}
                  </Table.Td>
                  <Table.Td>
                    {d.ticketNumber ? (
                      <Anchor component={Link} to={`/agent/tickets/${d.ticketNumber}`}>
                        {d.ticketNumber}
                      </Anchor>
                    ) : (
                      '—'
                    )}
                  </Table.Td>
                  <Table.Td>
                    {canRetry && d.status === 'dead' && (
                      <Button size="xs" variant="default" loading={retry.isPending && retry.variables === d.id} onClick={() => retry.mutate(d.id)}>
                        {t('integrations.deliveries.retry')}
                      </Button>
                    )}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
      {deliveries.hasNextPage && (
        <Group justify="center">
          <Button variant="default" loading={deliveries.isFetchingNextPage} onClick={() => void deliveries.fetchNextPage()}>
            {t('actions.showMore')}
          </Button>
        </Group>
      )}
    </Stack>
  );
}
