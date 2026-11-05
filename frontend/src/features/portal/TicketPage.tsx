import {
  Alert,
  Badge,
  Button,
  Card,
  Code,
  Group,
  Modal,
  Paper,
  Skeleton,
  Stack,
  Stepper,
  Text,
  Textarea,
  Timeline,
  Title,
} from '@mantine/core';
import { IconAlertCircle, IconCheck, IconMessage, IconRefresh, IconX } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate, useParams } from 'react-router';
import { ApiError } from '@/shared/api/client';
import { ListState } from '@/shared/ui/ListState';
import { ApprovalRoute } from '@/features/catalog/ApprovalRoute';
import { useApprovalRoute } from '@/features/catalog/api';
import { useUserName } from '@/features/catalog/users';
import { saveDraft } from './drafts';
import { StatusBadge } from './StatusBadge';
import { SurveyBlock } from './SurveyBlock';
import {
  type Activity,
  type AvailableTransition,
  type TicketDetails,
  progressSteps,
  useActivity,
  useReply,
  useTicket,
  useTransition,
} from './tickets';

/** Карточка заявки на портале (10.5): шкала статуса, срок, публичная лента, ответ, действия заявителя, оценка. */
export function TicketPage() {
  const { t } = useTranslation();
  const { ref } = useParams();
  const ticket = useTicket(ref);
  if (ticket.isPending) {
    return (
      <Stack maw={900} mx="auto" py="md" aria-busy="true">
        <Skeleton height={32} width="50%" />
        <Skeleton height={60} />
        <Skeleton height={200} />
      </Stack>
    );
  }
  if (ticket.error) {
    const notFound = ticket.error instanceof ApiError && ticket.error.status === 404;
    return (
      <ListState
        state={notFound ? 'empty' : 'error'}
        title={notFound ? t('ticket.notFound') : undefined}
        error={ticket.error}
        onRetry={() => void ticket.refetch()}
      />
    );
  }
  return <TicketView ticket={ticket.data} />;
}

function TicketView({ ticket }: { ticket: TicketDetails }) {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const route = useApprovalRoute(ticket.type === 'request' ? ticket.id : undefined);
  const cat = ticket.status.category;
  const final = cat === 'closed' || cat === 'cancelled';
  const steps = progressSteps(ticket.type, cat, !!route.data);
  const active = cat === 'assigned' ? steps.indexOf('new') : cat === 'pending' ? steps.indexOf('in_progress') : steps.indexOf(cat);
  const dt = new Intl.DateTimeFormat(i18n.language, { dateStyle: 'long', timeStyle: 'short' });

  const createBasedOn = () => {
    saveDraft('incident', {
      forOther: false,
      forUserId: null,
      title: ticket.title,
      description: t('ticket.basedOn', { number: ticket.number }),
      impact: 'only_me',
      values: {},
    });
    navigate('/portal/report');
  };

  return (
    <Stack maw={900} mx="auto" gap="lg" py="md">
      <Stack gap={4}>
        <Group gap="xs">
          <Text ff="monospace" c="dimmed">
            {ticket.number}
          </Text>
          <StatusBadge status={ticket.status} />
          <Badge variant="outline" color="gray">
            {t(`myTickets.types.${ticket.type}`, { defaultValue: ticket.type })}
          </Badge>
        </Group>
        <Title order={2}>{ticket.title}</Title>
      </Stack>

      {cat !== 'cancelled' && (
        <Stepper active={final ? steps.length : Math.max(active, 0)} size="sm" allowNextStepsSelect={false} aria-label={t('ticket.progress')}>
          {steps.map((s) => (
            <Stepper.Step key={s} label={t(`ticket.steps.${s}`)} />
          ))}
        </Stepper>
      )}

      {ticket.dueAt && !final && cat !== 'resolved' && (
        <Alert variant="light" color="blue">
          {t('ticket.expected', { date: dt.format(new Date(ticket.dueAt)) })}
        </Alert>
      )}
      {cat === 'pending' && ticket.pendingReason && (
        <Alert variant="light" color="yellow">
          {t('ticket.pending', { reason: ticket.pendingReason })}
        </Alert>
      )}

      {route.data && <ApprovalRoute route={route.data} />}

      {cat === 'resolved' && <ResolvedActions ticket={ticket} />}

      {ticket.description && (
        <Paper withBorder p="md">
          <Text size="sm" style={{ whiteSpace: 'pre-wrap' }}>
            {ticket.description}
          </Text>
        </Paper>
      )}

      <Feed ticketId={ticket.id} />

      {!final && ticket.permissions.comment !== false && <ReplyBox ticketId={ticket.id} />}

      {cat === 'closed' && <SurveyBlock ticketId={ticket.id} />}

      <Group gap="xs">
        {!final && <CancelAction ticket={ticket} />}
        {final && (
          <Button variant="default" onClick={createBasedOn}>
            {t('ticket.createBasedOn')}
          </Button>
        )}
      </Group>
    </Stack>
  );
}

function ResolvedActions({ ticket }: { ticket: TicketDetails }) {
  const { t } = useTranslation();
  const transition = useTransition(ticket.id);
  const [reopen, setReopen] = useState(false);
  const [comment, setComment] = useState('');
  const confirm = ticket.transitions.find((x) => x.category === 'closed');
  const back = ticket.transitions.find((x) => x.category === 'in_progress' || x.category === 'new' || x.category === 'assigned');
  return (
    <Card withBorder padding="md" bg="var(--mantine-color-teal-light)">
      <Stack gap="sm">
        <Text fw={600}>{t('ticket.resolvedTitle')}</Text>
        {ticket.resolutionText && (
          <Text size="sm" style={{ whiteSpace: 'pre-wrap' }}>
            {ticket.resolutionText}
          </Text>
        )}
        <Group gap="xs">
          {confirm && (
            <Button leftSection={<IconCheck size={16} />} color="teal" loading={transition.isPending} onClick={() => transition.mutate({ to: confirm.to })}>
              {t('ticket.confirm')}
            </Button>
          )}
          {back && (
            <Button variant="default" leftSection={<IconRefresh size={16} />} onClick={() => setReopen(true)}>
              {t('ticket.notHelped')}
            </Button>
          )}
        </Group>
        {reopen && back && (
          <Stack gap="xs">
            <Textarea
              label={t('ticket.notHelpedComment')}
              required
              autosize
              minRows={2}
              value={comment}
              onChange={(e) => setComment(e.currentTarget.value)}
            />
            <Group gap="xs">
              <Button
                size="xs"
                disabled={!comment.trim()}
                loading={transition.isPending}
                onClick={() => transition.mutate({ to: back.to, comment: comment.trim(), reasonCode: 'reopened' })}
              >
                {t('ticket.sendBack')}
              </Button>
              <Button size="xs" variant="subtle" onClick={() => setReopen(false)}>
                {t('actions.cancel')}
              </Button>
            </Group>
          </Stack>
        )}
        <ErrorLine error={transition.error} />
      </Stack>
    </Card>
  );
}

function CancelAction({ ticket }: { ticket: TicketDetails }) {
  const { t } = useTranslation();
  const transition = useTransition(ticket.id);
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');
  const cancel: AvailableTransition | undefined = ticket.transitions.find((x) => x.category === 'cancelled');
  if (!cancel) return null;
  return (
    <>
      <Button variant="subtle" color="red" leftSection={<IconX size={16} />} onClick={() => setOpen(true)}>
        {t('ticket.cancel')}
      </Button>
      <Modal opened={open} onClose={() => setOpen(false)} title={t('ticket.cancelTitle', { number: ticket.number })} centered>
        <Stack gap="sm">
          <Text size="sm">{t('ticket.cancelText')}</Text>
          <Textarea label={t('ticket.cancelReason')} required autosize minRows={2} value={reason} onChange={(e) => setReason(e.currentTarget.value)} />
          <ErrorLine error={transition.error} />
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setOpen(false)}>
              {t('actions.back')}
            </Button>
            <Button
              color="red"
              disabled={!reason.trim()}
              loading={transition.isPending}
              onClick={() =>
                transition.mutate(
                  { to: cancel.to, comment: reason.trim(), reasonCode: 'requester_cancelled' },
                  { onSuccess: () => setOpen(false) },
                )
              }
            >
              {t('ticket.cancelConfirm')}
            </Button>
          </Group>
        </Stack>
      </Modal>
    </>
  );
}

function Feed({ ticketId }: { ticketId: string }) {
  const { t } = useTranslation();
  const activity = useActivity(ticketId);
  const items = (activity.data?.pages.flatMap((p) => p.items) ?? []).filter((a) => a.visibility === 'public');
  if (activity.isPending) return <Skeleton height={80} />;
  if (activity.error) return <ListState state="error" error={activity.error} onRetry={() => void activity.refetch()} />;
  if (!items.length) return null;
  return (
    <Stack gap="xs">
      <Title order={4}>{t('ticket.feed')}</Title>
      <Timeline bulletSize={22} lineWidth={2}>
        {items.map((a) => (
          <Timeline.Item key={a.id} bullet={<IconMessage size={12} />}>
            <FeedItem a={a} />
          </Timeline.Item>
        ))}
      </Timeline>
    </Stack>
  );
}

function FeedItem({ a }: { a: Activity }) {
  const { t, i18n } = useTranslation();
  const author = useUserName(a.authorId);
  const dt = new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeStyle: 'short' });
  return (
    <Stack gap={2}>
      <Group gap="xs">
        <Text size="sm" fw={600}>
          {a.authorId ? (a.fromRequester ? t('ticket.you') : author) : t('ticket.system')}
        </Text>
        <Text size="xs" c="dimmed">
          {dt.format(new Date(a.createdAt))}
        </Text>
      </Group>
      <Text size="sm" style={{ whiteSpace: 'pre-wrap' }}>
        {a.body}
      </Text>
    </Stack>
  );
}

function ReplyBox({ ticketId }: { ticketId: string }) {
  const { t } = useTranslation();
  const reply = useReply(ticketId);
  const [body, setBody] = useState('');
  const send = (mask?: boolean) =>
    reply.mutate({ body: body.trim(), secrets: mask ? 'mask' : 'warn' }, { onSuccess: () => setBody('') });
  const secrets = reply.error instanceof ApiError && reply.error.code === 'secrets_detected';
  return (
    <Stack gap="xs">
      <Textarea
        label={t('ticket.reply')}
        autosize
        minRows={3}
        maxRows={12}
        value={body}
        onChange={(e) => setBody(e.currentTarget.value)}
      />
      {secrets ? (
        <Alert color="orange" title={t('request.secretsTitle')}>
          <Group gap="xs">
            <Button size="xs" color="orange" onClick={() => send(true)} loading={reply.isPending}>
              {t('request.maskAndSend')}
            </Button>
            <Button size="xs" variant="subtle" onClick={() => reply.reset()}>
              {t('request.editText')}
            </Button>
          </Group>
        </Alert>
      ) : (
        <ErrorLine error={reply.error} />
      )}
      <Group justify="flex-end">
        <Button disabled={!body.trim()} loading={reply.isPending} onClick={() => send()}>
          {t('ticket.send')}
        </Button>
      </Group>
    </Stack>
  );
}

export function ErrorLine({ error }: { error: unknown }) {
  const { t } = useTranslation();
  if (!error) return null;
  const e = error instanceof ApiError ? error : null;
  return (
    <Alert color="red" icon={<IconAlertCircle />} role="alert" p="xs">
      <Text size="sm">{e?.message ?? t('states.errorText')}</Text>
      {e?.traceId && (
        <Text size="xs" c="dimmed">
          {t('states.traceId')}: <Code>{e.traceId}</Code>
        </Text>
      )}
    </Alert>
  );
}
