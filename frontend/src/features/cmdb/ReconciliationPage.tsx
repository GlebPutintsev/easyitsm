import { Button, Group, Radio, SegmentedControl, Stack, Text } from '@mantine/core';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { hasRole, useMe } from '@/features/auth/api';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import { type Conflict, type ConflictStatus, formatValue, useConflicts, useResolveConflict } from './api';
import { CiLink, CiStatusBadge, CmdbHeader, ToneBadge, formatDateTime, sourceLabel } from './ui';
import classes from './cmdb.module.css';

const STATUSES: (ConflictStatus | 'all')[] = ['open', 'accepted', 'ignored', 'merged', 'dismissed', 'all'];

/** «CMDB → Сверка» (CFG-05): конфликты источников с текущими данными и дубликаты. */
export function ReconciliationPage() {
  const { t } = useTranslation();
  const me = useMe();
  const canWrite = hasRole(me.data, 'CFG');
  const [status, setStatus] = useState<ConflictStatus | 'all'>('open');
  const conflicts = useConflicts(status);
  const rows = conflicts.data?.pages.flatMap((p) => p.items) ?? [];
  const state = listStateOf(conflicts, rows.length, false);
  return (
    <Stack gap="md" maw={1280}>
      <CmdbHeader />
      <Text size="sm" c="var(--ei-text-2)" maw={760}>
        {t('cmdb.reconciliation.intro')}
      </Text>
      <SegmentedControl
        w="fit-content"
        value={status}
        onChange={(v) => setStatus(v as ConflictStatus | 'all')}
        data={STATUSES.map((s) => ({ value: s, label: t(`cmdb.reconciliation.status.${s}`) }))}
      />
      {state ? (
        <ListState
          state={state}
          error={conflicts.error}
          title={state === 'empty' ? t('cmdb.reconciliation.empty') : undefined}
          onRetry={() => conflicts.refetch()}
        />
      ) : (
        <Stack gap="sm">
          {rows.map((c) => (
            <ConflictCard key={c.id} conflict={c} canWrite={canWrite} />
          ))}
        </Stack>
      )}
      {conflicts.hasNextPage && (
        <Button variant="subtle" onClick={() => conflicts.fetchNextPage()} loading={conflicts.isFetchingNextPage} w="fit-content">
          {t('cmdb.showMore')}
        </Button>
      )}
    </Stack>
  );
}

function ConflictCard({ conflict: c, canWrite }: { conflict: Conflict; canWrite: boolean }) {
  const { t } = useTranslation();
  const resolve = useResolveConflict();
  const [chosen, setChosen] = useState<string | null>(null);
  const open = c.status === 'open';
  const act = (action: 'accept' | 'ignore' | 'merge', ciId?: string) => resolve.mutate({ id: c.id, action, ciId });
  return (
    <section className={classes.conflict} aria-label={c.kind === 'attribute' ? t('cmdb.reconciliation.attribute', { attribute: c.attribute }) : t('cmdb.reconciliation.duplicate')}>
      <Stack gap="sm">
        <Group justify="space-between" align="flex-start" wrap="wrap">
          <div>
            <Text fw={600}>
              {c.kind === 'attribute' ? t('cmdb.reconciliation.attribute', { attribute: c.attribute }) : t('cmdb.reconciliation.duplicate')}
            </Text>
            <Text size="xs" className={classes.muted}>
              {t('cmdb.reconciliation.object', { externalId: c.externalId, source: c.source.name })} · {formatDateTime(c.updatedAt)}
            </Text>
          </div>
          {!open && <ToneBadge tone="neutral">{t(`cmdb.reconciliation.status.${c.status}`)}</ToneBadge>}
        </Group>

        {c.kind === 'attribute' && (
          <>
            {c.ci && (
              <Group gap="xs">
                <CiLink id={c.ci.id} name={c.ci.name} />
                <Text size="xs" className={classes.muted}>
                  {c.ci.className}
                </Text>
              </Group>
            )}
            <div className={classes.compare}>
              <div className={classes.compareCell}>
                <Text size="xs" className={classes.muted}>
                  {t('cmdb.reconciliation.current')} · {t('cmdb.reconciliation.owner', { owner: sourceLabel(c.currentSource) })}
                </Text>
                <Text size="sm" className="ei-mono">
                  {formatValue(c.currentValue)}
                </Text>
              </div>
              <div className={classes.compareCell}>
                <Text size="xs" className={classes.muted}>
                  {t('cmdb.reconciliation.proposed')} · {c.source.name}
                </Text>
                <Text size="sm" className="ei-mono">
                  {formatValue(c.proposedValue)}
                </Text>
              </div>
            </div>
            {open && canWrite && (
              <Group gap="xs">
                <Button size="xs" loading={resolve.isPending && resolve.variables?.action === 'accept'} onClick={() => act('accept')}>
                  {t('cmdb.reconciliation.accept')}
                </Button>
                <Button size="xs" variant="default" loading={resolve.isPending && resolve.variables?.action === 'ignore'} onClick={() => act('ignore')}>
                  {t('cmdb.reconciliation.ignore')}
                </Button>
              </Group>
            )}
          </>
        )}

        {c.kind === 'duplicate' && (
          <>
            <Text size="sm">
              {c.itemName} <span className={classes.muted}>· {c.itemClassCode}</span>
            </Text>
            <Radio.Group
              label={open && canWrite ? t('cmdb.reconciliation.chooseCi') : t('cmdb.reconciliation.candidates')}
              value={chosen}
              onChange={setChosen}
            >
              <Stack gap={6} mt={6}>
                {c.candidates.map((ci) =>
                  open && canWrite ? (
                    <Radio
                      key={ci.id}
                      value={ci.id}
                      label={
                        <Group gap="xs">
                          <CiLink id={ci.id} name={ci.name} />
                          <Text size="xs" className={classes.muted}>
                            {ci.className}
                          </Text>
                          <CiStatusBadge status={ci.status} />
                        </Group>
                      }
                    />
                  ) : (
                    <Group key={ci.id} gap="xs">
                      <CiLink id={ci.id} name={ci.name} />
                      <CiStatusBadge status={ci.status} />
                    </Group>
                  ),
                )}
              </Stack>
            </Radio.Group>
            {open && canWrite && (
              <Group gap="xs">
                <Button size="xs" disabled={!chosen} loading={resolve.isPending && resolve.variables?.action === 'merge'} onClick={() => chosen && act('merge', chosen)}>
                  {t('cmdb.reconciliation.merge')}
                </Button>
                <Button size="xs" variant="default" disabled={!chosen} loading={resolve.isPending && resolve.variables?.action === 'accept'} onClick={() => chosen && act('accept', chosen)}>
                  {t('cmdb.reconciliation.link')}
                </Button>
                <Button size="xs" variant="subtle" color="gray" loading={resolve.isPending && resolve.variables?.action === 'ignore'} onClick={() => act('ignore')}>
                  {t('cmdb.reconciliation.ignore')}
                </Button>
              </Group>
            )}
          </>
        )}
        <ErrorLine error={resolve.error} />
      </Stack>
    </section>
  );
}
