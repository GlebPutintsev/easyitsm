import { Badge, Group, Stack, Text, Title } from '@mantine/core';
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, NavLink } from 'react-router';
import i18n from '@/shared/i18n';
import type { CiCriticality, CiLayer, CiStatus } from './api';
import './i18n';
import classes from './cmdb.module.css';

export type Tone = 'accent' | 'ok' | 'warn' | 'wait' | 'danger' | 'neutral';

/** Метка с тоном «Адмирала»: цвет всегда дублирован словом. */
export function ToneBadge({ tone, children }: { tone: Tone; children: ReactNode }) {
  return (
    <Badge variant="light" size="md" className={`${classes.badge} ${classes[`tone_${tone}`]}`}>
      {children}
    </Badge>
  );
}

const STATUS_TONE: Record<CiStatus, Tone> = { planned: 'wait', active: 'ok', maintenance: 'warn', retired: 'neutral' };
const CRITICALITY_TONE: Record<CiCriticality, Tone> = { low: 'neutral', medium: 'accent', high: 'warn', critical: 'danger' };

export function CiStatusBadge({ status }: { status: CiStatus }) {
  const { t } = useTranslation();
  return <ToneBadge tone={STATUS_TONE[status]}>{t(`cmdb.status.${status}`)}</ToneBadge>;
}

export function CriticalityBadge({ value }: { value: CiCriticality }) {
  const { t } = useTranslation();
  return <ToneBadge tone={CRITICALITY_TONE[value]}>{t(`cmdb.criticality.${value}`)}</ToneBadge>;
}

export function LayerText({ layer }: { layer: CiLayer }) {
  const { t } = useTranslation();
  return <>{t(`cmdb.layer.${layer}`)}</>;
}

export function CiLink({ id, name }: { id: string; name: string }) {
  return (
    <Link to={`/agent/cmdb/${id}`} className={classes.ciLink}>
      {name}
    </Link>
  );
}

const TABS = [
  { key: 'cis', to: '/agent/cmdb', end: true },
  { key: 'reconciliation', to: '/agent/cmdb/reconciliation', end: false },
  { key: 'quality', to: '/agent/cmdb/quality', end: false },
  { key: 'sources', to: '/agent/cmdb/sources', end: false },
] as const;

/** Шапка раздела: заголовок, подзаголовок, действие справа и вкладки. */
export function CmdbHeader({ action }: { action?: ReactNode }) {
  const { t } = useTranslation();
  return (
    <Stack gap="sm">
      <Group justify="space-between" align="flex-start" wrap="wrap">
        <div>
          <Title order={1} size="h2">
            {t('cmdb.title')}
          </Title>
          <Text size="sm" c="var(--ei-text-3)">
            {t('cmdb.subtitle')}
          </Text>
        </div>
        {action}
      </Group>
      <nav className={classes.tabs} aria-label={t('cmdb.title')}>
        {TABS.map((tab) => (
          <NavLink
            key={tab.key}
            to={tab.to}
            end={tab.end}
            className={({ isActive }) => `${classes.tab} ${isActive ? classes.tabActive : ''}`}
          >
            {t(`cmdb.tabs.${tab.key}`)}
          </NavLink>
        ))}
      </nav>
    </Stack>
  );
}

/** Пары «подпись — значение» карточки. */
export function Fields({ items }: { items: [string, ReactNode][] }) {
  return (
    <dl className={classes.fields}>
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

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleString(i18n.language, {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

/** Источник значения: manual — «вручную», иначе код источника. */
export function sourceLabel(source: string | null | undefined): string {
  if (!source || source === 'manual') return i18n.t('cmdb.card.manual');
  return source;
}
