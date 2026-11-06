import { Alert, Button, Group, Modal, Skeleton, Stack, Stepper, Text, Textarea } from '@mantine/core';
import { IconCheck, IconClock, IconHourglass, IconLock, IconRefresh, IconX } from '@tabler/icons-react';
import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useLocation, useNavigate, useParams } from 'react-router';
import { ApiError } from '@/shared/api/client';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { ListState } from '@/shared/ui/ListState';
import { usePersonName } from '@/shared/api/people';
import { useMe } from '@/features/auth/api';
import { AttachmentsPanel } from '@/features/attachments';
import { ApprovalRoute } from '@/features/catalog/ApprovalRoute';
import { useApprovalRoute } from '@/features/catalog/api';
import {
  type ActivityEntry,
  type AvailableTransition,
  type TicketDetails,
  secretFindings,
  useAddComment,
  useTicket,
  useTicketActivity,
  useTransitionTicket,
} from '@/features/tickets/api';
import { saveDraft } from './drafts';
import { dateTime, relativeTime } from './format';
import { progressIndex, progressSteps } from './requester';
import { SurveyBlock } from './SurveyBlock';
import { PersonAvatar, PersonName, TicketStatus } from './ui';
import './portal.css';

/** Карточка заявки на портале (10.5): шкала статуса, срок, публичная лента, ответ, действия заявителя, оценка. */
export function TicketPage() {
  const { t } = useTranslation();
  const { ref } = useParams();
  const ticket = useTicket(ref);
  if (ticket.isPending) {
    return (
      <div className="ep-page" aria-busy="true">
        <Skeleton height={32} width="50%" />
        <Skeleton height={60} />
        <Skeleton height={200} />
      </div>
    );
  }
  if (ticket.error) {
    const notFound = ticket.error instanceof ApiError && ticket.error.status === 404;
    return (
      <div className="ep-page">
        <ListState
          state={notFound ? 'empty' : 'error'}
          title={notFound ? t('ticket.notFound') : undefined}
          error={ticket.error}
          onRetry={() => void ticket.refetch()}
        />
      </div>
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
    <div className="ep-page">
      <nav className="ep-crumbs" aria-label={t('portal.breadcrumbs')}>
        <Link to="/portal">{t('nav.home')}</Link> / <Link to="/portal/tickets">{t('nav.myTickets')}</Link> / {ticket.number}
      </nav>
      <Stack gap={6}>
        <Group gap="xs">
          <TicketStatus status={ticket.status} />
          <span className="ep-mono" style={{ fontSize: 13 }}>
            {ticket.number}
          </span>
          <span className="ep-chip">{t(`myTickets.types.${ticket.type}`, { defaultValue: ticket.type })}</span>
        </Group>
        <h1 className="ep-h1">{ticket.title}</h1>
      </Stack>

      <div className="ep-ticket">
        <div className="ep-ticket-main">
          {cat !== 'cancelled' && (
            <Stepper active={progressIndex(steps, cat)} size="sm" allowNextStepsSelect={false} aria-label={t('ticket.progress')}>
              {steps.map((s) => (
                <Stepper.Step key={s} label={t(`ticket.steps.${s}`)} />
              ))}
            </Stepper>
          )}

          {ticket.dueAt && !final && cat !== 'resolved' && (
            <div className="ep-banner">
              <IconClock size={20} aria-hidden="true" />
              <div>
                <strong>{t('ticket.expected', { date: dateTime(ticket.dueAt, i18n.language) })}</strong>
              </div>
            </div>
          )}
          {cat === 'pending' && (
            <div className="ep-banner" style={{ background: 'var(--ei-wait-soft)' }}>
              <IconHourglass size={20} aria-hidden="true" style={{ color: 'var(--ei-wait)' }} />
              <div>
                <strong>{t('ticket.waitingYou')}</strong>
                {ticket.pendingReason && <p>{t('ticket.pending', { reason: ticket.pendingReason })}</p>}
              </div>
            </div>
          )}

          {route.data && <ApprovalRoute route={route.data} />}

          {cat === 'resolved' && <ResolvedActions ticket={ticket} />}

          {ticket.description && (
            <div className="ep-card" style={{ padding: 16 }}>
              <p className="ep-msg-text">{ticket.description}</p>
            </div>
          )}

          <AttachmentsPanel
            ownerType="ticket"
            ownerId={ticket.id}
            canUpload={!final && ticket.permissions.comment}
            className="ep-card ep-files"
          />

          <Feed ticket={ticket} />

          {!final && ticket.permissions.comment && <ReplyBox ticket={ticket} />}

          {cat === 'closed' && <SurveyBlock ticketId={ticket.id} />}

          <Group gap="xs">
            {!final && <CancelAction ticket={ticket} />}
            {final && (
              <Button variant="default" onClick={createBasedOn}>
                {t('ticket.createBasedOn')}
              </Button>
            )}
          </Group>
        </div>
        <Details ticket={ticket} />
      </div>
    </div>
  );
}

/** Справа: кто ведёт заявку и сроки. Имена — из справочника людей. */
function Details({ ticket }: { ticket: TicketDetails }) {
  const { t, i18n } = useTranslation();
  const me = useMe();
  const meId = me.data?.id;
  const assignee = usePersonName(ticket.assigneeId);
  return (
    <aside className="ep-card ep-details" aria-label={t('ticket.details')}>
      <dl>
        <dt>{t('myTickets.cols.assignee')}</dt>
        <dd>{ticket.assigneeId ? assignee : t('myTickets.notAssigned')}</dd>
        <dt>{t('ticket.requester')}</dt>
        <dd>
          <PersonName id={ticket.requesterId} meId={meId} />
        </dd>
        {ticket.createdForId && ticket.createdForId !== ticket.requesterId && (
          <>
            <dt>{t('ticket.createdFor')}</dt>
            <dd>
              <PersonName id={ticket.createdForId} meId={meId} />
            </dd>
          </>
        )}
        <dt>{t('ticket.created')}</dt>
        <dd>{dateTime(ticket.createdAt, i18n.language)}</dd>
        <dt>{t('myTickets.cols.updated')}</dt>
        <dd>{relativeTime(ticket.updatedAt, i18n.language)}</dd>
        {ticket.resolvedAt && (
          <>
            <dt>{t('ticket.resolvedAt')}</dt>
            <dd>{dateTime(ticket.resolvedAt, i18n.language)}</dd>
          </>
        )}
        {ticket.closedAt && (
          <>
            <dt>{t('ticket.closedAt')}</dt>
            <dd>{dateTime(ticket.closedAt, i18n.language)}</dd>
          </>
        )}
      </dl>
    </aside>
  );
}

function ResolvedActions({ ticket }: { ticket: TicketDetails }) {
  const { t } = useTranslation();
  const transition = useTransitionTicket();
  const [reopen, setReopen] = useState(false);
  const [comment, setComment] = useState('');
  const confirm = ticket.transitions.find((x) => x.category === 'closed');
  const back = ticket.transitions.find((x) => x.category === 'in_progress' || x.category === 'new' || x.category === 'assigned');
  return (
    <div className="ep-resolved">
      <Stack gap="sm">
        <Text fw={600}>{t('ticket.resolvedTitle')}</Text>
        {ticket.resolutionText && <p className="ep-msg-text">{ticket.resolutionText}</p>}
        <Group gap="xs">
          {confirm && (
            <Button
              leftSection={<IconCheck size={16} />}
              loading={transition.isPending && !reopen}
              onClick={() => transition.mutate({ ticket, input: { to: confirm.to } })}
            >
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
                disabled={!comment.trim()}
                loading={transition.isPending}
                onClick={() => transition.mutate({ ticket, input: { to: back.to, comment: comment.trim(), reasonCode: 'reopened' } })}
              >
                {t('ticket.sendBack')}
              </Button>
              <Button variant="subtle" color="gray" onClick={() => setReopen(false)}>
                {t('actions.cancel')}
              </Button>
            </Group>
          </Stack>
        )}
        <ErrorLine error={transition.error} />
      </Stack>
    </div>
  );
}

function CancelAction({ ticket }: { ticket: TicketDetails }) {
  const { t } = useTranslation();
  const transition = useTransitionTicket();
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
                  { ticket, input: { to: cancel.to, comment: reason.trim(), reasonCode: 'requester_cancelled' } },
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

/** Публичная лента: сообщения карточками с аватаром, смена статуса — строкой с кружком. */
function Feed({ ticket }: { ticket: TicketDetails }) {
  const { t } = useTranslation();
  const me = useMe();
  const activity = useTicketActivity(ticket.id, 'public');
  // Лента приходит от старых к новым страницами по 50, читается сверху вниз, как переписка.
  const items = (activity.data?.pages.flatMap((p) => p.items) ?? [])
    .filter((a) => a.visibility === 'public')
    .sort((a, b) => a.createdAt.localeCompare(b.createdAt));
  if (activity.isPending) return <Skeleton height={80} radius="lg" />;
  if (activity.error) return <ListState state="error" error={activity.error} onRetry={() => void activity.refetch()} />;
  if (!items.length) return null;
  return (
    <section className="ep-section" aria-label={t('ticket.feed')}>
      <h2 className="ep-h2">{t('ticket.feed')}</h2>
      <div className="ep-feed">
        {items.map((a) =>
          a.kind === 'reply' || a.kind === 'note' ? (
            <Message key={a.id} a={a} mine={!!me.data && a.authorId === me.data.id} />
          ) : (
            <SystemEvent key={a.id} a={a} />
          ),
        )}
      </div>
      {activity.hasNextPage && (
        <Button variant="subtle" size="xs" mx="auto" onClick={() => void activity.fetchNextPage()} loading={activity.isFetchingNextPage}>
          {t('actions.showMore')}
        </Button>
      )}
    </section>
  );
}

function Message({ a, mine }: { a: ActivityEntry; mine: boolean }) {
  const { t, i18n } = useTranslation();
  const author = usePersonName(a.authorId);
  const name = mine ? t('people.you') : a.authorId ? author : t('ticket.system');
  return (
    <article className="ep-msg">
      <PersonAvatar name={a.authorId ? author : '·'} tone={a.fromRequester ? undefined : 'accent'} />
      <div className="ep-card ep-msg-body" data-mine={mine || undefined}>
        <div className="ep-msg-head">
          <b>{name}</b>
          {!a.fromRequester && a.authorId && <span className="ep-chip" data-tone="accent">{t('ticket.support')}</span>}
          <time dateTime={a.createdAt} title={dateTime(a.createdAt, i18n.language)}>
            {relativeTime(a.createdAt, i18n.language)}
          </time>
        </div>
        <p className="ep-msg-text">{a.body}</p>
      </div>
    </article>
  );
}

function SystemEvent({ a }: { a: ActivityEntry }) {
  const { t, i18n } = useTranslation();
  const event = a.meta.event as string | undefined;
  let text: string;
  if (event === 'status_changed') text = t('ticket.event.status', { name: String(a.meta.toName ?? a.meta.to ?? '') });
  else if (event === 'merged') text = t('ticket.event.merged', { numbers: ((a.meta.sources as string[] | undefined) ?? []).join(', ') });
  else text = a.body || t('ticket.event.other');
  return (
    <div className="ep-event">
      <span>
        {text}
        {event === 'status_changed' && a.body ? ` — «${a.body}»` : ''} ·{' '}
        <time dateTime={a.createdAt} title={dateTime(a.createdAt, i18n.language)}>
          {relativeTime(a.createdAt, i18n.language)}
        </time>
      </span>
    </div>
  );
}

function ReplyBox({ ticket }: { ticket: TicketDetails }) {
  const { t } = useTranslation();
  const reply = useAddComment();
  const [body, setBody] = useState('');
  const ref = useRef<HTMLTextAreaElement>(null);
  const { hash } = useLocation();
  useEffect(() => {
    if (hash === '#reply') ref.current?.focus();
  }, [hash]);
  const send = (mask?: boolean) =>
    reply.mutate(
      { id: ticket.id, input: { body: body.trim(), visibility: 'public', secrets: mask ? 'mask' : 'warn' } },
      { onSuccess: () => setBody('') },
    );
  const secrets = secretFindings(reply.error);
  return (
    <section id="reply" className="ep-card ep-reply">
      <Stack gap="xs">
        <Textarea
          ref={ref}
          label={t('ticket.reply')}
          description={ticket.status.category === 'pending' ? t('ticket.replyResumes') : undefined}
          autosize
          minRows={3}
          maxRows={12}
          value={body}
          onChange={(e) => setBody(e.currentTarget.value)}
        />
        {secrets ? (
          <Alert color="orange" icon={<IconLock />} title={t('request.secretsTitle')}>
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
    </section>
  );
}
