import { notifications } from '@mantine/notifications';
import { IconAlertTriangle, IconArchive, IconClockHour4, IconInfoCircle } from '@tabler/icons-react';
import type { TFunction } from 'i18next';
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@/shared/api/client';
import type { ArticleStatus, Audience } from './api';
import './knowledge.css';

/**
 * Тон статуса статьи по DESIGN.md: черновик и архив нейтральные, проверка — «ожидание» (фиолетовый),
 * опубликована — ok, на пересмотре — warn. Цвет всегда дублируется словом.
 */
export const statusTone: Record<ArticleStatus, 'neutral' | 'wait' | 'ok' | 'warn'> = {
  draft: 'neutral',
  review: 'wait',
  published: 'ok',
  review_due: 'warn',
  archived: 'neutral',
};

export function StatusBadge({ status }: { status: ArticleStatus }) {
  const { t } = useTranslation();
  return (
    <span className="kb-badge" data-tone={statusTone[status]}>
      {t(`kb.status.${status}`)}
    </span>
  );
}

export function AudienceBadge({ audience }: { audience: Audience }) {
  const { t } = useTranslation();
  return (
    <span className="kb-badge" data-tone="outline">
      {t(`kb.audience.${audience}`)}
    </span>
  );
}

export type NoticeTone = 'warn' | 'danger' | 'wait' | 'accent' | 'neutral';

const noticeIcon: Record<NoticeTone, ReactNode> = {
  warn: <IconAlertTriangle size={20} aria-hidden />,
  danger: <IconAlertTriangle size={20} aria-hidden />,
  wait: <IconClockHour4 size={20} aria-hidden />,
  accent: <IconInfoCircle size={20} aria-hidden />,
  neutral: <IconArchive size={20} aria-hidden />,
};

/** Плашка над статьёй или редактором: мягкий фон токена, значок и текст (без цвета как единственного признака). */
export function Notice({ tone, title, children }: { tone: NoticeTone; title?: ReactNode; children?: ReactNode }) {
  return (
    <div className="kb-notice" data-tone={tone} role={tone === 'danger' ? 'alert' : 'status'}>
      {noticeIcon[tone]}
      <div style={{ minWidth: 0, flex: 1 }}>
        {title && <div className="kb-notice-title">{title}</div>}
        {children}
      </div>
    </div>
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
