import { Badge, Group, Text, Tooltip, type MantineColor } from '@mantine/core';
import {
  IconAlertTriangle,
  IconBug,
  IconCheckbox,
  IconClockPause,
  IconFlame,
  IconGitPullRequest,
  IconLock,
  IconShoppingCart,
  IconSquareCheck,
} from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { slaRemainingMs, slaToneOf, type SlaTone } from '../api/domain';
import type { Priority, SlaTimer, StatusRef, TicketType } from '../api/model';
import { formatDateTime, formatDuration } from './format';

export const priorityColor: Record<Priority, MantineColor> = { P1: 'red', P2: 'orange', P3: 'yellow', P4: 'gray' };

export function PriorityBadge({ priority }: { priority: Priority }) {
  const { t } = useTranslation();
  return (
    <Tooltip label={t(`workspace.priority.${priority}`)} openDelay={400}>
      <Badge color={priorityColor[priority]} variant={priority === 'P1' ? 'filled' : 'light'} radius="sm" w={36} px={0}>
        {priority}
      </Badge>
    </Tooltip>
  );
}

const statusColor: Record<StatusRef['category'], MantineColor> = {
  draft: 'gray',
  new: 'blue',
  approval: 'yellow',
  assigned: 'indigo',
  in_progress: 'teal',
  pending: 'grape',
  resolved: 'green',
  closed: 'gray',
  cancelled: 'gray',
};

export function StatusBadge({ status }: { status: StatusRef }) {
  return (
    <Badge color={statusColor[status.category]} variant="light" radius="sm" style={{ textTransform: 'none' }}>
      {status.name}
    </Badge>
  );
}

const typeIcon: Record<TicketType, typeof IconBug> = {
  incident: IconFlame,
  request: IconShoppingCart,
  problem: IconBug,
  change: IconGitPullRequest,
  task: IconSquareCheck,
};

export function TypeIcon({ type, size = 16 }: { type: TicketType; size?: number }) {
  const { t } = useTranslation();
  const Icon = typeIcon[type];
  return (
    <Tooltip label={t(`workspace.type.${type}`)} openDelay={400}>
      <Icon size={size} stroke={1.6} aria-label={t(`workspace.type.${type}`)} color="var(--mantine-color-dimmed)" />
    </Tooltip>
  );
}

export function ConfidentialIcon() {
  const { t } = useTranslation();
  return (
    <Tooltip label={t('workspace.confidential')}>
      <IconLock size={14} aria-label={t('workspace.confidential')} color="var(--mantine-color-red-6)" />
    </Tooltip>
  );
}

const toneColor: Record<SlaTone, MantineColor> = { breached: 'red', risk: 'orange', ok: 'gray', paused: 'grape', met: 'green' };

/** Таймер SLA: оставшееся время, «Просрочено на …» или «Пауза». Цвет дублируется значком для дальтоников (UI-07). */
export function SlaBadge({ timer, now, withKind = false, compact = false }: { timer: SlaTimer | null; now: number; withKind?: boolean; compact?: boolean }) {
  const { t, i18n } = useTranslation();
  if (!timer) return <Text size="sm" c="dimmed">—</Text>;
  const tone = slaToneOf(timer, now);
  const left = slaRemainingMs(timer, now);
  const text =
    tone === 'met'
      ? t('workspace.sla.met')
      : tone === 'breached'
        ? compact
          ? `−${formatDuration(left, t)}`
          : t('workspace.sla.breached', { value: formatDuration(left, t) })
        : tone === 'paused' && !compact
          ? t('workspace.sla.paused', { value: formatDuration(left, t) })
          : formatDuration(left, t);
  const Icon = tone === 'breached' ? IconAlertTriangle : tone === 'paused' ? IconClockPause : tone === 'met' ? IconCheckbox : null;
  return (
    <Tooltip
      label={`${t(`workspace.sla.kind.${timer.kind}`)}${tone === 'breached' ? ` · ${t('workspace.sla.breached', { value: formatDuration(left, t) })}` : ''} · ${t('workspace.sla.due')} ${formatDateTime(timer.dueAt, i18n.language)}`}
      openDelay={300}
    >
      <Group gap={4} wrap="nowrap" c={tone === 'ok' ? undefined : toneColor[tone]}>
        {Icon && <Icon size={14} stroke={1.8} aria-hidden />}
        <Text size="sm" fw={tone === 'breached' || tone === 'risk' ? 600 : 400} c="inherit" style={{ whiteSpace: 'nowrap' }}>
          {withKind ? `${t(`workspace.sla.kind.${timer.kind}`)}: ` : ''}
          {text}
        </Text>
      </Group>
    </Tooltip>
  );
}
