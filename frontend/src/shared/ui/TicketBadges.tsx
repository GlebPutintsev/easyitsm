import { useTranslation } from 'react-i18next';
import classes from './ticketTokens.module.css';

/** Категории статуса из контракта заявок (docs/api/tickets.yaml, StatusCategory). */
export type StatusCategory =
  | 'draft'
  | 'new'
  | 'approval'
  | 'assigned'
  | 'in_progress'
  | 'pending'
  | 'resolved'
  | 'closed'
  | 'cancelled';

export type Priority = 'P1' | 'P2' | 'P3' | 'P4';

export type StatusTone = 'accent' | 'warn' | 'wait' | 'ok' | 'neutral';

/** Цвет статуса задаётся категорией, а не названием: статусы у заказчика настраиваемые. */
export const statusTone: Record<StatusCategory, StatusTone> = {
  draft: 'neutral',
  new: 'accent',
  assigned: 'accent',
  approval: 'wait',
  in_progress: 'warn',
  pending: 'wait',
  resolved: 'ok',
  closed: 'neutral',
  cancelled: 'neutral',
};

interface StatusBadgeProps {
  category: StatusCategory;
  /** Название статуса у заказчика (StatusRef.name). Без него — название категории. */
  label?: string;
}

export function StatusBadge({ category, label }: StatusBadgeProps) {
  const { t } = useTranslation();
  const tone = statusTone[category];
  return (
    <span className={`${classes.badge} ${classes[tone]}`} data-tone={tone}>
      <span className={classes.dot} aria-hidden="true" />
      {label ?? t(`ticket.status.${category}`)}
    </span>
  );
}

interface PriorityBadgeProps {
  priority: Priority;
  /** short — «P1» для таблиц, full — «P1 Критический» для шапки карточки. */
  variant?: 'short' | 'full';
}

export function PriorityBadge({ priority, variant = 'short' }: PriorityBadgeProps) {
  const { t } = useTranslation();
  const name = t(`ticket.priority.${priority}`);
  const cls = classes[priority.toLowerCase()];
  return (
    <span className={`${classes.priority} ${cls}`} title={name} aria-label={`${priority} ${name}`}>
      {variant === 'full' ? `${priority} ${name}` : priority}
    </span>
  );
}
