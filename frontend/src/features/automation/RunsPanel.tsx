import { Anchor, Button, Group, Select, Stack, Table, Text, TextInput } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { Fragment, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import { type Run, useRules, useRuns } from './api';
import css from './automation.module.css';

/** Журнал запусков (AUT-04): каждый запуск с совпавшими условиями, в том числе остановленный защитой от петель. */
export function RunsPanel({ ruleId: initialRule }: { ruleId?: string }) {
  const { t } = useTranslation();
  const [ruleId, setRuleId] = useState<string | null>(initialRule ?? null);
  const [status, setStatus] = useState<string | null>(null);
  const [ticket, setTicket] = useState('');
  const [debouncedTicket] = useDebouncedValue(ticket.trim(), 300);
  const rules = useRules();
  const runs = useRuns({ ruleId: ruleId ?? undefined, status: status ?? undefined, ticket: debouncedTicket || undefined });
  const rows = runs.data?.pages.flatMap((p) => p.items) ?? [];
  const filtered = !!(ruleId || status || debouncedTicket);
  const state = listStateOf(runs, rows.length, filtered);
  const [open, setOpen] = useState<string | null>(null);

  return (
    <Stack gap="sm">
      <Group gap="xs" wrap="wrap">
        <Select
          aria-label={t('automation.columns.rule')}
          placeholder={t('automation.runs.allRules')}
          clearable
          searchable
          value={ruleId}
          onChange={setRuleId}
          data={(rules.data ?? []).map((r) => ({ value: r.id, label: r.name }))}
          w={260}
        />
        <Select
          aria-label={t('automation.columns.status')}
          placeholder={t('automation.runs.allStatuses')}
          clearable
          value={status}
          onChange={setStatus}
          data={(['success', 'failed', 'skipped'] as const).map((s) => ({ value: s, label: t(`automation.runs.status.${s}`) }))}
          w={180}
        />
        <TextInput
          aria-label={t('automation.runs.ticketFilter')}
          placeholder={t('automation.runs.ticketFilter')}
          value={ticket}
          onChange={(e) => setTicket(e.currentTarget.value)}
          w={180}
        />
      </Group>
      {state ? (
        <ListState
          state={state}
          title={state === 'empty' ? t('automation.runs.empty') : undefined}
          error={runs.error}
          onRetry={() => void runs.refetch()}
          onResetFilters={() => {
            setRuleId(null);
            setStatus(null);
            setTicket('');
          }}
        />
      ) : (
        <Table verticalSpacing="xs" highlightOnHover>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t('automation.columns.time')}</Table.Th>
              <Table.Th>{t('automation.columns.rule')}</Table.Th>
              <Table.Th>{t('automation.columns.ticket')}</Table.Th>
              <Table.Th>{t('automation.columns.status')}</Table.Th>
              <Table.Th>{t('automation.columns.depth')}</Table.Th>
              <Table.Th>{t('automation.columns.duration')}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {rows.map((r) => (
              <Fragment key={r.id}>
                <Table.Tr style={{ cursor: 'pointer' }} onClick={() => setOpen(open === r.id ? null : r.id)} aria-expanded={open === r.id}>
                  <Table.Td>
                    <Text size="sm">{new Date(r.startedAt).toLocaleString()}</Text>
                  </Table.Td>
                  <Table.Td>
                    {r.ruleId ? (
                      <Anchor component={Link} to={`/admin/automation/rules/${r.ruleId}`} size="sm" onClick={(e) => e.stopPropagation()}>
                        {r.ruleName}
                      </Anchor>
                    ) : (
                      <Text size="sm">{r.ruleName}</Text>
                    )}
                    <Text size="xs" c="dimmed">
                      {t(`automation.triggers.${r.trigger}`)}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    {r.ticketNumber ? (
                      <Anchor component={Link} to={`/agent/tickets/${r.ticketNumber}`} size="sm" className={css.mono} onClick={(e) => e.stopPropagation()}>
                        {r.ticketNumber}
                      </Anchor>
                    ) : (
                      '—'
                    )}
                  </Table.Td>
                  <Table.Td>
                    <RunStatus run={r} />
                  </Table.Td>
                  <Table.Td>{r.depth}</Table.Td>
                  <Table.Td>
                    <Text size="sm" c="dimmed">
                      {t('automation.runs.ms', { n: r.durationMs })}
                    </Text>
                  </Table.Td>
                </Table.Tr>
                {open === r.id && (
                  <Table.Tr>
                    <Table.Td colSpan={6}>
                      <RunDetails run={r} names={new Map((rules.data ?? []).map((x) => [x.id, x.name]))} />
                    </Table.Td>
                  </Table.Tr>
                )}
              </Fragment>
            ))}
          </Table.Tbody>
        </Table>
      )}
      {runs.hasNextPage && (
        <Button variant="subtle" mx="auto" onClick={() => void runs.fetchNextPage()} loading={runs.isFetchingNextPage}>
          {t('actions.showMore')}
        </Button>
      )}
    </Stack>
  );
}

export function RunStatus({ run }: { run: Run }) {
  const { t } = useTranslation();
  return (
    <Stack gap={2} align="flex-start">
      <span className={`${css.pill} ${css[run.status]}`}>{t(`automation.runs.status.${run.status}`)}</span>
      {run.skipReason && (
        <Text size="xs" c="dimmed">
          {t(`automation.runs.skip.${run.skipReason}`)}
        </Text>
      )}
    </Stack>
  );
}

function RunDetails({ run, names }: { run: Run; names: Map<string, string> }) {
  const { t } = useTranslation();
  return (
    <Stack gap={4} py={4}>
      {run.error && (
        <Text size="sm" c="var(--ei-danger)">
          {run.error}
          {run.errorCode && <span className={css.mono}> · {run.errorCode}</span>}
        </Text>
      )}
      {run.results.map((a, i) => (
        <Text size="sm" key={i}>
          {i + 1}. {t(`automation.actionTypes.${a.type}`)} — {t(`automation.runs.outcome.${a.outcome}`)}
        </Text>
      ))}
      {run.chain.length > 0 && (
        <Text size="xs" c="dimmed">
          {t('automation.runs.chain')}: {[...run.chain.map((id) => names.get(id) ?? t('automation.runs.deletedRule')), run.ruleName].join(' → ')}
        </Text>
      )}
    </Stack>
  );
}
