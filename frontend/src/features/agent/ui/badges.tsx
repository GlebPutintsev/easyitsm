import { Tooltip } from '@mantine/core';
import {
  IconBug,
  IconCheckbox,
  IconFlame,
  IconGitPullRequest,
  IconLock,
  IconShoppingCart,
  IconSquareCheck,
} from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { SlaTimer as SharedSlaTimer } from '@/shared/ui/SlaTimer';
import { PriorityBadge as SharedPriorityBadge, StatusBadge as SharedStatusBadge } from '@/shared/ui/TicketBadges';
import type { Priority, SlaTimer, StatusRef, TicketType } from '../api/model';
import { formatDateTime } from './format';

/**
 * Метки заявки рабочего места — тонкие обёртки над дизайн-системой «Ясность» (shared/ui):
 * цвета, формы и тексты общие для всего продукта, здесь только перевод из UI-модели.
 */
export function PriorityBadge({ priority, variant }: { priority: Priority; variant?: 'short' | 'full' }) {
  return <SharedPriorityBadge priority={priority} variant={variant} />;
}

export function StatusBadge({ status }: { status: StatusRef }) {
  return <SharedStatusBadge category={status.category} label={status.name} />;
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
      <Icon size={size} stroke={1.6} aria-label={t(`workspace.type.${type}`)} color="var(--ei-text-3)" />
    </Tooltip>
  );
}

export function ConfidentialIcon() {
  const { t } = useTranslation();
  return (
    <Tooltip label={t('workspace.confidential')}>
      <IconLock size={14} aria-label={t('workspace.confidential')} color="var(--ei-danger)" />
    </Tooltip>
  );
}

/**
 * Таймер SLA на общем SlaTimer: после 75% цели оранжевый, после срока красный «−0:42», пауза фиолетовая.
 * Отсчёт цели — от создания заявки (startAt), пока модуль sla не отдаёт начало сам.
 * Выполненную цель общий таймер не показывает, поэтому «Выполнено» рисуется здесь же в тех же токенах.
 */
export function SlaBadge({ timer, now, startAt, withKind = false }: { timer: SlaTimer | null; now: number; startAt?: string; withKind?: boolean }) {
  const { t, i18n } = useTranslation();
  if (!timer) return <SharedSlaTimer dueAt={null} now={now} />;
  const kind = t(`workspace.sla.kind.${timer.kind}`);
  return (
    <Tooltip label={`${kind} · ${t('workspace.sla.due')} ${formatDateTime(timer.dueAt, i18n.language)}`} openDelay={300}>
      <span className="ew-sla">
        {withKind && <span className="ew-sla-kind">{kind}</span>}
        {timer.state === 'met' ? (
          <span className="ew-sla-met">
            <IconCheckbox size={14} stroke={2} aria-hidden />
            {t('workspace.sla.met')}
          </span>
        ) : (
          <SharedSlaTimer dueAt={timer.dueAt} startAt={startAt} paused={timer.state === 'paused'} now={now} />
        )}
      </span>
    </Tooltip>
  );
}
