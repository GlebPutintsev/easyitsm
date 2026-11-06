/** Даты на портале: коротко и по-человечески, без своих словарей — через Intl. */

const MIN = 60_000;
const HOUR = 60 * MIN;
const DAY = 24 * HOUR;

/** «только что», «15 минут назад», «3 часа назад», «вчера», «5 дней назад», дальше — дата. */
export function relativeTime(iso: string, locale: string, now = Date.now()): string {
  const at = new Date(iso).getTime();
  const diff = at - now;
  const abs = Math.abs(diff);
  const rtf = new Intl.RelativeTimeFormat(locale, { numeric: 'auto' });
  if (abs < MIN) return rtf.format(0, 'second');
  if (abs < HOUR) return rtf.format(Math.round(diff / MIN), 'minute');
  if (abs < DAY) return rtf.format(Math.round(diff / HOUR), 'hour');
  if (abs < 7 * DAY) return rtf.format(Math.round(diff / DAY), 'day');
  return new Intl.DateTimeFormat(locale, { day: 'numeric', month: 'short' }).format(at);
}

/** Дата и время для подписи срока: «8 нояб., 10:12»; сегодня — только время. */
export function shortDateTime(iso: string, locale: string, now = Date.now()): string {
  const at = new Date(iso);
  const sameDay = new Date(now).toDateString() === at.toDateString();
  return new Intl.DateTimeFormat(
    locale,
    sameDay ? { hour: '2-digit', minute: '2-digit' } : { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' },
  ).format(at);
}

export function dateTime(iso: string, locale: string): string {
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(iso));
}
