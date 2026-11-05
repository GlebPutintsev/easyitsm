import { IconAlertCircle, IconAlertTriangle, IconClock, IconPlayerPause } from '@tabler/icons-react';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import classes from './ticketTokens.module.css';

export type SlaState = 'ok' | 'warn' | 'breached' | 'paused' | 'none';

/** Порог, после которого таймер становится оранжевым (SLA-03: пороги 50/75/90). */
export const SLA_WARN_FRACTION = 0.75;

interface SlaInput {
  dueAt: string | Date | null | undefined;
  /** Начало отсчёта цели. Нужно, чтобы показать порог 75%. Без него — только «нарушено» или «в норме». */
  startAt?: string | Date | null;
  paused?: boolean;
  now: number;
}

export function slaState({ dueAt, startAt, paused, now }: SlaInput): SlaState {
  if (paused) return 'paused';
  if (!dueAt) return 'none';
  const due = new Date(dueAt).getTime();
  if (now > due) return 'breached';
  if (startAt) {
    const start = new Date(startAt).getTime();
    if (due > start && (now - start) / (due - start) >= SLA_WARN_FRACTION) return 'warn';
  }
  return 'ok';
}

/** Остаток в виде «Ч:ММ»; от суток и больше — в днях. */
export function formatSlaRemaining(ms: number): { text: string; days?: number } {
  const abs = Math.abs(ms);
  const minutes = Math.floor(abs / 60_000);
  if (minutes >= 24 * 60) return { text: '', days: Math.floor(minutes / (24 * 60)) };
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return { text: `${h}:${String(m).padStart(2, '0')}` };
}

const icons = {
  ok: IconClock,
  warn: IconAlertTriangle,
  breached: IconAlertCircle,
  paused: IconPlayerPause,
  none: IconClock,
} as const;

const tone = {
  ok: classes.slaOk,
  warn: classes.slaWarn,
  breached: classes.slaBreached,
  paused: classes.slaPaused,
  none: classes.slaNone,
} as const;

interface Props extends Partial<Pick<SlaInput, 'startAt' | 'paused'>> {
  dueAt: SlaInput['dueAt'];
  /** Для тестов и сторибуков. Без него таймер берёт текущее время и обновляется раз в 30 секунд. */
  now?: number;
}

/** Таймер SLA для очереди и карточки: обычный, после 75% оранжевый, нарушение красное со знаком минус. */
export function SlaTimer({ dueAt, startAt, paused, now: fixedNow }: Props) {
  const { t } = useTranslation();
  const [tick, setTick] = useState(() => Date.now());
  useEffect(() => {
    if (fixedNow !== undefined) return;
    const id = setInterval(() => setTick(Date.now()), 30_000);
    return () => clearInterval(id);
  }, [fixedNow]);

  const now = fixedNow ?? tick;
  const state = slaState({ dueAt, startAt, paused, now });
  const Icon = icons[state];

  let text: string;
  let label: string;
  if (state === 'paused') {
    text = t('sla.paused');
    label = t('sla.pausedLabel');
  } else if (state === 'none' || !dueAt) {
    text = '—';
    label = t('sla.none');
  } else {
    const left = new Date(dueAt).getTime() - now;
    const { text: hm, days } = formatSlaRemaining(left);
    const value = days !== undefined ? t('sla.days', { count: days }) : hm;
    text = state === 'breached' ? `−${value}` : value;
    label = state === 'breached' ? t('sla.breachedLabel', { value }) : t('sla.remainingLabel', { value });
  }

  return (
    <span className={`${classes.sla} ${tone[state]}`} data-state={state} aria-label={label} title={label}>
      <Icon size={14} stroke={2} aria-hidden="true" />
      {text}
    </span>
  );
}
