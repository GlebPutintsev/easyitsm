import { Anchor, Group, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { PriorityBadge, StatusBadge } from '@/shared/ui/TicketBadges';
import type { LinkedTicket } from './api';
import classes from './problems.module.css';

/** Ссылка на связанную заявку: без доступа — только номер. */
export function LinkedTicketRow({ ticket }: { ticket: LinkedTicket }) {
  const { t, i18n } = useTranslation();
  if (!ticket.visible || !ticket.number) {
    return (
      <Group gap={8} wrap="nowrap">
        <Text className={classes.mono} c="dimmed">
          {ticket.number ?? '—'}
        </Text>
        <Text size="xs" c="dimmed">
          {t('problems.incidents.hidden')}
        </Text>
      </Group>
    );
  }
  return (
    <Group gap={10} wrap="nowrap" style={{ minWidth: 0 }}>
      {ticket.priority && <PriorityBadge priority={ticket.priority} />}
      <Anchor component={Link} to={`/agent/tickets/${ticket.number}`} className={classes.mono}>
        {ticket.number}
      </Anchor>
      <Text size="sm" truncate style={{ flex: 1, minWidth: 0 }}>
        {ticket.title}
      </Text>
      {ticket.statusCategory && <StatusBadge category={ticket.statusCategory} />}
      {ticket.createdAt && (
        <Text size="xs" c="dimmed" visibleFrom="sm">
          {new Date(ticket.createdAt).toLocaleDateString(i18n.language)}
        </Text>
      )}
    </Group>
  );
}

/** ISO-время → значение поля datetime-local в часовом поясе браузера и обратно. */
export function toLocalInput(iso: string | null): string {
  if (!iso) return '';
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function fromLocalInput(value: string): string | null {
  if (!value) return null;
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? null : d.toISOString();
}
