import { Button, Group, Modal, Stack, Text } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { hasRole, useMe } from '@/features/auth/api';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { ListState } from '@/shared/ui/ListState';
import { type CiRef, type DuplicateGroup, type QualityIssue, useDuplicates, useMerge, useQuality, useQualityIssues } from './api';
import { CiLink, CiStatusBadge, CmdbHeader, SectionLabel } from './ui';
import classes from './cmdb.module.css';

type View = QualityIssue | 'duplicates';

/** «CMDB → Качество данных» (CFG-07): оценка, показатели и списки КЕ с замечаниями, слияние дубликатов. */
export function QualityPage() {
  const { t } = useTranslation();
  const quality = useQuality();
  const [view, setView] = useState<View>('incomplete');
  const q = quality.data;
  const tile = (key: View, value: number) => (
    <button type="button" className={`${classes.stat} ${view === key ? classes.statActive : ''}`} onClick={() => setView(key)} aria-pressed={view === key}>
      <div className={`${classes.statValue} ${value > 0 ? classes.statWarnValue : ''}`}>{value}</div>
      <div className={classes.statLabel}>{t(`cmdb.quality.${key === 'orphan' ? 'orphan' : key}`)}</div>
    </button>
  );
  return (
    <Stack gap="md" maw={1280}>
      <CmdbHeader />
      {quality.isPending ? (
        <ListState state="loading" rows={2} />
      ) : quality.isError || !q ? (
        <ListState state="error" error={quality.error} onRetry={() => quality.refetch()} />
      ) : (
        <div className={classes.stats}>
          <div className={classes.stat}>
            <div className={classes.score}>{q.score}</div>
            <div className={classes.scoreBar} style={{ width: '100%' }} aria-hidden>
              <div className={classes.scoreFill} style={{ width: `${q.score}%` }} />
            </div>
            <div className={classes.statLabel}>
              {t('cmdb.quality.score')} · {t('cmdb.quality.total')}: {q.total}
            </div>
          </div>
          {tile('incomplete', q.incomplete)}
          {tile('stale', q.stale)}
          {tile('orphan', q.orphans)}
          {tile('duplicates', q.duplicates)}
        </div>
      )}
      <Text size="sm" c="var(--ei-text-2)">
        {t(`cmdb.quality.${view === 'orphan' ? 'orphan' : view}Hint`)}
      </Text>
      {view === 'duplicates' ? <Duplicates /> : <Issues kind={view} />}
    </Stack>
  );
}

function Issues({ kind }: { kind: QualityIssue }) {
  const { t } = useTranslation();
  const issues = useQualityIssues(kind);
  if (issues.isPending) return <ListState state="loading" />;
  if (issues.isError) return <ListState state="error" error={issues.error} onRetry={() => issues.refetch()} />;
  if (issues.data.length === 0) return <ListState state="empty" title={t('cmdb.quality.empty')} />;
  return (
    <div className={classes.panel}>
      {issues.data.map((ci) => (
        <CiLine key={ci.id} ci={ci} />
      ))}
    </div>
  );
}

function CiLine({ ci, action }: { ci: CiRef; action?: React.ReactNode }) {
  return (
    <div className={classes.relationRow}>
      <div style={{ flex: 1, minWidth: 0 }}>
        <CiLink id={ci.id} name={ci.name} />
        <Text size="xs" className={classes.muted}>
          {ci.className}
        </Text>
      </div>
      <CiStatusBadge status={ci.status} />
      {action}
    </div>
  );
}

function Duplicates() {
  const { t } = useTranslation();
  const me = useMe();
  const canWrite = hasRole(me.data, 'CFG');
  const groups = useDuplicates();
  const [merging, setMerging] = useState<{ group: DuplicateGroup; keep: CiRef } | null>(null);
  if (groups.isPending) return <ListState state="loading" />;
  if (groups.isError) return <ListState state="error" error={groups.error} onRetry={() => groups.refetch()} />;
  if (groups.data.length === 0) return <ListState state="empty" title={t('cmdb.quality.empty')} />;
  return (
    <Stack gap="sm">
      {groups.data.map((g) => (
        <div key={`${g.key}:${g.value}`} className={classes.panel}>
          <SectionLabel>{t('cmdb.quality.groupBy', { key: g.key, value: g.value })}</SectionLabel>
          {g.cis.map((ci) => (
            <CiLine
              key={ci.id}
              ci={ci}
              action={
                canWrite && (
                  <Button size="xs" variant="default" onClick={() => setMerging({ group: g, keep: ci })}>
                    {t('cmdb.quality.keep')}
                  </Button>
                )
              }
            />
          ))}
        </div>
      ))}
      <MergeDialog value={merging} onClose={() => setMerging(null)} />
    </Stack>
  );
}

function MergeDialog({ value, onClose }: { value: { group: DuplicateGroup; keep: CiRef } | null; onClose: () => void }) {
  const { t } = useTranslation();
  const merge = useMerge();
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<unknown>(null);
  if (!value) return null;
  const others = value.group.cis.filter((c) => c.id !== value.keep.id);
  const run = async () => {
    setRunning(true);
    setError(null);
    try {
      for (const dup of others) {
        await merge.mutateAsync({ keepId: value.keep.id, mergedId: dup.id });
      }
      notifications.show({ message: t('cmdb.quality.merged'), color: 'teal' });
      onClose();
    } catch (e) {
      setError(e);
    } finally {
      setRunning(false);
    }
  };
  return (
    <Modal opened onClose={onClose} title={t('cmdb.quality.mergeInto', { name: value.keep.name })}>
      <Stack gap="sm">
        <Text size="sm">{t('cmdb.quality.mergeConfirm', { count: others.length, name: value.keep.name })}</Text>
        <ErrorLine error={error} />
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            {t('cmdb.form.cancel')}
          </Button>
          <Button loading={running} onClick={run}>
            {t('cmdb.quality.mergeInto', { name: value.keep.name })}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
