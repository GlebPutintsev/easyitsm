import { Avatar, Badge, Group, Paper, SegmentedControl, Stack, Text, Tooltip } from '@mantine/core';
import { IconArrowRight, IconLock, IconPoint } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import { useActivity } from '../api/hooks';
import type { Activity, Ticket } from '../api/model';
import { formatDateTime, formatRelative } from '../ui/format';
import { Markdown } from '../ui/Markdown';
import { useNow } from '../ui/useNow';

type FeedFilter = 'all' | 'conversation' | 'notes' | 'history';

const matches: Record<FeedFilter, (a: Activity) => boolean> = {
  all: () => true,
  conversation: (a) => a.kind === 'reply',
  notes: (a) => a.kind === 'note',
  history: (a) => a.kind === 'system' || a.kind === 'change',
};

/** Лента активности (TCK-03): ответы, внутренние заметки, события и изменения полей. */
export function ActivityFeed({ ticket }: { ticket: Ticket }) {
  const { t } = useTranslation();
  const activity = useActivity(ticket.id);
  const [filter, setFilter] = useState<FeedFilter>('all');
  const items = (activity.data ?? []).filter(matches[filter]);
  const state = listStateOf(activity, items.length, filter !== 'all');

  return (
    <Stack gap="xs">
      <Group justify="space-between">
        <Text fw={600}>{t('workspace.feed.title')}</Text>
        <SegmentedControl
          size="xs"
          value={filter}
          onChange={(v) => setFilter(v as FeedFilter)}
          data={(['all', 'conversation', 'notes', 'history'] as const).map((f) => ({ value: f, label: t(`workspace.feed.filter.${f}`) }))}
        />
      </Group>
      {state ? (
        <ListState state={state} error={activity.error} onRetry={() => void activity.refetch()} onResetFilters={() => setFilter('all')} rows={3} />
      ) : (
        <Stack gap={10} role="feed" aria-label={t('workspace.feed.title')}>
          {items.map((a) => (
            <ActivityItem key={a.id} activity={a} requesterId={ticket.requester.id} />
          ))}
        </Stack>
      )}
    </Stack>
  );
}

function ActivityItem({ activity: a, requesterId }: { activity: Activity; requesterId: string }) {
  const { t, i18n } = useTranslation();
  const now = useNow(60_000);
  const when = (
    <Tooltip label={formatDateTime(a.createdAt, i18n.language)}>
      <Text size="xs" c="dimmed" component="time" dateTime={a.createdAt}>
        {formatRelative(a.createdAt, now, i18n.language, t)}
      </Text>
    </Tooltip>
  );

  if (a.kind === 'system' || a.kind === 'change') {
    return (
      <Group gap={6} wrap="nowrap" pl={6} className="ew-feed-event" align="flex-start">
        <IconPoint size={16} color="var(--mantine-color-dimmed)" style={{ flexShrink: 0, marginTop: 2 }} />
        <Text size="xs" c="dimmed" style={{ flex: 1 }}>
          {a.author && <strong>{a.author.name} </strong>}
          {a.kind === 'change' ? <ChangeText activity={a} /> : t(`workspace.feed.event.${a.event?.code ?? 'unknown'}`, {
                ...a.event,
                channel: typeof a.event?.channel === 'string' ? t(`workspace.channel.${a.event.channel}`) : '',
                defaultValue: a.event?.code ?? '',
              })}
        </Text>
        {when}
      </Group>
    );
  }

  const isNote = a.kind === 'note';
  const fromRequester = a.fromRequester || a.author?.id === requesterId;
  return (
    <Paper
      withBorder
      p="sm"
      radius="md"
      className={isNote ? 'ew-note' : fromRequester ? 'ew-reply-requester' : 'ew-reply-agent'}
      aria-label={t(isNote ? 'workspace.feed.noteLabel' : 'workspace.feed.replyLabel')}
    >
      <Group justify="space-between" mb={4} wrap="nowrap">
        <Group gap={8} wrap="nowrap">
          <Avatar name={a.author?.name ?? '?'} color="initials" size={26} radius="xl" />
          <Text size="sm" fw={600}>
            {a.author?.name ?? t('workspace.feed.system')}
          </Text>
          {isNote ? (
            <Badge size="xs" color="yellow" variant="light" leftSection={<IconLock size={10} />}>
              {t('workspace.feed.internal')}
            </Badge>
          ) : fromRequester ? (
            <Badge size="xs" color="blue" variant="light">
              {t('workspace.feed.requester')}
            </Badge>
          ) : (
            <Badge size="xs" color="gray" variant="light">
              {t('workspace.feed.public')}
            </Badge>
          )}
        </Group>
        {when}
      </Group>
      <Markdown text={a.body} />
    </Paper>
  );
}

function ChangeText({ activity }: { activity: Activity }) {
  const { t } = useTranslation();
  // Коды (high, in_progress) переводятся, имена групп и людей показываются как есть.
  const value = (v: string) => (/^[a-z_]+$/.test(v) ? t(`workspace.value.${v}`, { defaultValue: v }) : v);
  return (
    <>
      {(activity.changes ?? []).map((c, i) => (
        <span key={i}>
          {i > 0 && '; '}
          {/^[a-zA-Z_]+$/.test(c.field) ? t(`workspace.field.${c.field}`, { defaultValue: c.field }) : c.field}: {c.from ? <span className="ew-strike">{value(c.from)}</span> : '—'}{' '}
          <IconArrowRight size={10} style={{ verticalAlign: 'middle' }} /> <strong>{c.to ? value(c.to) : '—'}</strong>
        </span>
      ))}
    </>
  );
}
