import { Badge, Group, Stack, Text, Title } from '@mantine/core';
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { NavLink } from 'react-router';
import i18n from '@/shared/i18n';
import type { AssetState, ContractStatus, LicenseStatus } from './api';
import './i18n';
import classes from './assets.module.css';

export type Tone = 'accent' | 'ok' | 'warn' | 'wait' | 'danger' | 'neutral';

const STATE_TONE: Record<AssetState, Tone> = {
  in_stock: 'accent',
  in_use: 'ok',
  in_repair: 'warn',
  retired: 'neutral',
};

/** Метка с тоном «Адмирала»: цвет всегда дублирован словом. */
export function ToneBadge({ tone, children }: { tone: Tone; children: ReactNode }) {
  return (
    <Badge variant="light" size="md" className={`${classes.badge} ${classes[`tone_${tone}`]}`}>
      {children}
    </Badge>
  );
}

export function AssetStateBadge({ state }: { state: AssetState }) {
  const { t } = useTranslation();
  return <ToneBadge tone={STATE_TONE[state]}>{t(`assets.state.${state}`)}</ToneBadge>;
}

const TERM_TONE: Record<ContractStatus | LicenseStatus | 'overused', Tone> = {
  active: 'ok',
  expiring: 'warn',
  expired: 'danger',
  terminated: 'neutral',
  overused: 'danger',
};

export function TermBadge({ status, label }: { status: ContractStatus | LicenseStatus | 'overused'; label: string }) {
  return <ToneBadge tone={TERM_TONE[status]}>{label}</ToneBadge>;
}

const TABS = [
  { key: 'hardware', to: '/agent/assets', end: true },
  { key: 'licenses', to: '/agent/assets/licenses', end: false },
  { key: 'contracts', to: '/agent/assets/contracts', end: false },
  { key: 'directory', to: '/agent/assets/directory', end: false },
] as const;

/** Шапка раздела: заголовок, подзаголовок, действие справа и вкладки разделов учёта. */
export function AssetsHeader({ action }: { action?: ReactNode }) {
  const { t } = useTranslation();
  return (
    <Stack gap="sm">
      <Group justify="space-between" align="flex-start" wrap="wrap">
        <div>
          <Title order={1} size="h2">
            {t('assets.title')}
          </Title>
          <Text size="sm" c="var(--ei-text-3)">
            {t('assets.subtitle')}
          </Text>
        </div>
        {action}
      </Group>
      <nav className={classes.tabs} aria-label={t('assets.title')}>
        {TABS.map((tab) => (
          <NavLink
            key={tab.key}
            to={tab.to}
            end={tab.end}
            className={({ isActive }) => `${classes.tab} ${isActive ? classes.tabActive : ''}`}
          >
            {t(`assets.tabs.${tab.key}`)}
          </NavLink>
        ))}
      </nav>
    </Stack>
  );
}

export function formatDate(day: string | null | undefined): string {
  if (!day) return '—';
  const d = new Date(day.length === 10 ? `${day}T00:00:00` : day);
  return d.toLocaleDateString(i18n.language, { year: 'numeric', month: '2-digit', day: '2-digit' });
}

export function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString(i18n.language, {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export function formatMoney(amount: number | null | undefined, currency: string): string {
  if (amount == null) return '—';
  try {
    return new Intl.NumberFormat(i18n.language, { style: 'currency', currency, maximumFractionDigits: 2 }).format(amount);
  } catch {
    return `${amount} ${currency}`;
  }
}

/** Пары «подпись — значение» карточки. */
export function Fields({ items, compact = false }: { items: [string, ReactNode][]; compact?: boolean }) {
  return (
    <dl className={compact ? `${classes.fields} ${classes.fieldsCompact}` : classes.fields}>
      {items.map(([label, value]) => (
        <div key={label} style={{ display: 'contents' }}>
          <dt>{label}</dt>
          <dd>{value ?? '—'}</dd>
        </div>
      ))}
    </dl>
  );
}

export function SectionLabel({ children }: { children: ReactNode }) {
  return <div className={classes.sectionLabel}>{children}</div>;
}

/** Пусто в форме → не отправлять; очищенное существующее значение → в clear. */
export function patchOf<T extends Record<string, unknown>>(
  values: T,
  initial: Partial<Record<keyof T, unknown>> | null,
  clearable: (keyof T)[],
): T & { clear?: string[] } {
  const out: Record<string, unknown> = {};
  const clear: string[] = [];
  for (const [key, value] of Object.entries(values)) {
    const empty = value === '' || value === null || value === undefined;
    const before = initial?.[key as keyof T];
    if (!empty) {
      if (before !== value) out[key] = value;
    } else if (initial && before !== null && before !== undefined && before !== '' && clearable.includes(key as keyof T)) {
      clear.push(key);
    }
  }
  if (clear.length) out.clear = clear;
  return out as T & { clear?: string[] };
}
