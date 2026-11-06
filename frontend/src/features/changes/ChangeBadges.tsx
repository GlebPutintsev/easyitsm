import { useTranslation } from 'react-i18next';
import { StatusBadge } from '@/shared/ui/TicketBadges';
import type { ChangeState, ChangeType, RiskLevel } from './api';
import classes from './changes.module.css';
import { riskTone, stateCategory } from './model';

export function StateBadge({ state }: { state: ChangeState }) {
  const { t } = useTranslation();
  return <StatusBadge category={stateCategory[state]} label={t(`changes.state.${state}`)} />;
}

export function TypeBadge({ type }: { type: ChangeType }) {
  const { t } = useTranslation();
  const cls = type === 'emergency' ? classes.emergency : type === 'standard' ? classes.ok : classes.outline;
  return <span className={`${classes.badge} ${cls}`}>{t(`changes.type.${type}`)}</span>;
}

/** Уровень риска: цвет дублируется словом («Риск: высокий»). */
export function RiskBadge({ level }: { level: RiskLevel | null | undefined }) {
  const { t } = useTranslation();
  if (!level) {
    return <span className={`${classes.badge} ${classes.neutral}`}>{t('changes.risk.none')}</span>;
  }
  return (
    <span className={`${classes.badge} ${classes[riskTone[level]]}`} aria-label={t('changes.risk.aria', { level: t(`changes.risk.level.${level}`) })}>
      {t(`changes.risk.level.${level}`)}
    </span>
  );
}

export function ChangeNumber({ number }: { number: string }) {
  return <span className={classes.number}>{number}</span>;
}
