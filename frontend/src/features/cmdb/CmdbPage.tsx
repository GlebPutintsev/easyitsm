import { Button, Card, Group, Select, Stack, Table, Text, TextInput } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { IconPlus } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import { hasRole, useMe } from '@/features/auth/api';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import { CI_CRITICALITIES, CI_LAYERS, CI_STATUSES, type CiCriticality, type CiFilter, type CiLayer, type CiStatus, useCis, useClasses } from './api';
import { CiForm } from './CiForm';
import { CiStatusBadge, CmdbHeader, CriticalityBadge, LayerText, formatDateTime } from './ui';
import classes from './cmdb.module.css';

/** «CMDB → Конфигурационные единицы» (10.13): фильтры и список. Выведенные — по фильтру статуса. */
export function CmdbPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const me = useMe();
  const canWrite = hasRole(me.data, 'CFG');
  const [filter, setFilter] = useState<CiFilter>({});
  const [q] = useDebouncedValue(filter.q ?? '', 300);
  const cis = useCis({ ...filter, q });
  const rows = cis.data?.pages.flatMap((p) => p.items) ?? [];
  const filtered = Object.values(filter).some(Boolean);
  const state = listStateOf(cis, rows.length, filtered);
  const ciClasses = useClasses();
  const [creating, setCreating] = useState(false);

  const open = (id: string) => navigate(`/agent/cmdb/${id}`);
  const add = canWrite && (
    <Button leftSection={<IconPlus size={16} />} onClick={() => setCreating(true)}>
      {t('cmdb.add')}
    </Button>
  );

  return (
    <Stack gap="md" maw={1280}>
      <CmdbHeader action={add} />

      <Group gap="xs" align="flex-end" role="search" aria-label={t('cmdb.filters.label')} wrap="wrap">
        <TextInput
          label={t('cmdb.filters.search')}
          placeholder={t('cmdb.filters.searchPlaceholder')}
          value={filter.q ?? ''}
          onChange={(e) => setFilter({ ...filter, q: e.currentTarget.value || undefined })}
          style={{ flex: 1, minWidth: 220 }}
        />
        <Select
          label={t('cmdb.filters.class')}
          placeholder={t('cmdb.filters.allClasses')}
          clearable
          searchable
          value={filter.classCode ?? null}
          onChange={(v) => setFilter({ ...filter, classCode: v ?? undefined })}
          data={(ciClasses.data ?? []).filter((c) => c.active).map((c) => ({ value: c.code, label: c.name }))}
          w={200}
        />
        <Select
          label={t('cmdb.filters.layer')}
          placeholder={t('cmdb.filters.allLayers')}
          clearable
          value={filter.layer ?? null}
          onChange={(v) => setFilter({ ...filter, layer: (v as CiLayer) ?? undefined })}
          data={CI_LAYERS.map((l) => ({ value: l, label: t(`cmdb.layer.${l}`) }))}
          w={190}
        />
        <Select
          label={t('cmdb.filters.status')}
          placeholder={t('cmdb.filters.allStatuses')}
          clearable
          value={filter.status ?? null}
          onChange={(v) => setFilter({ ...filter, status: (v as CiStatus) ?? undefined })}
          data={CI_STATUSES.map((s) => ({ value: s, label: t(`cmdb.status.${s}`) }))}
          w={190}
        />
        <Select
          label={t('cmdb.filters.criticality')}
          placeholder={t('cmdb.filters.allCriticalities')}
          clearable
          value={filter.criticality ?? null}
          onChange={(v) => setFilter({ ...filter, criticality: (v as CiCriticality) ?? undefined })}
          data={CI_CRITICALITIES.map((c) => ({ value: c, label: t(`cmdb.criticality.${c}`) }))}
          w={150}
        />
      </Group>

      {state ? (
        <ListState
          state={state}
          error={cis.error}
          title={state === 'empty' ? t('cmdb.empty') : undefined}
          onRetry={() => cis.refetch()}
          onResetFilters={() => setFilter({})}
          action={state === 'empty' ? add || undefined : undefined}
        />
      ) : (
        <Card withBorder padding={0} radius="lg" className={classes.tableCard}>
          <Table.ScrollContainer minWidth={900}>
            <Table highlightOnHover verticalSpacing={10} className={classes.table}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t('cmdb.columns.name')}</Table.Th>
                  <Table.Th>{t('cmdb.columns.class')}</Table.Th>
                  <Table.Th>{t('cmdb.columns.status')}</Table.Th>
                  <Table.Th>{t('cmdb.columns.environment')}</Table.Th>
                  <Table.Th>{t('cmdb.columns.criticality')}</Table.Th>
                  <Table.Th>{t('cmdb.columns.lastSeen')}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {rows.map((ci) => (
                  <Table.Tr
                    key={ci.id}
                    className={classes.row}
                    tabIndex={0}
                    onClick={() => open(ci.id)}
                    onKeyDown={(k) => (k.key === 'Enter' ? open(ci.id) : undefined)}
                  >
                    <Table.Td>
                      <Text size="sm" fw={600}>
                        {ci.name}
                      </Text>
                      <Text size="xs" className={classes.muted}>
                        <LayerText layer={ci.layer} />
                      </Text>
                    </Table.Td>
                    <Table.Td>{ci.className}</Table.Td>
                    <Table.Td>
                      <CiStatusBadge status={ci.status} />
                    </Table.Td>
                    <Table.Td className="ei-mono">{ci.environment ?? '—'}</Table.Td>
                    <Table.Td>
                      <CriticalityBadge value={ci.criticality} />
                    </Table.Td>
                    <Table.Td className="ei-mono">{formatDateTime(ci.lastSeenAt)}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Table.ScrollContainer>
        </Card>
      )}
      {cis.hasNextPage && (
        <Button variant="subtle" onClick={() => cis.fetchNextPage()} loading={cis.isFetchingNextPage} w="fit-content">
          {t('cmdb.showMore')}
        </Button>
      )}

      <CiForm opened={creating} onClose={() => setCreating(false)} onSaved={(ci) => open(ci.id)} />
    </Stack>
  );
}
