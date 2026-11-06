import type { StatusCategory } from '@/shared/ui/TicketBadges';
import type { Change, ChangeState, Phase, RiskLevel, Step } from './api';

/** Категория статуса заявки для цвета метки: та же таблица, что в контракте изменений. */
export const stateCategory: Record<ChangeState, StatusCategory> = {
  draft: 'draft',
  assessment: 'new',
  approval: 'approval',
  scheduled: 'assigned',
  implementation: 'in_progress',
  verification: 'in_progress',
  review: 'resolved',
  closed: 'closed',
  rejected: 'cancelled',
  cancelled: 'cancelled',
};

export const STATES: ChangeState[] = [
  'draft',
  'assessment',
  'approval',
  'scheduled',
  'implementation',
  'verification',
  'review',
  'closed',
  'rejected',
  'cancelled',
];

export const ACTIVE_STATES: ChangeState[] = ['assessment', 'approval', 'scheduled', 'implementation', 'verification', 'review'];

export const PHASES: Phase[] = ['prepare', 'implement', 'verify', 'rollback'];

export const riskTone: Record<RiskLevel, 'ok' | 'warn' | 'danger'> = { low: 'ok', medium: 'warn', high: 'danger' };

/** Дата и время в часовом поясе изменения: окно работ согласуют по местному времени площадки. */
export function formatInZone(iso: string | null | undefined, lang: string, timeZone?: string): string {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleString(lang, { dateStyle: 'short', timeStyle: 'short', timeZone });
  } catch {
    return new Date(iso).toLocaleString(lang, { dateStyle: 'short', timeStyle: 'short' });
  }
}

export function formatWindow(c: Pick<Change, 'windowStart' | 'windowEnd'> & { timeZone?: string }, lang: string): string {
  if (!c.windowStart || !c.windowEnd) return '—';
  const start = formatInZone(c.windowStart, lang, c.timeZone);
  const sameDay = new Date(c.windowStart).toDateString() === new Date(c.windowEnd).toDateString();
  const end = sameDay
    ? new Date(c.windowEnd).toLocaleTimeString(lang, { timeStyle: 'short', timeZone: c.timeZone })
    : formatInZone(c.windowEnd, lang, c.timeZone);
  return `${start} – ${end}`;
}

/** 95 → «1 ч 35 мин». */
export function minutesLabel(m: number | null | undefined, t: (k: string, o?: Record<string, unknown>) => string): string {
  if (m == null) return '—';
  const h = Math.floor(m / 60);
  const rest = m % 60;
  if (h && rest) return t('changes.duration.hm', { h, m: rest });
  if (h) return t('changes.duration.h', { h });
  return t('changes.duration.m', { m: rest });
}

/** Значение для поля datetime-local в часовом поясе браузера. */
export function toLocalInput(iso: string | null | undefined): string {
  if (!iso) return '';
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function fromLocalInput(v: string): string | null {
  if (!v) return null;
  const d = new Date(v);
  return Number.isNaN(d.getTime()) ? null : d.toISOString();
}

export function stepsOf(steps: Step[], phase: Phase): Step[] {
  return steps.filter((s) => s.phase === phase).sort((a, b) => a.seq - b.seq);
}

/** Шаги внедрения без отметки: пока они есть, на проверку не перейти. */
export function pendingImplementSteps(steps: Step[]): number {
  return steps.filter((s) => s.phase === 'implement' && s.status === 'pending').length;
}

/** Первый день месяца и первый день следующей сетки календаря (с понедельника). */
export function monthGrid(anchor: Date): { from: Date; to: Date; days: Date[] } {
  const first = new Date(anchor.getFullYear(), anchor.getMonth(), 1);
  const offset = (first.getDay() + 6) % 7;
  const from = new Date(first);
  from.setDate(first.getDate() - offset);
  const days: Date[] = [];
  for (let i = 0; i < 42; i++) {
    const d = new Date(from);
    d.setDate(from.getDate() + i);
    days.push(d);
  }
  const to = new Date(from);
  to.setDate(from.getDate() + 42);
  return { from, to, days };
}

export function weekGrid(anchor: Date): { from: Date; to: Date; days: Date[] } {
  const from = new Date(anchor.getFullYear(), anchor.getMonth(), anchor.getDate());
  from.setDate(from.getDate() - ((from.getDay() + 6) % 7));
  const days: Date[] = [];
  for (let i = 0; i < 7; i++) {
    const d = new Date(from);
    d.setDate(from.getDate() + i);
    days.push(d);
  }
  const to = new Date(from);
  to.setDate(from.getDate() + 7);
  return { from, to, days };
}

/** Пересекается ли интервал [start, end) с днём (по времени браузера). */
export function overlapsDay(start: string | null, end: string | null, day: Date): boolean {
  if (!start || !end) return false;
  const dayStart = new Date(day.getFullYear(), day.getMonth(), day.getDate()).getTime();
  const dayEnd = dayStart + 24 * 3600 * 1000;
  return new Date(start).getTime() < dayEnd && new Date(end).getTime() > dayStart;
}
