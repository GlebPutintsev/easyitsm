import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { initials, usePersonName } from '@/shared/api/people';
import { StatusBadge } from '@/shared/ui/TicketBadges';
import type { StatusRef, Ticket } from '@/features/tickets/api';
import { relativeTime, shortDateTime } from './format';

/** Статус заявки: цвет по категории, название — как настроено у заказчика. */
export function TicketStatus({ status }: { status: StatusRef }) {
  return <StatusBadge category={status.category} label={status.name} />;
}

/** Подпись к заявке в коротком списке: что сейчас важно заявителю. */
export function TicketHint({ ticket, now: fixedNow }: { ticket: Ticket; now?: number }) {
  const { t, i18n } = useTranslation();
  const [mounted] = useState(() => Date.now());
  const now = fixedNow ?? mounted;
  const cat = ticket.status.category;
  if (cat === 'resolved') return <>{t('portal.hint.confirm')}</>;
  if (cat === 'pending') return <>{t('portal.hint.waitingYou')}</>;
  const due = ticket.dueAt ?? ticket.slaDueAt;
  if (due && (cat === 'new' || cat === 'assigned' || cat === 'in_progress') && new Date(due).getTime() > now) {
    return <>{t('portal.hint.due', { time: shortDateTime(due, i18n.language, now) })}</>;
  }
  return <>{relativeTime(ticket.updatedAt, i18n.language, now)}</>;
}

/** Аватар с инициалами; имя берётся из справочника людей. */
export function PersonAvatar({ id, name, tone }: { id?: string | null; name?: string; tone?: 'accent' }) {
  const fromDirectory = usePersonName(name ? null : id);
  const shown = name ?? fromDirectory;
  return (
    <span className="ep-avatar" data-size="sm" data-tone={tone} aria-hidden="true">
      {initials(shown)}
    </span>
  );
}

/** Имя человека из справочника; для текущего пользователя — «Вы». */
export function PersonName({ id, meId }: { id: string | null | undefined; meId?: string }) {
  const { t } = useTranslation();
  const name = usePersonName(id);
  if (id && meId && id === meId) return <>{t('people.you')}</>;
  return <>{name}</>;
}
