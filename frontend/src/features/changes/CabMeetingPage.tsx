import { ActionIcon, Anchor, Badge, Button, Group, Select, Skeleton, Stack, Text, Textarea, Title } from '@mantine/core';
import { IconArrowLeft, IconTrash } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { ListState } from '@/shared/ui/ListState';
import { type Meeting, type MeetingItemView, useChanges, useMeeting, useMeetingAction } from './api';
import { MEETING_COLOR } from './CabPage';
import { ChangeNumber, RiskBadge, StateBadge, TypeBadge } from './ChangeBadges';
import classes from './changes.module.css';
import { Person } from './ChangesPage';
import { formatWindow } from './model';

/** Заседание CAB (CHG-07): повестка, голоса участников, решения, протокол. */
export function CabMeetingPage() {
  const { id } = useParams();
  const { t, i18n } = useTranslation();
  const meeting = useMeeting(id);
  if (meeting.isPending) return <Skeleton height={320} />;
  if (meeting.error || !meeting.data) {
    return <ListState state="error" error={meeting.error} onRetry={() => void meeting.refetch()} />;
  }
  const m = meeting.data;
  return (
    <Stack gap="md" maw={1100}>
      <Button variant="subtle" leftSection={<IconArrowLeft size={16} />} component={Link} to="/agent/changes/cab" w="fit-content" px={0}>
        {t('changes.cab.title')}
      </Button>
      <Group gap="xs">
        <Badge variant="light" color={MEETING_COLOR[m.state]}>
          {t(`changes.cab.meetingState.${m.state}`)}
        </Badge>
        <Text size="sm" c="dimmed">
          {new Date(m.startsAt).toLocaleString(i18n.language, { dateStyle: 'full', timeStyle: 'short' })} · {m.durationMinutes}{' '}
          {t('changes.duration.min')}
          {m.location ? ` · ${m.location}` : ''}
        </Text>
      </Group>
      <Title order={1} fz={24}>
        {m.title}
      </Title>
      <Group gap="xs">
        <Text size="sm" c="dimmed">
          {t('changes.cab.columns.chair')}:
        </Text>
        <Person id={m.chairId} />
        <Text size="sm" c="dimmed">
          · {t('changes.cab.membersCount', { n: m.memberIds.length })}
        </Text>
      </Group>
      <MeetingActions meeting={m} />
      <Stack gap="sm">
        <div className={classes.sectionLabel}>{t('changes.cab.agenda')}</div>
        {m.items.length === 0 && (
          <Text size="sm" c="dimmed">
            {t('changes.cab.agendaEmpty')}
          </Text>
        )}
        {m.items.map((item) => (
          <AgendaItem key={item.id} meeting={m} item={item} />
        ))}
        {m.permissions.manage && m.state !== 'completed' && m.state !== 'cancelled' && <AddItem meeting={m} />}
      </Stack>
      <Minutes meeting={m} />
    </Stack>
  );
}

function MeetingActions({ meeting: m }: { meeting: Meeting }) {
  const { t } = useTranslation();
  const action = useMeetingAction(m);
  const open = m.state === 'planned' || m.state === 'in_progress';
  return (
    <Stack gap={6}>
      <Group gap="xs" wrap="wrap">
        {m.permissions.manage && open && (
          <Button variant={m.state === 'planned' ? 'default' : 'filled'} onClick={() => action.mutate({ path: '/collect' })} loading={action.isPending}>
            {t('changes.cab.collect')}
          </Button>
        )}
        {m.permissions.start && m.state === 'planned' && (
          <Button onClick={() => action.mutate({ path: '/start' })} loading={action.isPending}>
            {t('changes.cab.start')}
          </Button>
        )}
        {m.permissions.complete && m.state === 'in_progress' && (
          <Button variant="default" onClick={() => action.mutate({ path: '/complete', body: { minutes: m.minutes } })} loading={action.isPending}>
            {t('changes.cab.complete')}
          </Button>
        )}
        {m.permissions.sendProtocol && m.state === 'completed' && (
          <Button onClick={() => action.mutate({ path: '/protocol' })} loading={action.isPending}>
            {m.protocolSentAt ? t('changes.cab.resendProtocol') : t('changes.cab.sendProtocol')}
          </Button>
        )}
        {m.permissions.manage && m.state === 'planned' && (
          <Button variant="default" c="var(--ei-danger)" onClick={() => action.mutate({ path: '/cancel' })}>
            {t('changes.cab.cancel')}
          </Button>
        )}
      </Group>
      {m.protocolSentAt && (
        <Text size="xs" c="dimmed">
          {t('changes.cab.protocolSent', { date: new Date(m.protocolSentAt).toLocaleString() })}
        </Text>
      )}
      <ErrorLine error={action.error} />
    </Stack>
  );
}

function AgendaItem({ meeting: m, item }: { meeting: Meeting; item: MeetingItemView }) {
  const { t, i18n } = useTranslation();
  const action = useMeetingAction(m);
  const [comment, setComment] = useState('');
  const c = item.change;
  const decided = item.decision !== 'pending';
  const tally = (k: string) => item.tally?.[k] ?? 0;
  return (
    <div className={classes.agendaItem} data-decision={item.decision}>
      <Stack gap={8}>
        <Group justify="space-between" wrap="wrap" align="flex-start">
          <div style={{ minWidth: 0 }}>
            <Group gap={6} wrap="wrap">
              <Text fw={700}>{item.seq}.</Text>
              <ChangeNumber number={c.number} />
              <TypeBadge type={c.type} />
              <StateBadge state={c.state} />
              <RiskBadge level={c.riskLevel} />
            </Group>
            <Anchor component={Link} to={`/agent/changes/${c.id}`} fw={600} c="var(--ei-text)">
              {c.title}
            </Anchor>
            <Text size="xs" c="dimmed">
              {t('changes.columns.window')}: {formatWindow(c, i18n.language)}
            </Text>
          </div>
          <Group gap="xs">
            <span className={classes.tally} aria-label={t('changes.cab.tallyAria', { approve: tally('approve'), reject: tally('reject'), abstain: tally('abstain') })}>
              <span>✓ {tally('approve')}</span>
              <span>✕ {tally('reject')}</span>
              <span>○ {tally('abstain')}</span>
            </span>
            {decided && (
              <Badge variant="light" color={item.decision === 'approved' ? 'green' : item.decision === 'rejected' ? 'red' : 'violet'}>
                {t(`changes.cab.decision.${item.decision}`)}
              </Badge>
            )}
            {m.permissions.manage && !decided && m.state !== 'completed' && (
              <ActionIcon variant="subtle" color="red" aria-label={t('changes.cab.removeItem')} onClick={() => action.mutate({ path: `/items/${item.id}`, method: 'DELETE' })}>
                <IconTrash size={16} />
              </ActionIcon>
            )}
          </Group>
        </Group>
        {item.votes.length > 0 && (
          <Stack gap={2}>
            {item.votes.map((v) => (
              <Group key={v.voterId} gap={6} wrap="nowrap">
                <Person id={v.voterId} />
                <Text size="sm" c="dimmed">
                  — {t(`changes.cab.vote.${v.vote}`)}
                  {v.comment ? `: ${v.comment}` : ''}
                </Text>
              </Group>
            ))}
          </Stack>
        )}
        {item.comment && (
          <Text size="sm">
            {t('changes.cab.remarks')}: {item.comment}
          </Text>
        )}
        {(item.canVote || item.canDecide) && !decided && (
          <Stack gap={6}>
            <Textarea placeholder={t('changes.cab.commentPlaceholder')} aria-label={t('changes.cab.remarks')} autosize minRows={1} value={comment} onChange={(e) => setComment(e.currentTarget.value)} />
            <Group gap="xs" wrap="wrap">
              {item.canVote &&
                (['approve', 'reject', 'abstain'] as const).map((v) => (
                  <Button
                    key={v}
                    size="xs"
                    variant={item.myVote === v ? 'filled' : 'default'}
                    onClick={() => action.mutate({ path: `/items/${item.id}/vote`, body: { vote: v, comment: comment.trim() || undefined } })}
                  >
                    {t(`changes.cab.vote.${v}`)}
                  </Button>
                ))}
              {item.canDecide && m.state === 'in_progress' && (
                <>
                  <Text size="xs" c="dimmed" mx={4}>
                    {t('changes.cab.decisionLabel')}
                  </Text>
                  <Button size="xs" color="green" onClick={() => action.mutate({ path: `/items/${item.id}/decision`, body: { decision: 'approve', comment: comment.trim() || undefined } })}>
                    {t('changes.cab.approve')}
                  </Button>
                  <Button size="xs" variant="default" c="var(--ei-danger)" disabled={!comment.trim()} onClick={() => action.mutate({ path: `/items/${item.id}/decision`, body: { decision: 'reject', comment: comment.trim() } })}>
                    {t('changes.cab.reject')}
                  </Button>
                  <Button size="xs" variant="default" onClick={() => action.mutate({ path: `/items/${item.id}/decision`, body: { decision: 'defer', comment: comment.trim() || undefined } })}>
                    {t('changes.cab.defer')}
                  </Button>
                </>
              )}
            </Group>
          </Stack>
        )}
        <ErrorLine error={action.error} />
      </Stack>
    </div>
  );
}

/** Добавить изменение, ждущее CAB, вручную (обычно повестку собирает кнопка «Собрать повестку»). */
function AddItem({ meeting: m }: { meeting: Meeting }) {
  const { t } = useTranslation();
  const action = useMeetingAction(m);
  const waiting = useChanges({ state: 'approval' });
  const [changeId, setChangeId] = useState<string | null>(null);
  const onAgenda = new Set(m.items.map((i) => i.change.id));
  const options = (waiting.data?.pages.flatMap((p) => p.items) ?? [])
    .filter((c) => !onAgenda.has(c.id))
    .map((c) => ({ value: c.id, label: `${c.number} ${c.title}` }));
  return (
    <Group gap="xs" align="flex-end">
      <Select label={t('changes.cab.addItem')} placeholder={t('changes.cab.addItemHint')} searchable clearable data={options} value={changeId} onChange={setChangeId} w={420} />
      <Button
        variant="default"
        disabled={!changeId}
        loading={action.isPending}
        onClick={() => action.mutate({ path: '/items', body: { changeId } }, { onSuccess: () => setChangeId(null) })}
      >
        {t('changes.cab.add')}
      </Button>
      <ErrorLine error={action.error} />
    </Group>
  );
}

function Minutes({ meeting: m }: { meeting: Meeting }) {
  const { t } = useTranslation();
  const action = useMeetingAction(m);
  const [minutes, setMinutes] = useState(m.minutes ?? '');
  const editable = m.permissions.manage && m.state !== 'cancelled';
  return (
    <Stack gap="xs">
      <div className={classes.sectionLabel}>{t('changes.cab.minutes')}</div>
      {editable ? (
        <>
          <Textarea autosize minRows={4} value={minutes} onChange={(e) => setMinutes(e.currentTarget.value)} aria-label={t('changes.cab.minutes')} />
          <Group justify="flex-end">
            <Button variant="default" loading={action.isPending} onClick={() => action.mutate({ path: '', method: 'PATCH', body: { minutes } })}>
              {t('changes.actions.save')}
            </Button>
          </Group>
          <ErrorLine error={action.error} />
        </>
      ) : (
        <Text size="sm" style={{ whiteSpace: 'pre-wrap' }}>
          {m.minutes || '—'}
        </Text>
      )}
    </Stack>
  );
}
