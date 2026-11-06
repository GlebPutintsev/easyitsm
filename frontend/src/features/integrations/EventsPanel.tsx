import { Anchor, Badge, Button, Group, Select, Stack, Table, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import { type Outcome, OUTCOMES, useEvents, useSources } from './api';

const COLORS: Record<Outcome, string> = {
  created: 'blue',
  updated: 'gray',
  escalated: 'orange',
  reopened: 'orange',
  resolved: 'green',
  ignored: 'gray',
  error: 'red',
};

/** Журнал событий мониторинга: что пришло и что сделано с инцидентом. */
export function EventsPanel({ sourceId }: { sourceId?: string }) {
  const { t, i18n } = useTranslation();
  const [params, setParams] = useSearchParams();
  const outcome = params.get('outcome') ?? undefined;
  const sources = useSources();
  const events = useEvents(sourceId, outcome);
  const rows = events.data?.pages.flatMap((p) => p.items) ?? [];
  const state = listStateOf(events, rows.length);
  const names = new Map((sources.data ?? []).map((s) => [s.source.id, s.source.name]));

  const setFilter = (key: string, value: string | null) => {
    const next = new URLSearchParams(params);
    next.set('tab', 'events');
    if (value) next.set(key, value);
    else next.delete(key);
    setParams(next, { replace: true });
  };

  return (
    <Stack gap="sm">
      <Group gap="sm" wrap="wrap">
        <Select
          label={t('integrations.events.source')}
          placeholder={t('integrations.events.allSources')}
          data={(sources.data ?? []).map((s) => ({ value: s.source.id, label: s.source.name }))}
          value={sourceId ?? null}
          clearable
          w={260}
          onChange={(v) => setFilter('source', v)}
        />
        <Select
          label={t('integrations.events.outcome')}
          placeholder={t('integrations.events.allOutcomes')}
          data={OUTCOMES.map((o) => ({ value: o, label: t(`integrations.outcomes.${o}`) }))}
          value={outcome ?? null}
          clearable
          w={220}
          onChange={(v) => setFilter('outcome', v)}
        />
      </Group>
      {state ? (
        <ListState
          state={state}
          title={state === 'empty' ? t('integrations.events.empty') : undefined}
          error={events.error}
          onRetry={() => void events.refetch()}
        />
      ) : (
        <Table.ScrollContainer minWidth={760}>
          <Table verticalSpacing="xs">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('integrations.events.time')}</Table.Th>
                <Table.Th>{t('integrations.events.source')}</Table.Th>
                <Table.Th>{t('integrations.events.alert')}</Table.Th>
                <Table.Th>{t('integrations.events.outcome')}</Table.Th>
                <Table.Th>{t('integrations.events.ticket')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((e) => (
                <Table.Tr key={e.id}>
                  <Table.Td>
                    <Text size="sm" c="dimmed" style={{ whiteSpace: 'nowrap' }}>
                      {new Date(e.receivedAt).toLocaleString(i18n.language, { dateStyle: 'short', timeStyle: 'medium' })}
                    </Text>
                  </Table.Td>
                  <Table.Td>{names.get(e.sourceId) ?? '—'}</Table.Td>
                  <Table.Td>
                    <Text size="sm" lineClamp={1}>
                      {e.title ?? '—'}
                    </Text>
                    {e.alertStatus && (
                      <Text size="xs" c="dimmed">
                        {t(`integrations.alertStatus.${e.alertStatus}`)}
                        {e.severity ? ` · ${t(`integrations.severity.${e.severity}`)}` : ''}
                      </Text>
                    )}
                  </Table.Td>
                  <Table.Td>
                    <Badge variant="light" color={COLORS[e.outcome]}>
                      {t(`integrations.outcomes.${e.outcome}`)}
                    </Badge>
                    {e.detail && (
                      <Text size="xs" c="dimmed">
                        {e.detail}
                      </Text>
                    )}
                  </Table.Td>
                  <Table.Td>
                    {e.ticketNumber ? (
                      <Anchor component={Link} to={`/agent/tickets/${e.ticketNumber}`}>
                        {e.ticketNumber}
                      </Anchor>
                    ) : (
                      '—'
                    )}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
      {events.hasNextPage && (
        <Group justify="center">
          <Button variant="default" loading={events.isFetchingNextPage} onClick={() => void events.fetchNextPage()}>
            {t('actions.showMore')}
          </Button>
        </Group>
      )}
    </Stack>
  );
}
