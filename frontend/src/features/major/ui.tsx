import { Anchor, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { StatusBadge } from '@/shared/ui/TicketBadges';
import type { MajorState, TicketRef } from './api';
import classes from './major.module.css';

export function MajorStateBadge({ state }: { state: MajorState }) {
  const { t } = useTranslation();
  return (
    <span className={classes.state} data-state={state}>
      {t(`major.state.${state}`)}
    </span>
  );
}

/** Ссылка на заявку; невидимая текущему пользователю — только номер. */
export function TicketLink({ ticket }: { ticket: TicketRef }) {
  const { t } = useTranslation();
  if (!ticket.visible || !ticket.number) {
    return (
      <Text size="sm" c="dimmed">
        {ticket.number ?? t('major.hiddenTicket')}
      </Text>
    );
  }
  return (
    <Anchor component={Link} to={`/agent/tickets/${ticket.number}`} size="sm" style={{ display: 'inline-flex', gap: 8, alignItems: 'center', minWidth: 0 }}>
      <span className={classes.mono}>{ticket.number}</span>
      {ticket.statusCategory && <StatusBadge category={ticket.statusCategory} />}
      <Text span size="sm" truncate>
        {ticket.title}
      </Text>
    </Anchor>
  );
}

export function timeOf(iso: string, locale: string): string {
  return new Date(iso).toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' });
}

export function dateTimeOf(iso: string, locale: string): string {
  return new Date(iso).toLocaleString(locale, { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' });
}

/** Значение для input type=datetime-local в местном времени. */
export function toLocalInput(iso: string | null): string {
  if (!iso) return '';
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function fromLocalInput(value: string): string | null {
  return value ? new Date(value).toISOString() : null;
}
