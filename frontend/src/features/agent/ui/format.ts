import type { TFunction } from 'i18next';

const MIN = 60_000;
const HOUR = 60 * MIN;

/** Короткая длительность: «45 мин», «3 ч 20 мин», «2 д 4 ч». */
export function formatDuration(ms: number, t: TFunction): string {
  const totalMin = Math.max(1, Math.round(Math.abs(ms) / MIN));
  if (totalMin < 60) return t('workspace.time.min', { count: totalMin });
  if (totalMin < 24 * 60) {
    const h = Math.floor(totalMin / 60);
    const m = totalMin % 60;
    return m ? `${t('workspace.time.h', { count: h })} ${t('workspace.time.min', { count: m })}` : t('workspace.time.h', { count: h });
  }
  const totalH = Math.round(totalMin / 60);
  const d = Math.floor(totalH / 24);
  const h = totalH % 24;
  return h ? `${t('workspace.time.d', { count: d })} ${t('workspace.time.h', { count: h })}` : t('workspace.time.d', { count: d });
}

/** «5 мин назад», «вчера в 14:20», «3 нояб., 09:12». */
export function formatRelative(iso: string, now: number, locale: string, t: TFunction): string {
  const at = new Date(iso).getTime();
  const diff = now - at;
  if (diff < MIN) return t('workspace.time.justNow');
  if (diff < HOUR) return t('workspace.time.ago', { value: t('workspace.time.min', { count: Math.round(diff / MIN) }) });
  if (diff < 12 * HOUR) return t('workspace.time.ago', { value: t('workspace.time.h', { count: Math.floor(diff / HOUR) }) });
  return new Intl.DateTimeFormat(locale, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }).format(at);
}

export function formatDateTime(iso: string, locale: string): string {
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(iso));
}
