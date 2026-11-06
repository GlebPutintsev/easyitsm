import type { TFunction } from 'i18next';
import { formatDuration } from '@/features/agent/ui/format';
import type { ColumnType } from './api';

/** Значение показателя как на экране: «1 204», «93,4 %», «2 ч 15 мин», «4,25». Пусто — «—». */
export function formatValue(value: unknown, type: ColumnType | undefined, locale: string, t: TFunction): string {
  if (value === null || value === undefined || value === '') return '—';
  if (typeof value !== 'number') {
    if (type === 'date' && typeof value === 'string') return formatDay(value, locale);
    return String(value);
  }
  switch (type) {
    case 'percent':
      return `${new Intl.NumberFormat(locale, { maximumFractionDigits: 1 }).format(value)} %`;
    case 'duration':
      return formatDuration(value * 1000, t);
    case 'score':
      return new Intl.NumberFormat(locale, { minimumFractionDigits: 1, maximumFractionDigits: 2 }).format(value);
    default:
      return new Intl.NumberFormat(locale).format(value);
  }
}

/** «10 янв.» для точки графика. */
export function formatDay(iso: string, locale: string): string {
  const [y = 1970, m = 1, d = 1] = iso.split('-').map(Number);
  return new Intl.DateTimeFormat(locale, { day: 'numeric', month: 'short' }).format(new Date(y, m - 1, d));
}

/** Даты периодов в формате ГГГГ-ММ-ДД по местному времени. */
export function isoDay(date: Date): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

export type PeriodPreset = 'last7' | 'last30' | 'last90' | 'thisMonth' | 'prevMonth' | 'last365';

export function presetRange(preset: PeriodPreset, today = new Date()): { from: string; to: string } {
  const end = new Date(today.getFullYear(), today.getMonth(), today.getDate());
  const minus = (days: number) => new Date(end.getFullYear(), end.getMonth(), end.getDate() - days);
  switch (preset) {
    case 'last7':
      return { from: isoDay(minus(6)), to: isoDay(end) };
    case 'last90':
      return { from: isoDay(minus(89)), to: isoDay(end) };
    case 'last365':
      return { from: isoDay(minus(364)), to: isoDay(end) };
    case 'thisMonth':
      return { from: isoDay(new Date(end.getFullYear(), end.getMonth(), 1)), to: isoDay(end) };
    case 'prevMonth':
      return {
        from: isoDay(new Date(end.getFullYear(), end.getMonth() - 1, 1)),
        to: isoDay(new Date(end.getFullYear(), end.getMonth(), 0)),
      };
    default:
      return { from: isoDay(minus(29)), to: isoDay(end) };
  }
}

/** Какой пресет соответствует периоду, иначе «custom». */
export function presetOf(from: string, to: string, today = new Date()): PeriodPreset | 'custom' {
  const presets: PeriodPreset[] = ['last7', 'last30', 'last90', 'thisMonth', 'prevMonth', 'last365'];
  return presets.find((p) => {
    const r = presetRange(p, today);
    return r.from === from && r.to === to;
  }) ?? 'custom';
}

/** «Красивый» верх шкалы: 1, 2, 2.5, 5 × 10^n, не меньше максимума. */
export function niceMax(max: number): number {
  if (max <= 0) return 4;
  const exp = Math.pow(10, Math.floor(Math.log10(max)));
  for (const step of [1, 2, 2.5, 5, 10]) {
    if (step * exp >= max) return step * exp;
  }
  return 10 * exp;
}
