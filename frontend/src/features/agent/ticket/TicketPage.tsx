import { ActionIcon, Anchor, Box, Button, Group, Menu, Paper, Skeleton, Stack, Text, Title, Tooltip } from '@mantine/core';
import { useClipboard, useHotkeys } from '@mantine/hooks';
import { notifications } from '@mantine/notifications';
import {
  IconAlertTriangle,
  IconArrowBackUp,
  IconBook2,
  IconArrowLeft,
  IconCheck,
  IconChevronDown,
  IconChevronLeft,
  IconChevronRight,
  IconCopy,
  IconDots,
  IconHandGrab,
  IconPlayerPause,
  IconPlayerPlay,
  IconTrendingUp,
  IconUserPlus,
} from '@tabler/icons-react';
import { useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { AttachmentsPanel } from '@/features/attachments';
import { Link, useLocation, useNavigate, useParams } from 'react-router';
import { ApiError } from '@/shared/api/client';
import { ListState } from '@/shared/ui/ListState';
import { CreateArticleFromTicketButton, canMakeArticle } from '@/features/knowledge';
import { MajorTicketAction } from '@/features/major';
import { CreateProblemDialog, KnownErrorsHint } from '@/features/problems';
import { mainActionOf, transitionTo } from '../api/domain';
import { useDirectory, useTake, useTicket } from '../api/hooks';
import type { AvailableTransition, Ticket } from '../api/model';
import type { QueueNavState } from '../queue/QueuePage';
import { ConfidentialIcon, PriorityBadge, SlaBadge, StatusBadge, TypeIcon } from '../ui/badges';
import { formatDateTime } from '../ui/format';
import { Markdown } from '../ui/Markdown';
import { useNow } from '../ui/useNow';
import { showError } from '../ui/undoToast';
import { ActivityFeed } from './ActivityFeed';
import { AssignDialog, EscalateDialog, TransitionDialog } from './dialogs';
import { ReplyComposer, type ReplyComposerHandle } from './ReplyComposer';
import { TicketSidePanel } from './TicketSidePanel';

/** Карточка заявки агента (10.7): шапка с главным действием, лента, редактор ответа, правая панель. */
export function TicketPage() {
  const { number = '' } = useParams();
  const ticket = useTicket(number);
  const { t } = useTranslation();

  if (ticket.isPending) {
    return (
      <Stack gap="md" aria-busy="true" aria-label={t('states.loading')}>
        <Skeleton height={28} width="40%" />
        <Skeleton height={36} width="70%" />
        <Group align="flex-start" grow>
          <Stack>
            <Skeleton height={120} />
            <Skeleton height={80} />
            <Skeleton height={80} />
          </Stack>
          <Skeleton height={360} maw={340} />
        </Group>
      </Stack>
    );
  }
  if (ticket.error) {
    const notFound = ticket.error instanceof ApiError && ticket.error.status === 404;
    return (
      <ListState
        state={notFound ? 'empty' : 'error'}
        title={notFound ? t('workspace.ticket.notFound', { number }) : undefined}
        error={ticket.error}
        onRetry={() => void ticket.refetch()}
        action={
          <Button component={Link} to="/agent/queues" variant="subtle">
            {t('workspace.ticket.toQueue')}
          </Button>
        }
      />
    );
  }
  return <TicketView key={ticket.data.id} ticket={ticket.data} />;
}

function TicketView({ ticket }: { ticket: Ticket }) {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const location = useLocation();
  const nav = location.state as QueueNavState | null;
  const directory = useDirectory().data;
  const meId = directory?.me.id ?? '';
  const now = useNow();
  const take = useTake(ticket);
  const clipboard = useClipboard();
  const composer = useRef<ReplyComposerHandle>(null);
  const [transition, setTransition] = useState<AvailableTransition | null>(null);
  const [assignOpen, setAssignOpen] = useState(false);
  const [escalateOpen, setEscalateOpen] = useState(false);
  const [problemOpen, setProblemOpen] = useState(false);
  // «Создать проблему» (10.7): из инцидента, тема и описание копируются, инцидент связывается.
  const canProblem = ticket.type === 'incident' && ticket.permissions.edit;

  const main = mainActionOf(ticket, meId);
  const resolve = transitionTo(ticket, 'resolved');
  const resume = transitionTo(ticket, 'in_progress');
  const pause = transitionTo(ticket, 'pending');
  // Статья из заявки (KB-05): только из решённой или закрытой.
  const canArticle = canMakeArticle(ticket.status.category);
  const others = ticket.transitions.filter((x) => x !== resolve && x !== resume && x !== pause);

  const queue = nav?.queue ?? [];
  const pos = queue.indexOf(ticket.number);
  const prev = pos > 0 ? queue[pos - 1] : undefined;
  const next = pos >= 0 && pos < queue.length - 1 ? queue[pos + 1] : undefined;
  const backTo = nav?.backTo ?? '/agent/queues';
  const go = (n?: string) => n && navigate(`/agent/tickets/${n}`, { state: nav, replace: true });

  const doTake = () => take.mutate(undefined, { onError: showError });
  const runMain = () => {
    if (main === 'take') doTake();
    else if (main === 'resolve' && resolve) setTransition(resolve);
    else if (main === 'resume' && resume) setTransition(resume);
  };
  const copyLink = () => {
    clipboard.copy(`${window.location.origin}/agent/tickets/${ticket.number}`);
    notifications.show({ message: t('workspace.ticket.copied'), icon: <IconCheck size={16} />, color: 'green', autoClose: 2000 });
  };

  // Клавиши карточки: R — ответ, M — заметка, T — взять, E — решить, J/K — следующая и предыдущая, U — к очереди.
  useHotkeys([
    ['r', () => composer.current?.focus('reply')],
    ['m', () => composer.current?.focus('note')],
    ['t', () => main === 'take' && doTake()],
    ['e', () => resolve && setTransition(resolve)],
    ['a', () => ticket.permissions.assign && setAssignOpen(true)],
    ['j', () => go(next)],
    ['k', () => go(prev)],
    ['u', () => navigate(backTo)],
  ]);

  const mainLabel = main === 'take' ? t('workspace.actions.take') : main === 'resolve' ? (resolve?.name ?? t('workspace.actions.resolve')) : main === 'resume' ? (resume?.name ?? t('workspace.actions.resume')) : null;
  const MainIcon = main === 'take' ? IconHandGrab : main === 'resolve' ? IconCheck : IconPlayerPlay;

  return (
    <Box className="ew-ticket">
      <Group justify="space-between" mb={6} wrap="nowrap">
        <Group gap={6} wrap="nowrap">
          <Anchor component={Link} to={backTo} size="sm" c="dimmed">
            <Group gap={4} wrap="nowrap">
              <IconArrowLeft size={14} />
              {t('workspace.ticket.back')}
            </Group>
          </Anchor>
        </Group>
        {queue.length > 1 && pos >= 0 && (
          <Group gap={4} wrap="nowrap">
            <Text size="xs" c="dimmed">
              {t('workspace.ticket.position', { pos: pos + 1, total: queue.length })}
            </Text>
            <Tooltip label={`${t('workspace.ticket.prev')} · K`}>
              <ActionIcon variant="subtle" color="gray" disabled={!prev} onClick={() => go(prev)} aria-label={t('workspace.ticket.prev')}>
                <IconChevronLeft size={16} />
              </ActionIcon>
            </Tooltip>
            <Tooltip label={`${t('workspace.ticket.next')} · J`}>
              <ActionIcon variant="subtle" color="gray" disabled={!next} onClick={() => go(next)} aria-label={t('workspace.ticket.next')}>
                <IconChevronRight size={16} />
              </ActionIcon>
            </Tooltip>
          </Group>
        )}
      </Group>

      <Group justify="space-between" align="flex-start" wrap="wrap" gap="sm" mb="md">
        <Stack gap={4} style={{ flex: '1 1 420px', minWidth: 0 }}>
          <Group gap={8} wrap="wrap">
            <TypeIcon type={ticket.type} />
            <Text ff="monospace" c="dimmed" size="sm">
              {ticket.number}
            </Text>
            <Tooltip label={t('workspace.ticket.copyLink')}>
              <ActionIcon size="sm" variant="subtle" color="gray" onClick={copyLink} aria-label={t('workspace.ticket.copyLink')}>
                <IconCopy size={14} />
              </ActionIcon>
            </Tooltip>
            {ticket.confidential && <ConfidentialIcon />}
            <PriorityBadge priority={ticket.priority} variant="full" />
            <StatusBadge status={ticket.status} />
            <SlaBadge timer={ticket.sla} now={now} startAt={ticket.createdAt} withKind />
            {ticket.escalationLevel > 0 && (
              <Text size="xs" c="var(--ei-warn)" fw={600}>
                {t('workspace.ticket.escalated', { level: ticket.escalationLevel })}
              </Text>
            )}
          </Group>
          <Title order={2} fz={{ base: 'lg', sm: 'xl' }} style={{ overflowWrap: 'anywhere' }}>
            {ticket.title}
          </Title>
          <Text size="xs" c="dimmed">
            {t('workspace.ticket.meta', {
              requester: ticket.requester.name,
              date: formatDateTime(ticket.createdAt, i18n.language),
              channel: t(`workspace.channel.${ticket.channel}`),
            })}
          </Text>
        </Stack>

        <Group gap={6} wrap="nowrap">
          {mainLabel && (
            <Tooltip label={main === 'take' ? 'T' : main === 'resolve' ? 'E' : undefined} disabled={main === 'resume'}>
              <Button leftSection={<MainIcon size={16} />} onClick={runMain} loading={take.isPending} color={main === 'resolve' ? 'green' : undefined}>
                {mainLabel}
              </Button>
            </Tooltip>
          )}
          {ticket.type === 'incident' && (
            <MajorTicketAction
              ticket={{ id: ticket.id, number: ticket.number, title: ticket.title, serviceId: ticket.service?.id ?? null }}
              closed={ticket.status.category === 'closed' || ticket.status.category === 'cancelled'}
            />
          )}
          {canArticle && <CreateArticleFromTicketButton ticketNumber={ticket.number} visibleFrom="sm" />}
          {ticket.permissions.assign && (
            <Button variant="default" leftSection={<IconUserPlus size={16} />} onClick={() => setAssignOpen(true)} visibleFrom="sm">
              {t('workspace.actions.assign')}
            </Button>
          )}
          <Menu position="bottom-end" width={240}>
            <Menu.Target>
              <Button variant="default" rightSection={<IconChevronDown size={14} />} px="sm" aria-label={t('workspace.actions.more')}>
                <IconDots size={16} />
              </Button>
            </Menu.Target>
            <Menu.Dropdown>
              {main !== 'resolve' && resolve && (
                <Menu.Item leftSection={<IconCheck size={16} />} onClick={() => setTransition(resolve)} rightSection={<Text size="xs" c="dimmed">E</Text>}>
                  {resolve.name}
                </Menu.Item>
              )}
              {pause && (
                <Menu.Item leftSection={<IconPlayerPause size={16} />} onClick={() => setTransition(pause)}>
                  {pause.name}
                </Menu.Item>
              )}
              {main !== 'resume' && resume && ticket.status.category !== 'new' && ticket.status.category !== 'assigned' && (
                <Menu.Item leftSection={<IconArrowBackUp size={16} />} onClick={() => setTransition(resume)}>
                  {resume.name}
                </Menu.Item>
              )}
              {ticket.permissions.assign && (
                <Menu.Item leftSection={<IconUserPlus size={16} />} onClick={() => setAssignOpen(true)} rightSection={<Text size="xs" c="dimmed">A</Text>}>
                  {t('workspace.actions.assign')}
                </Menu.Item>
              )}
              <Menu.Item leftSection={<IconTrendingUp size={16} />} onClick={() => setEscalateOpen(true)}>
                {t('workspace.actions.escalate')}
              </Menu.Item>
              <Menu.Item leftSection={<IconCopy size={16} />} onClick={copyLink}>
                {t('workspace.ticket.copyLink')}
              </Menu.Item>
              {canArticle && (
                <Menu.Item
                  leftSection={<IconBook2 size={16} />}
                  component={Link}
                  to={`/agent/kb/new?fromTicket=${encodeURIComponent(ticket.number)}`}
                >
                  {t('kb.fromTicket.button')}
                </Menu.Item>
              )}
              {canProblem && (
                <Menu.Item leftSection={<IconAlertTriangle size={16} />} onClick={() => setProblemOpen(true)}>
                  {t('problems.create.button')}
                </Menu.Item>
              )}
              {others.length > 0 && <Menu.Divider />}
              {others.map((x) => (
                <Menu.Item key={x.to} color={x.category === 'cancelled' ? 'red' : undefined} onClick={() => setTransition(x)}>
                  {x.name}
                </Menu.Item>
              ))}
            </Menu.Dropdown>
          </Menu>
        </Group>
      </Group>

      <div className="ew-ticket-grid">
        <Stack gap="md" style={{ minWidth: 0 }}>
          <Paper withBorder p="md" radius="md">
            <Text size="xs" c="dimmed" fw={500} mb={4}>
              {t('workspace.ticket.description')}
            </Text>
            {ticket.description ? <Markdown text={ticket.description} /> : <Text c="dimmed" size="sm">—</Text>}
            {ticket.resolutionText && (
              <Paper mt="sm" p="sm" radius="sm" className="ew-resolution">
                <Text size="xs" fw={600} mb={2}>
                  {t('workspace.ticket.resolution', {
                    code: directory?.resolutionCodes.find((c) => c.id === ticket.resolutionCode)?.name ?? ticket.resolutionCode ?? '',
                  })}
                </Text>
                <Markdown text={ticket.resolutionText} />
              </Paper>
            )}
            <Box mt="md">
              <AttachmentsPanel
                ownerType="ticket"
                ownerId={ticket.id}
                canUpload={ticket.permissions.comment && ticket.status.category !== 'closed' && ticket.status.category !== 'cancelled'}
                canUploadInternal={ticket.permissions.internalNotes}
              />
            </Box>
          </Paper>
          <ActivityFeed ticket={ticket} />
          {ticket.permissions.comment && ticket.status.category !== 'closed' && ticket.status.category !== 'cancelled' && (
            <ReplyComposer ref={composer} ticket={ticket} onSendAndResolve={resolve ? () => setTransition(resolve) : undefined} />
          )}
        </Stack>
        <aside aria-label={t('workspace.side.label')}>
          {ticket.type === 'incident' && <KnownErrorsHint ticketId={ticket.id} />}
          <TicketSidePanel ticket={ticket} />
        </aside>
      </div>

      <TransitionDialog ticket={ticket} transition={transition} onClose={() => setTransition(null)} />
      <AssignDialog ticket={ticket} opened={assignOpen} onClose={() => setAssignOpen(false)} />
      <EscalateDialog ticket={ticket} opened={escalateOpen} onClose={() => setEscalateOpen(false)} />
      <CreateProblemDialog
        opened={problemOpen}
        onClose={() => setProblemOpen(false)}
        draft={{ title: ticket.title, description: ticket.description, incidentIds: [ticket.id], source: 'incident', fromNumber: ticket.number, groupId: ticket.group?.id ?? null }}
      />
    </Box>
  );
}
