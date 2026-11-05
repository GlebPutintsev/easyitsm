import { Badge } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import type { TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@/shared/api/client';
import type { ArticleStatus, Audience } from './api';

export const statusColor: Record<ArticleStatus, string> = {
  draft: 'gray',
  review: 'yellow',
  published: 'green',
  review_due: 'orange',
  archived: 'dark',
};

export function StatusBadge({ status }: { status: ArticleStatus }) {
  const { t } = useTranslation();
  return (
    <Badge color={statusColor[status]} variant="light" style={{ flexShrink: 0 }}>
      {t(`kb.status.${status}`)}
    </Badge>
  );
}

export function AudienceBadge({ audience }: { audience: Audience }) {
  const { t } = useTranslation();
  return (
    <Badge color="blue" variant="outline">
      {t(`kb.audience.${audience}`)}
    </Badge>
  );
}

/** Ошибка действия: текст с сервера и код трассировки (UI-06). */
export function notifyError(t: TFunction, error: unknown) {
  const message = error instanceof ApiError ? error.message : t('states.errorText');
  const trace = error instanceof ApiError && error.traceId ? ` · ${t('states.traceId')}: ${error.traceId}` : '';
  notifications.show({ color: 'red', title: t('kb.errors.actionFailed'), message: message + trace });
}

export function notifyDone(message: string) {
  notifications.show({ color: 'green', message });
}

export function useDateFormat() {
  const { i18n } = useTranslation();
  const date = new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium' });
  const dateTime = new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeStyle: 'short' });
  const time = new Intl.DateTimeFormat(i18n.language, { timeStyle: 'short' });
  return {
    date: (iso: string | null | undefined) => (iso ? date.format(new Date(iso)) : '—'),
    dateTime: (iso: string | null | undefined) => (iso ? dateTime.format(new Date(iso)) : '—'),
    time: (d: Date) => time.format(d),
  };
}

/** Дата через N месяцев в формате поля type="date". */
export function monthsFromNow(months: number): string {
  const d = new Date();
  d.setMonth(d.getMonth() + months);
  return d.toISOString().slice(0, 10);
}

/** Дата из поля type="date" как конец дня UTC, чтобы срок не наступал раньше выбранного дня. */
export function dateInputToIso(value: string): string | undefined {
  return value ? `${value}T23:59:59Z` : undefined;
}
