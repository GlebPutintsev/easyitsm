import { Alert, Button, Group, Menu, Skeleton, Stack, Tabs, Text, Title } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconAlertTriangle, IconArrowLeft, IconChevronDown, IconDownload } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams, useSearchParams } from 'react-router';
import { ApiError } from '@/shared/api/client';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { ListState } from '@/shared/ui/ListState';
import { type Change, downloadGet, useChange, useChangeAction } from './api';
import { ApprovalsTab } from './ApprovalsTab';
import { ChangeNumber, RiskBadge, StateBadge, TypeBadge } from './ChangeBadges';
import classes from './changes.module.css';
import { CompleteDialog, ConflictAckDialog, conflictsOf, ReasonDialog, RescheduleDialog } from './dialogs';
import { pendingImplementSteps } from './model';
import { OverviewTab } from './OverviewTab';
import { PlanTab } from './PlanTab';
import { ReviewTab } from './ReviewTab';
import { RiskTab } from './RiskTab';

type Dialog = 'cancel' | 'return' | 'rollback' | 'reschedule' | 'complete' | 'start' | 'conflicts' | null;

/** Карточка изменения (10.11): шапка с действиями по статусу и вкладки формы ЗНИ. */
export function ChangePage() {
  const { id } = useParams();
  const { t } = useTranslation();
  const change = useChange(id);
  const [params, setParams] = useSearchParams();
  const tab = params.get('tab') ?? 'overview';

  if (change.isPending) {
    return (
      <Stack gap="sm" maw={1280}>
        <Skeleton height={28} width={320} />
        <Skeleton height={20} width={480} />
        <Skeleton height={240} />
      </Stack>
    );
  }
  if (change.error || !change.data) {
    const notFound = change.error instanceof ApiError && change.error.status === 404;
    return (
      <ListState
        state={change.error instanceof ApiError && change.error.status === 403 ? 'forbidden' : notFound ? 'empty' : 'error'}
        title={notFound ? t('changes.notFound') : undefined}
        error={change.error}
        onRetry={() => void change.refetch()}
      />
    );
  }
  const c = change.data;
  const showReview = ['review', 'closed'].includes(c.state) || !!c.outcome;
  return (
    <Stack gap="md" maw={1280}>
      <Button variant="subtle" leftSection={<IconArrowLeft size={16} />} component={Link} to="/agent/changes" w="fit-content" px={0}>
        {t('changes.title')}
      </Button>
      <Header change={c} />
      {c.missing.length > 0 && c.state === 'draft' && (
        <div className={classes.missing} role="status">
          {t('changes.missingTitle')}: {c.missing.map((m) => t(`changes.missing.${m}`, { defaultValue: m })).join(', ')}
        </div>
      )}
      {c.postApprovalOverdue && (
        <Alert color="red" icon={<IconAlertTriangle />}>
          {t('changes.postApprovalOverdue')}
        </Alert>
      )}
      <Tabs value={tab} onChange={(v) => setParams(v && v !== 'overview' ? { tab: v } : {}, { replace: true })} keepMounted={false}>
        <Tabs.List>
          <Tabs.Tab value="overview">{t('changes.tabs.overview')}</Tabs.Tab>
          <Tabs.Tab value="plan">
            {t('changes.tabs.plan')} · {c.steps.length}
          </Tabs.Tab>
          <Tabs.Tab value="risk">{t('changes.tabs.risk')}</Tabs.Tab>
          <Tabs.Tab value="approvals">{t('changes.tabs.approvalsCard')}</Tabs.Tab>
          {showReview && <Tabs.Tab value="review">{t('changes.tabs.review')}</Tabs.Tab>}
        </Tabs.List>
        <Tabs.Panel value="overview" pt="md">
          <OverviewTab change={c} />
        </Tabs.Panel>
        <Tabs.Panel value="plan" pt="md">
          <PlanTab change={c} />
        </Tabs.Panel>
        <Tabs.Panel value="risk" pt="md">
          <RiskTab change={c} />
        </Tabs.Panel>
        <Tabs.Panel value="approvals" pt="md">
          <ApprovalsTab change={c} />
        </Tabs.Panel>
        {showReview && (
          <Tabs.Panel value="review" pt="md">
            <ReviewTab change={c} />
          </Tabs.Panel>
        )}
      </Tabs>
    </Stack>
  );
}

function Header({ change: c }: { change: Change }) {
  const { t } = useTranslation();
  const action = useChangeAction(c);
  const [dialog, setDialog] = useState<Dialog>(null);
  const [lastPath, setLastPath] = useState('');
  const p = c.permissions;

  const run = (path: string, body?: unknown, done?: () => void) => {
    setLastPath(path);
    action.mutate(
      { path, body },
      {
        onSuccess: () => {
          setDialog(null);
          done?.();
        },
        onError: (e) => {
          if (conflictsOf(e) && path !== '/reschedule') setDialog('conflicts');
          if (e instanceof ApiError && e.code === 'outside_window') setDialog('start');
        },
      },
    );
  };
  const exportAs = (format: 'xlsx' | 'docx' | 'pdf') =>
    downloadGet(`/changes/${c.id}/plan-document?format=${format}`, `${c.number}.${format}`).catch((e: unknown) =>
      notifications.show({ color: 'red', message: e instanceof Error ? e.message : String(e) }),
    );

  const primary: { key: string; label: string; onClick: () => void; variant?: string; path?: string }[] = [];
  if (p.submit) primary.push({ key: 'submit', path: '/submit', label: t(`changes.actions.submit_${c.type}`), onClick: () => run('/submit') });
  if (p.sendToApproval) primary.push({ key: 'send', path: '/send-to-approval', label: t('changes.actions.sendToApproval'), onClick: () => run('/send-to-approval') });
  if (p.start) primary.push({ key: 'start', path: '/start', label: t('changes.actions.start'), onClick: () => run('/start') });
  if (c.state === 'implementation' && p.recordSteps) {
    primary.push({
      key: 'verify',
      path: '/verify',
      label: t('changes.actions.verify'),
      onClick: () =>
        pendingImplementSteps(c.steps) > 0
          ? notifications.show({ color: 'orange', message: t('changes.stepsPending') })
          : run('/verify'),
    });
  }
  if (p.complete) primary.push({ key: 'complete', label: t('changes.actions.complete'), onClick: () => setDialog('complete'), variant: c.state === 'implementation' ? 'default' : undefined });

  const secondary: { key: string; label: string; onClick: () => void; color?: string }[] = [];
  if (p.reschedule) secondary.push({ key: 'reschedule', label: t('changes.actions.reschedule'), onClick: () => setDialog('reschedule') });
  if (p.returnToDraft) secondary.push({ key: 'return', label: t('changes.actions.returnToDraft'), onClick: () => setDialog('return') });
  if (c.state === 'verification' && p.recordSteps) secondary.push({ key: 'reopen', label: t('changes.actions.reopen'), onClick: () => run('/reopen') });
  if (p.startRollback) secondary.push({ key: 'rollback', label: t('changes.actions.rollback'), onClick: () => setDialog('rollback'), color: 'red' });
  if (p.cancel) secondary.push({ key: 'cancel', label: t('changes.actions.cancel'), onClick: () => setDialog('cancel'), color: 'red' });

  const conflicts = conflictsOf(action.error) ?? [];
  return (
    <Stack gap={8}>
      <Group gap={8} wrap="wrap">
        <TypeBadge type={c.type} />
        <StateBadge state={c.state} />
        <RiskBadge level={c.risk.level} />
        <ChangeNumber number={c.number} />
        {c.pipeline && (
          <Text size="xs" c="dimmed">
            {t('changes.fromPipeline')}
          </Text>
        )}
      </Group>
      <Title order={1} fz={24}>
        {c.title}
      </Title>
      <Group gap="xs" wrap="wrap">
        {primary.map((a, i) => (
          <Button
            key={a.key}
            variant={a.variant ?? (i === 0 ? 'filled' : 'default')}
            loading={action.isPending && !!a.path && lastPath === a.path}
            onClick={a.onClick}
          >
            {a.label}
          </Button>
        ))}
        {secondary.map((a) => (
          <Button key={a.key} variant="default" color={a.color} c={a.color === 'red' ? 'var(--ei-danger)' : undefined} onClick={a.onClick}>
            {a.label}
          </Button>
        ))}
        <Menu position="bottom-end" withinPortal>
          <Menu.Target>
            <Button variant="default" leftSection={<IconDownload size={16} />} rightSection={<IconChevronDown size={14} />}>
              {t('changes.export.title')}
            </Button>
          </Menu.Target>
          <Menu.Dropdown>
            <Menu.Item onClick={() => void exportAs('xlsx')}>{t('changes.export.xlsx')}</Menu.Item>
            <Menu.Item onClick={() => void exportAs('docx')}>{t('changes.export.docx')}</Menu.Item>
            <Menu.Item onClick={() => void exportAs('pdf')}>{t('changes.export.pdf')}</Menu.Item>
            <Menu.Divider />
            <Menu.Item onClick={() => void downloadGet(`/changes/${c.id}/ical`, `${c.number}.ics`)}>{t('changes.export.ical')}</Menu.Item>
          </Menu.Dropdown>
        </Menu>
      </Group>
      {dialog === null && !conflictsOf(action.error) && !(action.error instanceof ApiError && action.error.code === 'outside_window') && (
        <ErrorLine error={action.error} />
      )}

      <ReasonDialog
        opened={dialog === 'cancel'}
        title={t('changes.actions.cancel')}
        label={t('changes.dialogs.cancelReason')}
        confirm={t('changes.actions.cancel')}
        danger
        pending={action.isPending}
        error={action.error}
        onClose={() => setDialog(null)}
        onConfirm={(reason) => run('/cancel', { reason })}
      />
      <ReasonDialog
        opened={dialog === 'return'}
        title={t('changes.actions.returnToDraft')}
        label={t('changes.dialogs.returnReason')}
        confirm={t('changes.actions.returnToDraft')}
        pending={action.isPending}
        error={action.error}
        onClose={() => setDialog(null)}
        onConfirm={(reason) => run('/return', { reason })}
      />
      <ReasonDialog
        opened={dialog === 'rollback'}
        title={t('changes.actions.rollback')}
        label={t('changes.dialogs.rollbackReason')}
        confirm={t('changes.actions.rollback')}
        danger
        pending={action.isPending}
        error={action.error}
        onClose={() => setDialog(null)}
        onConfirm={(reason) => run('/rollback', { reason })}
      />
      <ReasonDialog
        opened={dialog === 'start'}
        title={t('changes.dialogs.outsideWindowTitle')}
        label={t('changes.dialogs.outsideWindowReason')}
        confirm={t('changes.actions.start')}
        pending={action.isPending}
        error={action.error instanceof ApiError && action.error.code === 'outside_window' ? null : action.error}
        onClose={() => setDialog(null)}
        onConfirm={(reason) => run('/start', { outsideWindowReason: reason })}
      />
      {dialog === 'reschedule' && (
        <RescheduleDialog
          change={c}
          opened
          pending={action.isPending}
          error={action.error}
          onClose={() => setDialog(null)}
          onConfirm={(body) => run('/reschedule', body)}
        />
      )}
      {dialog === 'complete' && (
        <CompleteDialog
          opened
          rolledBack={!!c.rollbackStartedAt}
          pending={action.isPending}
          error={action.error}
          onClose={() => setDialog(null)}
          onConfirm={(body) => run('/complete', body)}
        />
      )}
      {dialog === 'conflicts' && (
        <ConflictAckDialog
          opened
          conflicts={conflicts}
          pending={action.isPending}
          error={action.error}
          onClose={() => setDialog(null)}
          onConfirm={(ack) => run(lastPath, { acknowledgeConflicts: ack })}
        />
      )}
    </Stack>
  );
}
