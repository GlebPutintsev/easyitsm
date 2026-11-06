import { ActionIcon, Alert, Anchor, Button, Group, Modal, SegmentedControl, Select, Skeleton, Stack, Tabs, Text, Title, Tooltip } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { useQuery } from '@tanstack/react-query';
import { IconArrowLeft, IconBolt, IconPencil, IconPlus, IconX } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams } from 'react-router';
import { hasRole, useMe } from '@/features/auth/api';
import { api, type CursorPage } from '@/shared/api/client';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { ListState } from '@/shared/ui/ListState';
import {
  type Ci,
  type HistoryEntry,
  type Relation,
  formatValue,
  useAddRelation,
  useCi,
  useCiMap,
  useClasses,
  useGroups,
  useHistory,
  useImpact,
  useRelationTypes,
  useRelations,
  useRemoveRelation,
} from './api';
import { CiForm } from './CiForm';
import { CiMapView } from './CiMapView';
import { CiLink, CiStatusBadge, CriticalityBadge, Fields, LayerText, SectionLabel, formatDateTime, sourceLabel } from './ui';
import classes from './cmdb.module.css';

type Tab = 'overview' | 'relations' | 'impact' | 'map' | 'history';

/** Карточка КЕ (10.13): обзор с атрибутами и их источниками, связи, влияние, карта, история. */
export function CiPage() {
  const { id = '' } = useParams();
  const { t } = useTranslation();
  const me = useMe();
  const canWrite = hasRole(me.data, 'CFG');
  const ci = useCi(id);
  const [tab, setTab] = useState<Tab>('overview');
  const [editing, setEditing] = useState(false);

  if (ci.isPending) return <Skeleton height={240} radius="lg" maw={1280} />;
  if (ci.isError || !ci.data) {
    return <ListState state="error" error={ci.error} title={t('cmdb.card.notFound')} onRetry={() => ci.refetch()} />;
  }
  const c = ci.data;

  return (
    <Stack gap="md" maw={1280}>
      <Anchor component={Link} to="/agent/cmdb" size="sm" w="fit-content">
        <Group gap={4}>
          <IconArrowLeft size={14} />
          {t('cmdb.card.back')}
        </Group>
      </Anchor>
      <Group justify="space-between" align="flex-start" wrap="wrap">
        <div>
          <Title order={1} size="h2">
            {c.name}
          </Title>
          <Group gap="xs" mt={4}>
            <Text size="sm" c="var(--ei-text-3)">
              {c.className} · <LayerText layer={c.layer} />
            </Text>
            <CiStatusBadge status={c.status} />
            <CriticalityBadge value={c.criticality} />
          </Group>
        </div>
        {canWrite && !c.mergedInto && (
          <Button variant="default" leftSection={<IconPencil size={16} />} onClick={() => setEditing(true)}>
            {t('cmdb.card.edit')}
          </Button>
        )}
      </Group>
      {c.mergedInto && (
        <Alert color="gray" variant="light">
          {t('cmdb.card.merged')}{' '}
          <Anchor component={Link} to={`/agent/cmdb/${c.mergedInto}`}>
            {t('cmdb.card.openMerged')}
          </Anchor>
        </Alert>
      )}

      <Tabs value={tab} onChange={(v) => setTab((v as Tab) ?? 'overview')} keepMounted={false}>
        <Tabs.List>
          {(['overview', 'relations', 'impact', 'map', 'history'] as Tab[]).map((k) => (
            <Tabs.Tab key={k} value={k}>
              {t(`cmdb.card.tabs.${k}`)}
            </Tabs.Tab>
          ))}
        </Tabs.List>
        <Tabs.Panel value="overview" pt="md">
          <Overview ci={c} />
        </Tabs.Panel>
        <Tabs.Panel value="relations" pt="md">
          <Relations ci={c} canWrite={canWrite && !c.mergedInto} />
        </Tabs.Panel>
        <Tabs.Panel value="impact" pt="md">
          <ImpactPanel ci={c} />
        </Tabs.Panel>
        <Tabs.Panel value="map" pt="md">
          <MapPanel ci={c} />
        </Tabs.Panel>
        <Tabs.Panel value="history" pt="md">
          <History ci={c} />
        </Tabs.Panel>
      </Tabs>

      <CiForm opened={editing} onClose={() => setEditing(false)} ci={c} />
    </Stack>
  );
}

function Overview({ ci }: { ci: Ci }) {
  const { t } = useTranslation();
  const ciClasses = useClasses();
  const groups = useGroups();
  const schema = ciClasses.data?.find((k) => k.code === ci.classCode)?.attributes ?? [];
  const group = groups.data?.find((g) => g.id === ci.ownerGroupId)?.name ?? ci.ownerGroupId;
  return (
    <div className={classes.layout}>
      <Stack gap="md">
        <div className={classes.panel}>
          <Stack gap="sm">
            <SectionLabel>{t('cmdb.field.attributes')}</SectionLabel>
            {schema.length === 0 ? (
              <Text size="sm" className={classes.muted}>
                {t('cmdb.card.noAttributes')}
              </Text>
            ) : (
              <Fields
                items={schema.map((def) => [
                  def.name,
                  ci.attributes[def.code] === undefined ? (
                    '—'
                  ) : (
                    <>
                      <span className={def.type === 'ip' || def.code.endsWith('_number') ? 'ei-mono' : undefined}>
                        {def.type === 'boolean' ? t(ci.attributes[def.code] ? 'cmdb.field.yes' : 'cmdb.field.no') : formatValue(ci.attributes[def.code])}
                      </span>{' '}
                      <span className={classes.sourceTag}>{t('cmdb.card.sourceOf', { source: sourceLabel(ci.attributeSources[def.code]) })}</span>
                    </>
                  ),
                ])}
              />
            )}
          </Stack>
        </div>
        {ci.description && (
          <div className={classes.panel}>
            <Stack gap="xs">
              <SectionLabel>{t('cmdb.field.description')}</SectionLabel>
              <Text size="sm" style={{ whiteSpace: 'pre-wrap' }}>
                {ci.description}
              </Text>
            </Stack>
          </div>
        )}
      </Stack>
      <div className={classes.panel}>
        <Fields
          items={[
            [t('cmdb.field.class'), ci.className],
            [t('cmdb.field.environment'), ci.environment ? <span className="ei-mono">{ci.environment}</span> : '—'],
            [t('cmdb.field.ownerGroup'), group ?? '—'],
            [t('cmdb.card.lastSeen'), ci.lastSeenAt ? formatDateTime(ci.lastSeenAt) : t('cmdb.card.never')],
            [t('cmdb.card.updated'), formatDateTime(ci.updatedAt)],
            ['ID', <span className="ei-mono">{ci.id}</span>],
          ]}
        />
      </div>
    </div>
  );
}

function Relations({ ci, canWrite }: { ci: Ci; canWrite: boolean }) {
  const { t } = useTranslation();
  const relations = useRelations(ci.id);
  const remove = useRemoveRelation();
  const [adding, setAdding] = useState(false);
  const [removing, setRemoving] = useState<Relation | null>(null);
  const list = relations.data ?? [];
  return (
    <Stack gap="sm">
      {canWrite && (
        <Button variant="default" leftSection={<IconPlus size={16} />} onClick={() => setAdding(true)} w="fit-content">
          {t('cmdb.relations.add')}
        </Button>
      )}
      {relations.isPending ? (
        <ListState state="loading" />
      ) : relations.isError ? (
        <ListState state="error" error={relations.error} onRetry={() => relations.refetch()} />
      ) : list.length === 0 ? (
        <Text size="sm" className={classes.muted}>
          {t('cmdb.relations.empty')}
        </Text>
      ) : (
        <div className={classes.panel}>
          {list.map((r) => (
            <div key={r.id} className={classes.relationRow}>
              <div className={classes.relationLabel}>{r.label}</div>
              <div style={{ flex: 1, minWidth: 0 }}>
                <CiLink id={r.other.id} name={r.other.name} />
                <Text size="xs" className={classes.muted}>
                  {r.other.className} · {t('cmdb.relations.origin', { origin: sourceLabel(r.origin) })}
                </Text>
              </div>
              {r.impactDirection !== 'none' && (
                <Tooltip label={t('cmdb.relations.impacts')}>
                  <IconBolt size={16} color="var(--ei-brass-line)" aria-label={t('cmdb.relations.impacts')} />
                </Tooltip>
              )}
              <CiStatusBadge status={r.other.status} />
              {canWrite && (
                <ActionIcon variant="subtle" color="gray" aria-label={t('cmdb.relations.remove')} onClick={() => setRemoving(r)}>
                  <IconX size={16} />
                </ActionIcon>
              )}
            </div>
          ))}
        </div>
      )}
      <AddRelation ci={ci} opened={adding} onClose={() => setAdding(false)} />
      <Modal opened={!!removing} onClose={() => setRemoving(null)} title={t('cmdb.relations.remove')}>
        {removing && (
          <Stack gap="sm">
            <Text size="sm">{t('cmdb.relations.removeConfirm', { label: removing.label, name: removing.other.name })}</Text>
            <ErrorLine error={remove.error} />
            <Group justify="flex-end">
              <Button variant="default" onClick={() => setRemoving(null)}>
                {t('cmdb.form.cancel')}
              </Button>
              <Button color="red" loading={remove.isPending} onClick={() => remove.mutate(removing.id, { onSuccess: () => setRemoving(null) })}>
                {t('cmdb.relations.remove')}
              </Button>
            </Group>
          </Stack>
        )}
      </Modal>
    </Stack>
  );
}

function AddRelation({ ci, opened, onClose }: { ci: Ci; opened: boolean; onClose: () => void }) {
  const { t } = useTranslation();
  const types = useRelationTypes();
  const add = useAddRelation(ci.id);
  const [typeCode, setTypeCode] = useState<string | null>(null);
  const [direction, setDirection] = useState<'outgoing' | 'incoming'>('outgoing');
  const [targetId, setTargetId] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [q] = useDebouncedValue(search, 250);
  const found = useQuery({
    queryKey: ['cmdb', 'cis', 'picker', q],
    queryFn: ({ signal }) => api.get<CursorPage<Ci>>('/cmdb/cis', { query: { q, limit: 20 }, signal }).then((p) => p.items),
    enabled: opened && q.trim().length >= 2,
  });
  const type = types.data?.find((x) => x.code === typeCode);
  const close = () => {
    setTypeCode(null);
    setTargetId(null);
    setSearch('');
    add.reset();
    onClose();
  };
  return (
    <Modal opened={opened} onClose={close} title={t('cmdb.relations.add')}>
      <Stack gap="sm">
        <Select
          label={t('cmdb.relations.type')}
          required
          value={typeCode}
          onChange={setTypeCode}
          data={(types.data ?? []).filter((x) => x.active).map((x) => ({ value: x.code, label: x.name }))}
        />
        <SegmentedControl
          aria-label={t('cmdb.relations.direction')}
          value={direction}
          onChange={(v) => setDirection(v as 'outgoing' | 'incoming')}
          data={[
            { value: 'outgoing', label: type ? `${ci.name} — ${type.name.toLowerCase()} →` : t('cmdb.relations.outgoing') },
            { value: 'incoming', label: type ? `← ${type.inverseName.toLowerCase()}` : t('cmdb.relations.incoming') },
          ]}
        />
        <Select
          label={t('cmdb.relations.target')}
          placeholder={t('cmdb.relations.targetPlaceholder')}
          required
          searchable
          value={targetId}
          onChange={setTargetId}
          searchValue={search}
          onSearchChange={setSearch}
          filter={({ options }) => options}
          nothingFoundMessage={q.trim().length >= 2 && found.isSuccess ? t('cmdb.relations.notFound') : undefined}
          data={(found.data ?? []).filter((x) => x.id !== ci.id).map((x) => ({ value: x.id, label: `${x.name} · ${x.className}` }))}
        />
        <ErrorLine error={add.error} />
        <Group justify="flex-end">
          <Button variant="default" onClick={close}>
            {t('cmdb.form.cancel')}
          </Button>
          <Button
            disabled={!typeCode || !targetId}
            loading={add.isPending}
            onClick={() => typeCode && targetId && add.mutate({ typeCode, targetId, direction }, { onSuccess: close })}
          >
            {t('cmdb.relations.add')}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}

function DepthControl({ value, onChange }: { value: number; onChange: (v: number) => void }) {
  const { t } = useTranslation();
  return (
    <Group gap="xs">
      <Text size="sm" className={classes.muted}>
        {t('cmdb.impact.depth')}
      </Text>
      <SegmentedControl size="xs" value={String(value)} onChange={(v) => onChange(Number(v))} data={['1', '2', '3', '4', '5']} />
    </Group>
  );
}

function ImpactPanel({ ci }: { ci: Ci }) {
  const { t } = useTranslation();
  const [depth, setDepth] = useState(3);
  const impact = useImpact(ci.id, depth);
  const r = impact.data;
  return (
    <Stack gap="md">
      <DepthControl value={depth} onChange={setDepth} />
      {impact.isPending ? (
        <ListState state="loading" />
      ) : impact.isError || !r ? (
        <ListState state="error" error={impact.error} onRetry={() => impact.refetch()} />
      ) : (
        <div className={classes.layout}>
          <Stack gap="md">
            <div className={classes.panel}>
              <Stack gap="xs">
                <SectionLabel>{t('cmdb.impact.services')}</SectionLabel>
                {r.services.length === 0 ? (
                  <Text size="sm" className={classes.muted}>
                    {t('cmdb.impact.noServices')}
                  </Text>
                ) : (
                  r.services.map((s) => (
                    <Group key={s.ci.id} gap="xs" justify="space-between">
                      <CiLink id={s.ci.id} name={s.ci.name} />
                      <Group gap="xs">
                        <Text size="xs" className={classes.muted}>
                          {t('cmdb.impact.level', { depth: s.depth })}
                        </Text>
                        <CriticalityBadge value={s.ci.criticality} />
                      </Group>
                    </Group>
                  ))
                )}
              </Stack>
            </div>
            <div className={classes.panel}>
              <Stack gap="xs">
                <SectionLabel>{t('cmdb.impact.impacted')}</SectionLabel>
                {r.impacted.length === 0 ? (
                  <Text size="sm" className={classes.muted}>
                    {t('cmdb.impact.nothing')}
                  </Text>
                ) : (
                  r.impacted.map((s) => (
                    <Group key={s.ci.id} gap="xs" justify="space-between">
                      <div>
                        <CiLink id={s.ci.id} name={s.ci.name} />
                        <Text size="xs" className={classes.muted}>
                          {s.ci.className}
                        </Text>
                      </div>
                      <Text size="xs" className={classes.muted}>
                        {t('cmdb.impact.level', { depth: s.depth })}
                      </Text>
                    </Group>
                  ))
                )}
                {r.truncated && (
                  <Text size="xs" c="var(--ei-warn)">
                    {t('cmdb.impact.truncated')}
                  </Text>
                )}
              </Stack>
            </div>
          </Stack>
          <div className={classes.panel}>
            <Stack gap="xs">
              <SectionLabel>{t('cmdb.impact.tickets')}</SectionLabel>
              {r.tickets.length === 0 && (
                <Text size="sm" className={classes.muted}>
                  {t('cmdb.impact.noTickets')}
                </Text>
              )}
              {r.tickets.map((tk) => (
                <div key={tk.id}>
                  <Anchor component={Link} to={`/agent/tickets/${tk.number}`} size="sm" fw={600} className="ei-mono">
                    {tk.number}
                  </Anchor>
                  <Text size="sm" lineClamp={2}>
                    {tk.title}
                  </Text>
                </div>
              ))}
              {r.hiddenTickets > 0 && (
                <Text size="xs" className={classes.muted}>
                  {t('cmdb.impact.hidden', { count: r.hiddenTickets })}
                </Text>
              )}
            </Stack>
          </div>
        </div>
      )}
    </Stack>
  );
}

function MapPanel({ ci }: { ci: Ci }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [depth, setDepth] = useState(2);
  const map = useCiMap(ci.id, depth);
  return (
    <Stack gap="sm">
      <DepthControl value={depth} onChange={setDepth} />
      {map.isPending ? (
        <Skeleton height={360} radius="lg" />
      ) : map.isError || !map.data ? (
        <ListState state="error" error={map.error} onRetry={() => map.refetch()} />
      ) : (
        <>
          <CiMapView map={map.data} onOpen={(id) => navigate(`/agent/cmdb/${id}`)} />
          <Text size="xs" className={classes.muted}>
            {t('cmdb.map.legend')}
            {map.data.truncated && ` ${t('cmdb.map.truncated')}`}
          </Text>
        </>
      )}
    </Stack>
  );
}

function History({ ci }: { ci: Ci }) {
  const { t } = useTranslation();
  const history = useHistory(ci.id);
  const ciClasses = useClasses();
  const schema = ciClasses.data?.find((k) => k.code === ci.classCode)?.attributes ?? [];
  const entries = history.data?.pages.flatMap((p) => p.items) ?? [];
  if (history.isPending) return <ListState state="loading" />;
  if (history.isError) return <ListState state="error" error={history.error} onRetry={() => history.refetch()} />;
  if (entries.length === 0) return <Text size="sm">{t('cmdb.history.empty')}</Text>;
  return (
    <Stack gap="sm">
      <ol className={classes.timeline}>
        {entries.map((e) => (
          <li key={e.id}>
            <Text size="sm">{describe(e, schema, t)}</Text>
            <Text size="xs" className={classes.muted}>
              {formatDateTime(e.at)} · {sourceLabel(e.source)}
            </Text>
          </li>
        ))}
      </ol>
      {history.hasNextPage && (
        <Button variant="subtle" onClick={() => history.fetchNextPage()} loading={history.isFetchingNextPage} w="fit-content">
          {t('cmdb.showMore')}
        </Button>
      )}
    </Stack>
  );
}

function describe(e: HistoryEntry, schema: { code: string; name: string }[], t: (key: string, o?: Record<string, unknown>) => string): string {
  if (e.field === 'created' || e.field === 'merged' || e.field === 'merged_into') {
    const v = (e.newValue ?? {}) as { name?: string };
    return v.name && e.field !== 'created' ? `${t(`cmdb.history.${e.field}`)}: ${v.name}` : t(`cmdb.history.${e.field}`);
  }
  if (e.field === 'relation') {
    const v = (e.newValue ?? e.oldValue ?? {}) as { type?: string };
    return `${t('cmdb.history.relation')} ${v.type ?? ''} ${t(e.newValue ? 'cmdb.history.relationAdded' : 'cmdb.history.relationRemoved')}`;
  }
  const label = e.field.startsWith('attr.')
    ? (schema.find((d) => d.code === e.field.slice(5))?.name ?? e.field.slice(5))
    : t(`cmdb.history.field.${e.field}`);
  return `${label}: ${formatValue(e.oldValue)} → ${formatValue(e.newValue)}`;
}
