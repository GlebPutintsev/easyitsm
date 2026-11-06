import {
  Alert,
  Badge,
  Button,
  Card,
  Code,
  Drawer,
  Group,
  Modal,
  Select,
  Stack,
  Table,
  Text,
  TextInput,
  Textarea,
  Title,
} from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { notifications } from '@mantine/notifications';
import { IconDownload, IconPlugConnected, IconShieldCheck, IconShieldX } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import i18n from '@/shared/i18n';
import { hasRole, useMe } from '@/features/auth/api';
import { usePersonName } from '@/shared/api/people';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import {
  type AuditCategory,
  type AuditEvent,
  type AuditFilter,
  type AuditResult,
  type Verification,
  useAuditEvents,
  useExport,
  useSiemStatus,
  useSiemTest,
  useVerify,
} from './api';
import './i18n';
import classes from './audit.module.css';

const CATEGORIES: AuditCategory[] = ['auth', 'access', 'config', 'data', 'security', 'audit'];
const RESULTS: AuditResult[] = ['success', 'failure', 'denied'];

/** «Управление → Журнал аудита» (10.16): SEC и AUD. Фильтр, проверка целостности, выгрузка, состояние SIEM. */
export function AuditPage() {
  const { t } = useTranslation();
  const me = useMe();
  const isSec = hasRole(me.data, 'SEC');
  const [filter, setFilter] = useState<AuditFilter>({});
  const [q] = useDebouncedValue(filter.q ?? '', 300);
  const [action] = useDebouncedValue(filter.action ?? '', 300);
  const effective = { ...filter, q, action };
  const events = useAuditEvents(effective);
  const rows = events.data?.pages.flatMap((p) => p.items) ?? [];
  const filtered = Object.values(filter).some(Boolean);
  const state = listStateOf(events, rows.length, filtered);
  const [selected, setSelected] = useState<AuditEvent | null>(null);
  const [exporting, setExporting] = useState(false);
  const verify = useVerify();

  return (
    <Stack gap="md" maw={1280}>
      <Group justify="space-between" align="flex-start" wrap="wrap">
        <div>
          <Title order={1} size="h2">
            {t('audit.title')}
          </Title>
          <Text size="sm" c="var(--ei-text-3)" maw={640}>
            {t('audit.subtitle')}
          </Text>
        </div>
        <Group gap="xs">
          <Button variant="default" leftSection={<IconShieldCheck size={16} />} loading={verify.isPending} onClick={() => verify.mutate()}>
            {t('audit.verify')}
          </Button>
          <Button leftSection={<IconDownload size={16} />} onClick={() => setExporting(true)}>
            {t('audit.export')}
          </Button>
        </Group>
      </Group>

      <ErrorLine error={verify.error} />
      {verify.data && <VerificationResult result={verify.data} />}
      {isSec && <SiemCard />}

      <Group gap="xs" align="flex-end" role="search" aria-label={t('audit.filters.label')} wrap="wrap">
        <TextInput
          type="date"
          label={t('audit.filters.from')}
          value={filter.from ?? ''}
          onChange={(e) => setFilter({ ...filter, from: e.currentTarget.value || undefined })}
          w={150}
        />
        <TextInput
          type="date"
          label={t('audit.filters.to')}
          value={filter.to ?? ''}
          onChange={(e) => setFilter({ ...filter, to: e.currentTarget.value || undefined })}
          w={150}
        />
        <Select
          label={t('audit.filters.category')}
          placeholder={t('audit.filters.allCategories')}
          clearable
          value={filter.category ?? null}
          onChange={(v) => setFilter({ ...filter, category: (v as AuditCategory) ?? undefined })}
          data={CATEGORIES.map((c) => ({ value: c, label: t(`audit.category.${c}`) }))}
          w={170}
        />
        <Select
          label={t('audit.filters.result')}
          placeholder={t('audit.filters.allResults')}
          clearable
          value={filter.result ?? null}
          onChange={(v) => setFilter({ ...filter, result: (v as AuditResult) ?? undefined })}
          data={RESULTS.map((r) => ({ value: r, label: t(`audit.result.${r}`) }))}
          w={170}
        />
        <TextInput
          label={t('audit.filters.action')}
          placeholder={t('audit.filters.actionPlaceholder')}
          value={filter.action ?? ''}
          onChange={(e) => setFilter({ ...filter, action: e.currentTarget.value || undefined })}
          w={190}
        />
        <TextInput
          label={t('audit.filters.search')}
          placeholder={t('audit.filters.searchPlaceholder')}
          value={filter.q ?? ''}
          onChange={(e) => setFilter({ ...filter, q: e.currentTarget.value || undefined })}
          style={{ flex: 1, minWidth: 200 }}
        />
      </Group>

      {state ? (
        <ListState state={state} error={events.error} title={state === 'empty' ? t('audit.empty') : undefined} onRetry={() => events.refetch()} onResetFilters={() => setFilter({})} />
      ) : (
        <Card withBorder padding={0} radius="lg" className={classes.tableCard}>
          <Table.ScrollContainer minWidth={860}>
            <Table highlightOnHover verticalSpacing={10} className={classes.table}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t('audit.columns.at')}</Table.Th>
                  <Table.Th>{t('audit.columns.category')}</Table.Th>
                  <Table.Th>{t('audit.columns.action')}</Table.Th>
                  <Table.Th>{t('audit.columns.actor')}</Table.Th>
                  <Table.Th>{t('audit.columns.object')}</Table.Th>
                  <Table.Th>{t('audit.columns.result')}</Table.Th>
                  <Table.Th>{t('audit.columns.ip')}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {rows.map((e) => (
                  <Table.Tr
                    key={e.id}
                    className={classes.row}
                    tabIndex={0}
                    onClick={() => setSelected(e)}
                    onKeyDown={(k) => (k.key === 'Enter' ? setSelected(e) : undefined)}
                    aria-label={t('audit.details.title', { seq: e.seq })}
                  >
                    <Table.Td className="ei-mono">{formatTime(e.at)}</Table.Td>
                    <Table.Td>{t(`audit.category.${e.category}`)}</Table.Td>
                    <Table.Td>
                      <Text size="sm">{actionLabel(t, e.action)}</Text>
                    </Table.Td>
                    <Table.Td>
                      <Actor event={e} />
                    </Table.Td>
                    <Table.Td>
                      <Text size="sm" c="var(--ei-text-2)" className={classes.object}>
                        {objectLabel(e)}
                      </Text>
                    </Table.Td>
                    <Table.Td>
                      <ResultBadge result={e.result} />
                    </Table.Td>
                    <Table.Td className="ei-mono">{e.ip ?? '—'}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Table.ScrollContainer>
        </Card>
      )}
      {events.hasNextPage && (
        <Button variant="subtle" onClick={() => events.fetchNextPage()} loading={events.isFetchingNextPage} w="fit-content">
          {t('audit.showMore')}
        </Button>
      )}

      <EventDrawer event={selected} onClose={() => setSelected(null)} />
      <ExportModal opened={exporting} filter={effective} onClose={() => setExporting(false)} />
    </Stack>
  );
}

/** Коды действий содержат точки, поэтому берём словарь целиком, а неизвестный код показываем как есть. */
function actionLabel(t: TFunction, action: string) {
  const labels = t('audit.actions', { returnObjects: true }) as Record<string, string>;
  return (typeof labels === 'object' && labels[action]) || action;
}

function objectLabel(e: AuditEvent) {
  if (!e.objectType && !e.objectId) return '—';
  return [e.objectType, e.objectId].filter(Boolean).join(' · ');
}

function formatTime(iso: string) {
  return new Date(iso).toLocaleString(i18n.language, {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

function Actor({ event }: { event: AuditEvent }) {
  const { t } = useTranslation();
  const name = usePersonName(event.actorId);
  if (!event.actorId) return <Text size="sm">{event.actorLogin ?? t('audit.system')}</Text>;
  return (
    <Stack gap={0}>
      <Text size="sm">{name}</Text>
      {event.actorLogin && (
        <Text size="xs" c="var(--ei-text-3)">
          {event.actorLogin}
        </Text>
      )}
    </Stack>
  );
}

function ResultBadge({ result }: { result: AuditResult }) {
  const { t } = useTranslation();
  return (
    <Badge variant="light" radius={6} className={classes[`result_${result}`]}>
      {t(`audit.result.${result}`)}
    </Badge>
  );
}

function VerificationResult({ result }: { result: Verification }) {
  const { t } = useTranslation();
  if (result.valid) {
    return (
      <Alert icon={<IconShieldCheck />} className={classes.ok} role="status">
        {t('audit.verification.ok', { count: result.checked, head: result.headSeq })}
      </Alert>
    );
  }
  return (
    <Alert icon={<IconShieldX />} className={classes.broken} role="alert">
      <Text size="sm" fw={600}>
        {t('audit.verification.broken', {
          seq: result.brokenAtSeq,
          problem: t(`audit.verification.problem.${result.problem}`),
        })}
      </Text>
      <Text size="sm">{t('audit.verification.hint')}</Text>
    </Alert>
  );
}

function EventDrawer({ event, onClose }: { event: AuditEvent | null; onClose: () => void }) {
  const { t } = useTranslation();
  const details = event ? Object.entries(event.details) : [];
  return (
    <Drawer opened={!!event} onClose={onClose} position="right" size={440} title={event ? t('audit.details.title', { seq: event.seq }) : ''}>
      {event && (
        <Stack gap="sm">
          <Field label={t('audit.details.when')}>
            <span className="ei-mono">{formatTime(event.at)}</span>
          </Field>
          <Field label={t('audit.details.what')}>
            {actionLabel(t, event.action)} <Code>{event.action}</Code>
          </Field>
          <Field label={t('audit.details.result')}>
            <ResultBadge result={event.result} />
          </Field>
          <Field label={t('audit.details.who')}>
            <Actor event={event} />
          </Field>
          <Field label={t('audit.details.object')}>{objectLabel(event)}</Field>
          <Field label={t('audit.details.where')}>
            <span className="ei-mono">{event.ip ?? '—'}</span>
          </Field>
          <Field label={t('audit.details.browser')}>{event.userAgent ?? '—'}</Field>
          <Field label={t('audit.details.trace')}>
            <span className="ei-mono">{event.traceId ?? '—'}</span>
          </Field>
          <Field label={t('audit.details.data')}>
            {details.length === 0 ? (
              t('audit.details.noData')
            ) : (
              <Table withRowBorders={false} verticalSpacing={2} fz="sm">
                <Table.Tbody>
                  {details.map(([k, v]) => (
                    <Table.Tr key={k}>
                      <Table.Td c="var(--ei-text-3)" w="40%">
                        {k}
                      </Table.Td>
                      <Table.Td className={classes.value}>{typeof v === 'object' ? JSON.stringify(v) : String(v)}</Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            )}
          </Field>
          <Field label={t('audit.details.hash')}>
            <span className={`ei-mono ${classes.hash}`}>{event.hash}</span>
          </Field>
          <Field label={t('audit.details.prevHash')}>
            <span className={`ei-mono ${classes.hash}`}>{event.prevHash}</span>
          </Field>
        </Stack>
      )}
    </Drawer>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <Text size="xs" fw={600} c="var(--ei-text-3)">
        {label}
      </Text>
      <Text size="sm" component="div">
        {children}
      </Text>
    </div>
  );
}

function ExportModal({ opened, filter, onClose }: { opened: boolean; filter: AuditFilter; onClose: () => void }) {
  const { t } = useTranslation();
  const [reason, setReason] = useState('');
  const exp = useExport();
  const valid = reason.trim().length >= 5 && reason.trim().length <= 500;
  return (
    <Modal opened={opened} onClose={onClose} title={t('audit.exportDialog.title')}>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (!valid) return;
          exp.mutate(
            { reason: reason.trim(), filter },
            {
              onSuccess: () => {
                setReason('');
                onClose();
              },
            },
          );
        }}
      >
        <Stack gap="sm">
          <Text size="sm" c="var(--ei-text-2)">
            {t('audit.exportDialog.text')}
          </Text>
          <Textarea
            label={t('audit.exportDialog.reason')}
            placeholder={t('audit.exportDialog.reasonPlaceholder')}
            description={t('audit.exportDialog.reasonHint')}
            value={reason}
            onChange={(e) => setReason(e.currentTarget.value)}
            maxLength={500}
            autosize
            minRows={2}
            required
            data-autofocus
          />
          <ErrorLine error={exp.error} />
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose}>
              {t('actions.cancel')}
            </Button>
            <Button type="submit" disabled={!valid} loading={exp.isPending} leftSection={<IconDownload size={16} />}>
              {t('audit.exportDialog.submit')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}

function SiemCard() {
  const { t } = useTranslation();
  const status = useSiemStatus(true);
  const test = useSiemTest();
  const s = status.data;
  if (!s) return null;
  return (
    <Card withBorder radius="lg" padding="md">
      <Group justify="space-between" align="flex-start" wrap="wrap" gap="sm">
        <Stack gap={4}>
          <Text fw={700}>{t('audit.siem.title')}</Text>
          {!s.enabled ? (
            <Text size="sm" c="var(--ei-text-3)">
              {t('audit.siem.off')}
            </Text>
          ) : (
            <>
              <Text size="sm">
                {t('audit.siem.target')}: <span className="ei-mono">{s.target}</span> · {s.transport} · {s.format}
              </Text>
              <Text size="sm" c="var(--ei-text-2)">
                {t('audit.siem.delivered', { seq: s.deliveredSeq })} · {t('audit.siem.lag')}: {s.lag}
                {s.deliveredAt && ` · ${t('audit.siem.deliveredAt')}: ${formatTime(s.deliveredAt)}`}
              </Text>
              {s.lastError && (
                <Text size="sm" className={classes.errorText}>
                  {t('audit.siem.lastError')}: {s.lastError}
                  {s.nextAttemptAt && ` · ${t('audit.siem.retry')}: ${formatTime(s.nextAttemptAt)}`}
                </Text>
              )}
            </>
          )}
        </Stack>
        {s.enabled && (
          <Button
            variant="default"
            size="sm"
            leftSection={<IconPlugConnected size={16} />}
            loading={test.isPending}
            onClick={() =>
              test.mutate(undefined, {
                onSuccess: (r) =>
                  notifications.show({
                    color: r.ok ? 'green' : 'red',
                    message: r.ok ? t('audit.siem.testOk') : t('audit.siem.testFailed', { error: r.error }),
                  }),
              })
            }
          >
            {t('audit.siem.test')}
          </Button>
        )}
      </Group>
      <ErrorLine error={test.error} />
    </Card>
  );
}
