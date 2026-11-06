import { Button, Card, Group, Select, Stack, Table, Text, TextInput } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { IconPlus } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import { hasRole, useMe } from '@/features/auth/api';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import { ASSET_KINDS, ASSET_STATES, type AssetFilter, type AssetKind, type AssetState, useAssetStats, useAssets, useLocations } from './api';
import { AssetForm } from './AssetForm';
import { AssetStateBadge, AssetsHeader, formatDate, formatMoney } from './ui';
import classes from './assets.module.css';

/** «Активы → Оборудование» (10.14): сводка, фильтры и список. Списанные — отдельным фильтром. */
export function AssetsPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const me = useMe();
  const canManage = hasRole(me.data, 'AST', 'ADM');
  const [filter, setFilter] = useState<AssetFilter>({});
  const [q] = useDebouncedValue(filter.q ?? '', 300);
  const effective = { ...filter, q };
  const assets = useAssets(effective);
  const rows = assets.data?.pages.flatMap((p) => p.items) ?? [];
  const filtered = Object.values(filter).some(Boolean);
  const state = listStateOf(assets, rows.length, filtered);
  const locations = useLocations();
  const [creating, setCreating] = useState(false);

  const open = (id: string) => navigate(`/agent/assets/${id}`);

  return (
    <Stack gap="md" maw={1280}>
      <AssetsHeader
        action={
          canManage && (
            <Button leftSection={<IconPlus size={16} />} onClick={() => setCreating(true)}>
              {t('assets.add')}
            </Button>
          )
        }
      />

      <Stats onPick={(s) => setFilter({ ...filter, state: s })} />

      <Group gap="xs" align="flex-end" role="search" aria-label={t('assets.filters.label')} wrap="wrap">
        <TextInput
          label={t('assets.filters.search')}
          placeholder={t('assets.filters.searchPlaceholder')}
          value={filter.q ?? ''}
          onChange={(e) => setFilter({ ...filter, q: e.currentTarget.value || undefined })}
          style={{ flex: 1, minWidth: 220 }}
        />
        <Select
          label={t('assets.filters.state')}
          placeholder={t('assets.filters.allStates')}
          clearable
          value={filter.state ?? null}
          onChange={(v) => setFilter({ ...filter, state: (v as AssetState) ?? undefined })}
          data={ASSET_STATES.map((s) => ({ value: s, label: t(`assets.state.${s}`) }))}
          w={190}
        />
        <Select
          label={t('assets.filters.kind')}
          placeholder={t('assets.filters.allKinds')}
          clearable
          value={filter.kind ?? null}
          onChange={(v) => setFilter({ ...filter, kind: (v as AssetKind) ?? undefined })}
          data={ASSET_KINDS.map((k) => ({ value: k, label: t(`assets.kind.${k}`) }))}
          w={170}
        />
        <Select
          label={t('assets.filters.location')}
          placeholder={t('assets.filters.allLocations')}
          clearable
          searchable
          value={filter.locationId ?? null}
          onChange={(v) => setFilter({ ...filter, locationId: v ?? undefined })}
          data={(locations.data ?? []).map((l) => ({ value: l.id, label: l.name }))}
          w={200}
        />
      </Group>

      {state ? (
        <ListState
          state={state}
          error={assets.error}
          title={state === 'empty' ? t('assets.empty') : undefined}
          onRetry={() => assets.refetch()}
          onResetFilters={() => setFilter({})}
          action={
            state === 'empty' && canManage ? (
              <Button leftSection={<IconPlus size={16} />} onClick={() => setCreating(true)}>
                {t('assets.add')}
              </Button>
            ) : undefined
          }
        />
      ) : (
        <Card withBorder padding={0} radius="lg" className={classes.tableCard}>
          <Table.ScrollContainer minWidth={900}>
            <Table highlightOnHover verticalSpacing={10} className={classes.table}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t('assets.columns.tag')}</Table.Th>
                  <Table.Th>{t('assets.columns.name')}</Table.Th>
                  <Table.Th>{t('assets.columns.kind')}</Table.Th>
                  <Table.Th>{t('assets.columns.state')}</Table.Th>
                  <Table.Th>{t('assets.columns.holder')}</Table.Th>
                  <Table.Th>{t('assets.columns.location')}</Table.Th>
                  <Table.Th>{t('assets.columns.warranty')}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {rows.map((a) => (
                  <Table.Tr
                    key={a.id}
                    className={classes.row}
                    tabIndex={0}
                    onClick={() => open(a.id)}
                    onKeyDown={(k) => (k.key === 'Enter' ? open(a.id) : undefined)}
                  >
                    <Table.Td className="ei-mono">{a.tag}</Table.Td>
                    <Table.Td>
                      <Text size="sm" fw={600}>
                        {a.name}
                      </Text>
                      {(a.model || a.serial) && (
                        <Text size="xs" className={classes.muted}>
                          {[a.model, a.serial].filter(Boolean).join(' · ')}
                        </Text>
                      )}
                    </Table.Td>
                    <Table.Td>{t(`assets.kind.${a.kind}`)}</Table.Td>
                    <Table.Td>
                      <AssetStateBadge state={a.state} />
                    </Table.Td>
                    <Table.Td>{a.holder?.name ?? '—'}</Table.Td>
                    <Table.Td>{a.location?.name ?? '—'}</Table.Td>
                    <Table.Td className="ei-mono">{formatDate(a.warrantyTo)}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Table.ScrollContainer>
        </Card>
      )}
      {assets.hasNextPage && (
        <Button variant="subtle" onClick={() => assets.fetchNextPage()} loading={assets.isFetchingNextPage} w="fit-content">
          {t('assets.showMore')}
        </Button>
      )}

      <AssetForm opened={creating} onClose={() => setCreating(false)} onSaved={(a) => open(a.id)} />
    </Stack>
  );
}

function Stats({ onPick }: { onPick: (s: AssetState) => void }) {
  const { t } = useTranslation();
  const stats = useAssetStats();
  const s = stats.data;
  if (!s) return null;
  const tile = (label: string, value: number, state?: AssetState, warn = false) =>
    state ? (
      <button type="button" className={classes.stat} onClick={() => onPick(state)}>
        <div className={classes.statValue}>{value}</div>
        <div className={classes.statLabel}>{label}</div>
      </button>
    ) : (
      <div className={`${classes.stat} ${warn && value > 0 ? classes.statWarn : ''}`}>
        <div className={classes.statValue}>{value}</div>
        <div className={classes.statLabel}>{label}</div>
      </div>
    );
  return (
    <div className={classes.stats}>
      {tile(t('assets.stats.inStock'), s.inStock, 'in_stock')}
      {tile(t('assets.stats.inUse'), s.inUse, 'in_use')}
      {tile(t('assets.stats.inRepair'), s.inRepair, 'in_repair')}
      {tile(t('assets.stats.warrantyExpiring'), s.warrantyExpiring, undefined, true)}
      {tile(t('assets.stats.endOfLife'), s.endOfLife, undefined, true)}
      {s.cost.length > 0 && (
        <div className={classes.stat}>
          <div className={classes.statValue} style={{ fontSize: 18, lineHeight: '32px' }}>
            {s.cost.map((c) => formatMoney(c.amount, c.currency)).join(' · ')}
          </div>
          <div className={classes.statLabel}>{t('assets.stats.cost')}</div>
        </div>
      )}
    </div>
  );
}
