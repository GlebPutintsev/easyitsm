import { Alert, Anchor, Button, Group, Modal, Paper, Skeleton, Stack, Switch, Tabs, Text, TextInput, Textarea, Title } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconArrowLeft, IconExternalLink, IconPlus } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams, useSearchParams } from 'react-router';
import { ApiError } from '@/shared/api/client';
import { usePersonName } from '@/shared/api/people';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { ListState } from '@/shared/ui/ListState';
import { PriorityBadge, StatusBadge } from '@/shared/ui/TicketBadges';
import { problemsApi, useProblem, useProblemMutation, type Problem, type ResolveResult } from './api';
import { AnalysisEditor } from './AnalysisEditor';
import { KnownErrorForm } from './KnownErrorForm';
import classes from './problems.module.css';
import { LinkedTicketRow } from './ui';
import { useMutation, useQueryClient } from '@tanstack/react-query';

/** Карточка проблемы (PRB-01…04): обзор, анализ причин, известная ошибка, инциденты, изменения. */
export function ProblemPage() {
  const { number = '' } = useParams();
  const { t } = useTranslation();
  const problem = useProblem(number);
  if (problem.isPending) {
    return (
      <Stack gap="md" aria-busy="true" aria-label={t('states.loading')}>
        <Skeleton height={20} width={140} />
        <Skeleton height={32} width="60%" />
        <Skeleton height={240} />
      </Stack>
    );
  }
  if (problem.error) {
    const notFound = problem.error instanceof ApiError && problem.error.status === 404;
    return (
      <ListState
        state={notFound ? 'empty' : 'error'}
        title={notFound ? t('problems.card.notFound', { number }) : undefined}
        error={problem.error}
        onRetry={() => void problem.refetch()}
        action={
          <Button component={Link} to="/agent/problems" variant="subtle">
            {t('problems.card.back')}
          </Button>
        }
      />
    );
  }
  return <ProblemView key={problem.data.id} problem={problem.data} />;
}

function ProblemView({ problem }: { problem: Problem }) {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const tab = params.get('tab') ?? 'overview';
  return (
    <Stack gap="md" maw={1100}>
      <Anchor component={Link} to="/agent/problems" size="sm" c="dimmed">
        <Group gap={4} wrap="nowrap">
          <IconArrowLeft size={14} />
          {t('problems.card.back')}
        </Group>
      </Anchor>
      <Group justify="space-between" align="flex-start" wrap="wrap" gap="sm">
        <Stack gap={4} style={{ flex: '1 1 420px', minWidth: 0 }}>
          <Group gap={8} wrap="wrap">
            <Text className={classes.mono} c="dimmed">
              {problem.number}
            </Text>
            <PriorityBadge priority={problem.priority} variant="full" />
            <StatusBadge category={problem.statusCategory} />
          </Group>
          <Title order={2} fz={{ base: 'lg', sm: 'xl' }} style={{ overflowWrap: 'anywhere' }}>
            {problem.title}
          </Title>
          <Text size="xs" c="dimmed">
            {t(`problems.source.${problem.source}`)}
          </Text>
        </Stack>
        <Button variant="default" component={Link} to={`/agent/tickets/${problem.number}`} leftSection={<IconExternalLink size={16} />}>
          {t('problems.card.openTicket')}
        </Button>
      </Group>
      {!problem.permissions.edit && (
        <Alert color="gray" variant="light">
          {t('problems.card.readOnly')}
        </Alert>
      )}
      <Tabs value={tab} onChange={(v) => setParams(v && v !== 'overview' ? { tab: v } : {}, { replace: true })} keepMounted={false}>
        <Tabs.List>
          <Tabs.Tab value="overview">{t('problems.card.tabs.overview')}</Tabs.Tab>
          <Tabs.Tab value="analysis">{t('problems.card.tabs.analysis')}</Tabs.Tab>
          <Tabs.Tab value="knownError">{t('problems.card.tabs.knownError')}</Tabs.Tab>
          <Tabs.Tab value="incidents">
            {t('problems.card.tabs.incidents')} · {problem.incidents.length}
          </Tabs.Tab>
          <Tabs.Tab value="changes">
            {t('problems.card.tabs.changes')} · {problem.changes.length}
          </Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="overview" pt="md">
          <Overview problem={problem} />
        </Tabs.Panel>
        <Tabs.Panel value="analysis" pt="md">
          <AnalysisEditor problem={problem} />
        </Tabs.Panel>
        <Tabs.Panel value="knownError" pt="md">
          <KnownErrorForm problem={problem} />
        </Tabs.Panel>
        <Tabs.Panel value="incidents" pt="md">
          <Incidents problem={problem} />
        </Tabs.Panel>
        <Tabs.Panel value="changes" pt="md">
          <Changes problem={problem} />
        </Tabs.Panel>
      </Tabs>
    </Stack>
  );
}

function Overview({ problem }: { problem: Problem }) {
  const { t } = useTranslation();
  const readOnly = !problem.permissions.edit;
  const [rootCause, setRootCause] = useState(problem.rootCause);
  const [workaround, setWorkaround] = useState(problem.workaround);
  const save = useProblemMutation((body: { rootCause?: string; workaround?: string; closeIncidents?: boolean }) => problemsApi.saveAnalysis(problem, body));
  const dirty = rootCause !== problem.rootCause || workaround !== problem.workaround;
  return (
    <Stack gap="md">
      <section className={classes.section}>
        <Stack gap="sm">
          <Textarea
            label={t('problems.card.rootCause')}
            description={t('problems.card.rootCauseHint')}
            value={rootCause}
            onChange={(e) => setRootCause(e.currentTarget.value)}
            readOnly={readOnly}
            autosize
            minRows={2}
            maxLength={20000}
          />
          <Textarea
            label={t('problems.card.workaround')}
            description={t('problems.card.workaroundHint')}
            value={workaround}
            onChange={(e) => setWorkaround(e.currentTarget.value)}
            readOnly={readOnly}
            autosize
            minRows={2}
            maxLength={20000}
          />
          <Switch
            label={t('problems.card.closeIncidents')}
            checked={problem.closeIncidents}
            disabled={readOnly || save.isPending}
            onChange={(e) => save.mutate({ closeIncidents: e.currentTarget.checked })}
          />
          <ErrorLine error={save.error} />
          {!readOnly && (
            <Group justify="flex-end">
              <Button
                disabled={!dirty}
                loading={save.isPending}
                onClick={() =>
                  save.mutate({ rootCause, workaround }, { onSuccess: () => notifications.show({ message: t('problems.card.saved'), color: 'green', autoClose: 2000 }) })
                }
              >
                {t('problems.card.save')}
              </Button>
            </Group>
          )}
        </Stack>
      </section>
      <RiskBlock problem={problem} />
    </Stack>
  );
}

function RiskBlock({ problem }: { problem: Problem }) {
  const { t, i18n } = useTranslation();
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');
  const accepted = usePersonName(problem.risk?.acceptedBy);
  const accept = useProblemMutation((r: string) => problemsApi.acceptRisk(problem, r, null));
  const date = (iso: string | null | undefined) => (iso ? new Date(iso).toLocaleDateString(i18n.language) : '—');
  return (
    <section className={classes.section}>
      <Stack gap="xs" align="flex-start">
        <div className={classes.label}>{t('problems.card.risk.title')}</div>
        {problem.risk ? (
          <>
            <Text size="sm" className={classes.preWrap}>
              {problem.risk.reason}
            </Text>
            <Text size="xs" c="dimmed">
              {t('problems.card.risk.acceptedBy', { name: accepted, date: date(problem.risk.acceptedAt), review: date(problem.risk.reviewAt) })}
            </Text>
          </>
        ) : (
          <Text size="sm" c="dimmed">
            {t('problems.card.risk.none')}
          </Text>
        )}
        {problem.permissions.publishKnownError && (
          <Button size="xs" variant="subtle" onClick={() => setOpen(true)}>
            {t('problems.card.risk.accept')}
          </Button>
        )}
      </Stack>
      <Modal opened={open} onClose={() => setOpen(false)} title={t('problems.card.risk.accept')}>
        <Stack>
          <Textarea label={t('problems.card.risk.reason')} value={reason} onChange={(e) => setReason(e.currentTarget.value)} autosize minRows={3} maxLength={2000} required />
          <ErrorLine error={accept.error} />
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setOpen(false)}>
              {t('actions.cancel')}
            </Button>
            <Button disabled={!reason.trim()} loading={accept.isPending} onClick={() => accept.mutate(reason.trim(), { onSuccess: () => setOpen(false) })}>
              {t('problems.card.risk.accept')}
            </Button>
          </Group>
        </Stack>
      </Modal>
    </section>
  );
}

function Incidents({ problem }: { problem: Problem }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [numbers, setNumbers] = useState('');
  const [comment, setComment] = useState('');
  const [result, setResult] = useState<ResolveResult | null>(null);
  const link = useProblemMutation(async (refs: string[]) => {
    // Номера → id через карточки заявок: сервер связывает по id.
    const ids = await Promise.all(refs.map((n) => problemsApi.ticketId(n)));
    return problemsApi.linkIncidents(problem, ids);
  });
  const resolve = useMutation({
    mutationFn: () => problemsApi.resolveIncidents(problem, comment.trim()),
    onSuccess: (r) => {
      setResult(r);
      void qc.invalidateQueries({ queryKey: ['problems', 'detail'] });
      void qc.invalidateQueries({ queryKey: ['tickets'] });
    },
  });
  const refs = numbers
    .split(/[\s,;]+/)
    .map((s) => s.trim().toUpperCase())
    .filter(Boolean);
  return (
    <Stack gap="md">
      <section className={classes.section}>
        {problem.incidents.length === 0 ? (
          <Text size="sm" c="dimmed">
            {t('problems.incidents.empty')}
          </Text>
        ) : (
          <Stack gap={10}>
            {problem.incidents.map((i) => (
              <LinkedTicketRow key={i.id} ticket={i} />
            ))}
          </Stack>
        )}
      </section>
      {problem.permissions.edit && (
        <Paper withBorder p="md" radius="lg">
          <Group align="flex-end" wrap="wrap">
            <TextInput
              style={{ flex: '1 1 260px' }}
              label={t('problems.incidents.addLabel')}
              placeholder={t('problems.incidents.addPlaceholder')}
              value={numbers}
              onChange={(e) => setNumbers(e.currentTarget.value)}
            />
            <Button
              variant="default"
              leftSection={<IconPlus size={16} />}
              disabled={refs.length === 0}
              loading={link.isPending}
              onClick={() => link.mutate(refs, { onSuccess: () => setNumbers('') })}
            >
              {t('problems.incidents.add')}
            </Button>
          </Group>
          <ErrorLine error={link.error} />
        </Paper>
      )}
      {problem.permissions.edit && (
        <Paper withBorder p="md" radius="lg">
          <Stack gap="sm">
            <Textarea
              label={t('problems.incidents.resolveComment')}
              value={comment}
              onChange={(e) => setComment(e.currentTarget.value)}
              autosize
              minRows={1}
              maxLength={2000}
              disabled={!problem.permissions.resolveIncidents}
            />
            <Group justify="space-between" wrap="wrap">
              <Text size="xs" c="dimmed">
                {problem.permissions.resolveIncidents ? '' : t('problems.incidents.resolveHint')}
              </Text>
              <Button color="green" disabled={!problem.permissions.resolveIncidents} loading={resolve.isPending} onClick={() => resolve.mutate()}>
                {t('problems.incidents.resolve')}
              </Button>
            </Group>
            <ErrorLine error={resolve.error} />
            {result && (
              <Stack gap={2}>
                <Text size="sm">{t('problems.incidents.resolved', { count: result.resolved.length })}</Text>
                {result.skipped.map((s) => (
                  <Text key={s.number} size="xs" c="dimmed">
                    {t('problems.incidents.skipped', { number: s.number, reason: t(`problems.incidents.skipReason.${s.reason}`, { defaultValue: s.reason }) })}
                  </Text>
                ))}
              </Stack>
            )}
          </Stack>
        </Paper>
      )}
    </Stack>
  );
}

function Changes({ problem }: { problem: Problem }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const create = useMutation({
    mutationFn: () => problemsApi.createChange(problem, title.trim(), description),
    onSuccess: (c) => {
      setOpen(false);
      notifications.show({ message: t('problems.changes.created', { number: c.number }), color: 'green' });
      void qc.invalidateQueries({ queryKey: ['problems', 'detail'] });
    },
  });
  return (
    <Stack gap="md">
      <section className={classes.section}>
        {problem.changes.length === 0 ? (
          <Text size="sm" c="dimmed">
            {t('problems.changes.empty')}
          </Text>
        ) : (
          <Stack gap={10}>
            {problem.changes.map((c) => (
              <LinkedTicketRow key={c.id} ticket={c} />
            ))}
          </Stack>
        )}
      </section>
      {problem.permissions.createChange && (
        <Group>
          <Button
            variant="default"
            leftSection={<IconPlus size={16} />}
            onClick={() => {
              setTitle(problem.title);
              setDescription(problem.rootCause);
              setOpen(true);
            }}
          >
            {t('problems.changes.create')}
          </Button>
        </Group>
      )}
      <Modal opened={open} onClose={() => setOpen(false)} title={t('problems.changes.create')} size="lg">
        <Stack>
          <TextInput label={t('problems.changes.name')} value={title} onChange={(e) => setTitle(e.currentTarget.value)} maxLength={255} required />
          <Textarea label={t('problems.changes.description')} value={description} onChange={(e) => setDescription(e.currentTarget.value)} autosize minRows={3} maxLength={20000} />
          <ErrorLine error={create.error} />
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setOpen(false)}>
              {t('actions.cancel')}
            </Button>
            <Button disabled={!title.trim()} loading={create.isPending} onClick={() => create.mutate()}>
              {t('problems.changes.create')}
            </Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  );
}
