import { ActionIcon, Badge, Button, Card, Group, Indicator, Modal, Stack, Tabs, Text, Textarea, Title, Tooltip } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconArrowForward, IconCheck, IconChecklist, IconHelp, IconX } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { ApiError } from '@/shared/api/client';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import { type Approval, useApprovalCount, useApprovals, useDecide, useDelegate } from './api';
import { UserPicker } from './UserPicker';
import { useUserName } from './users';

/** Согласования (10.8): вкладки «Ждут меня» и «Мои решения». Доступно на портале и в рабочем месте. */
export function ApprovalsPage() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const tab = params.get('tab') === 'decided' ? 'decided' : 'pending';
  const count = useApprovalCount();
  return (
    <Stack maw={1000} mx="auto" gap="md" py="md">
      <Title order={2}>{t('approvals.title')}</Title>
      <Tabs value={tab} onChange={(v) => setParams(v === 'decided' ? { tab: 'decided' } : {}, { replace: true })} keepMounted={false}>
        <Tabs.List>
          <Tabs.Tab value="pending" rightSection={count.data ? <Badge size="xs" circle>{count.data}</Badge> : undefined}>
            {t('approvals.tabs.pending')}
          </Tabs.Tab>
          <Tabs.Tab value="decided">{t('approvals.tabs.decided')}</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="pending" pt="md">
          <ApprovalsList tab="pending" />
        </Tabs.Panel>
        <Tabs.Panel value="decided" pt="md">
          <ApprovalsList tab="decided" />
        </Tabs.Panel>
      </Tabs>
    </Stack>
  );
}

export function ApprovalsList({ tab }: { tab: 'pending' | 'decided' }) {
  const { t } = useTranslation();
  const list = useApprovals(tab);
  const items = list.data?.pages.flatMap((p) => p.items) ?? [];
  const state = listStateOf(list, items.length);
  if (state) {
    return (
      <ListState
        state={state}
        title={state === 'empty' ? t(tab === 'pending' ? 'approvals.emptyPending' : 'approvals.emptyDecided') : undefined}
        error={list.error}
        onRetry={() => void list.refetch()}
      />
    );
  }
  return (
    <Stack gap="sm">
      {items.map((a) => (
        <ApprovalCard key={a.id} approval={a} />
      ))}
      {list.hasNextPage && (
        <Button variant="subtle" mx="auto" onClick={() => void list.fetchNextPage()} loading={list.isFetchingNextPage}>
          {t('actions.showMore')}
        </Button>
      )}
    </Stack>
  );
}

type Action = 'approve' | 'reject' | 'request_info' | 'delegate';

function ApprovalCard({ approval: a }: { approval: Approval }) {
  const { t, i18n } = useTranslation();
  const requester = useUserName(a.requesterId);
  const createdFor = useUserName(a.createdForId);
  const [action, setAction] = useState<Action | null>(null);
  const dt = new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeStyle: 'short' });
  const overdue = a.dueAt && a.decision === 'pending' && new Date(a.dueAt) < new Date();

  return (
    <Card withBorder padding="md">
      <Group justify="space-between" align="flex-start" wrap="wrap" gap="sm">
        <Stack gap={4} style={{ flex: 1, minWidth: 240 }}>
          <Group gap="xs">
            <Text ff="monospace" size="sm" c="dimmed" component={Link} to={`/portal/tickets/${a.ticketId}`} td="none">
              {a.ticketNumber}
            </Text>
            <Badge variant="light" color="gray">
              {a.stepName}
            </Badge>
            {a.decision !== 'pending' && (
              <Badge color={a.decision === 'approved' ? 'teal' : a.decision === 'rejected' ? 'red' : 'gray'}>
                {t(`approvals.decisions.${a.decision}`)}
              </Badge>
            )}
            {a.infoRequestedAt && a.decision === 'pending' && <Badge color="yellow">{t('approvals.infoRequested')}</Badge>}
          </Group>
          <Text fw={600}>{a.title}</Text>
          <Text size="sm" c="dimmed">
            {t('approvals.from', { name: requester })}
            {a.createdForId ? ` · ${t('approvals.forWhom', { name: createdFor })}` : ''}
            {' · '}
            {dt.format(new Date(a.createdAt))}
          </Text>
          {a.dueAt && a.decision === 'pending' && (
            <Text size="sm" c={overdue ? 'red' : 'dimmed'}>
              {t('approvals.due', { date: dt.format(new Date(a.dueAt)) })}
            </Text>
          )}
          {a.comment && (
            <Text size="sm" fs="italic">
              «{a.comment}»
            </Text>
          )}
        </Stack>
        {a.decision === 'pending' && (
          <Group gap="xs">
            <Button size="sm" color="teal" leftSection={<IconCheck size={16} />} onClick={() => setAction('approve')}>
              {t('approvals.approve')}
            </Button>
            <Button size="sm" color="red" variant="light" leftSection={<IconX size={16} />} onClick={() => setAction('reject')}>
              {t('approvals.reject')}
            </Button>
            <Tooltip label={t('approvals.requestInfo')}>
              <ActionIcon size="lg" variant="default" aria-label={t('approvals.requestInfo')} onClick={() => setAction('request_info')}>
                <IconHelp size={18} />
              </ActionIcon>
            </Tooltip>
            {a.allowDelegation && (
              <Tooltip label={t('approvals.delegate')}>
                <ActionIcon size="lg" variant="default" aria-label={t('approvals.delegate')} onClick={() => setAction('delegate')}>
                  <IconArrowForward size={18} />
                </ActionIcon>
              </Tooltip>
            )}
          </Group>
        )}
      </Group>
      {action && <DecisionDialog approval={a} action={action} onClose={() => setAction(null)} />}
    </Card>
  );
}

/** Подтверждение решения: причина обязательна при отклонении и уточнении, комментарий — по желанию (10.8). */
function DecisionDialog({ approval, action, onClose }: { approval: Approval; action: Action; onClose: () => void }) {
  const { t } = useTranslation();
  const decide = useDecide();
  const delegate = useDelegate();
  const [comment, setComment] = useState('');
  const [to, setTo] = useState<string | null>(null);
  const needsText = action === 'reject' || action === 'request_info';
  const pending = decide.isPending || delegate.isPending;
  const error = (decide.error ?? delegate.error) as ApiError | null;

  const done = () => {
    notifications.show({ color: 'teal', message: t(`approvals.done.${action}`, { number: approval.ticketNumber }) });
    onClose();
  };
  const submit = () => {
    if (action === 'delegate') {
      if (to) delegate.mutate({ id: approval.id, toUserId: to, comment: comment.trim() || undefined }, { onSuccess: done });
    } else {
      decide.mutate({ id: approval.id, decision: action, comment: comment.trim() || undefined }, { onSuccess: done });
    }
  };

  return (
    <Modal opened onClose={onClose} title={t(`approvals.dialog.${action}`, { number: approval.ticketNumber })} centered>
      <Stack gap="sm">
        <Text size="sm">{approval.title}</Text>
        {action === 'delegate' && <UserPicker label={t('approvals.delegateTo')} value={to} onChange={setTo} />}
        <Textarea
          label={t(needsText ? `approvals.text.${action}` : 'approvals.text.comment')}
          required={needsText}
          autosize
          minRows={2}
          maxLength={4000}
          value={comment}
          onChange={(e) => setComment(e.currentTarget.value)}
          data-autofocus
        />
        {error && (
          <Text size="sm" c="red" role="alert">
            {error.message}
          </Text>
        )}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            {t('actions.cancel')}
          </Button>
          <Button
            color={action === 'reject' ? 'red' : action === 'approve' ? 'teal' : undefined}
            loading={pending}
            disabled={(needsText && !comment.trim()) || (action === 'delegate' && !to)}
            onClick={submit}
          >
            {t(`approvals.confirm.${action}`)}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}

/** Значок согласований в верхней панели со счётчиком (10.2). */
export function ApprovalsButton() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const count = useApprovalCount();
  const n = count.data ?? 0;
  return (
    <Tooltip label={t('topbar.approvals')}>
      <Indicator disabled={!n} label={n > 99 ? '99+' : n} size={16} offset={6}>
        <ActionIcon
          variant="subtle"
          size="lg"
          aria-label={n ? t('approvals.badge', { count: n }) : t('topbar.approvals')}
          onClick={() => navigate('/portal/approvals')}
        >
          <IconChecklist size={20} />
        </ActionIcon>
      </Indicator>
    </Tooltip>
  );
}
