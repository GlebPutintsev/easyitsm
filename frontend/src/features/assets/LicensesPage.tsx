import { ActionIcon, Button, Card, Group, Modal, NumberInput, Progress, SegmentedControl, Select, SimpleGrid, Stack, Table, Text, TextInput, Textarea } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { IconPlus, IconX } from '@tabler/icons-react';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate, useParams } from 'react-router';
import { hasRole, useMe } from '@/features/auth/api';
import { api, type CursorPage } from '@/shared/api/client';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import { PersonPicker } from '@/shared/ui/PersonPicker';
import {
  LICENSE_METRICS,
  type AssetRow,
  type License,
  type LicenseMetric,
  type LicenseStatus,
  useAssign,
  useAssignments,
  useContracts,
  useLicense,
  useLicenses,
  useSaveLicense,
  useUnassign,
  useVendors,
} from './api';
import { AssetsHeader, SectionLabel, TermBadge, formatDate, patchOf } from './ui';
import classes from './assets.module.css';

const STATUSES: (LicenseStatus | 'overused')[] = ['active', 'expiring', 'expired', 'overused'];

/** «Активы → Лицензии ПО» (AST-03, 10.14): права, факт использования и сроки. */
export function LicensesPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { id } = useParams();
  const me = useMe();
  const canManage = hasRole(me.data, 'AST', 'ADM');
  const [q, setQ] = useState('');
  const [status, setStatus] = useState<LicenseStatus | 'overused' | undefined>();
  const [debounced] = useDebouncedValue(q, 300);
  const licenses = useLicenses(debounced, status);
  const rows = licenses.data ?? [];
  const state = listStateOf(licenses, rows.length, !!q || !!status);
  const [creating, setCreating] = useState(false);

  return (
    <Stack gap="md" maw={1280}>
      <AssetsHeader
        action={
          canManage && (
            <Button leftSection={<IconPlus size={16} />} onClick={() => setCreating(true)}>
              {t('assets.licenses.add')}
            </Button>
          )
        }
      />
      <Group gap="xs" align="flex-end" role="search" wrap="wrap">
        <TextInput label={t('assets.filters.search')} placeholder={t('assets.licenses.search')} value={q} onChange={(e) => setQ(e.currentTarget.value)} style={{ flex: 1, minWidth: 220 }} />
        <Select
          label={t('assets.licenses.status')}
          placeholder={t('assets.licenses.allStatuses')}
          clearable
          value={status ?? null}
          onChange={(v) => setStatus((v as LicenseStatus | 'overused') ?? undefined)}
          data={STATUSES.map((s) => ({ value: s, label: t(`assets.licenses.statusName.${s}`) }))}
          w={200}
        />
      </Group>
      {state ? (
        <ListState state={state} error={licenses.error} title={state === 'empty' ? t('assets.licenses.empty') : undefined} onRetry={() => licenses.refetch()} onResetFilters={() => { setQ(''); setStatus(undefined); }} />
      ) : (
        <Card withBorder padding={0} radius="lg" className={classes.tableCard}>
          <Table.ScrollContainer minWidth={820}>
            <Table highlightOnHover verticalSpacing={10} className={classes.table}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t('assets.licenses.columns.product')}</Table.Th>
                  <Table.Th>{t('assets.licenses.columns.metric')}</Table.Th>
                  <Table.Th>{t('assets.licenses.columns.usage')}</Table.Th>
                  <Table.Th>{t('assets.licenses.columns.validTo')}</Table.Th>
                  <Table.Th>{t('assets.licenses.columns.status')}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {rows.map((l) => (
                  <Table.Tr key={l.id} className={classes.row} tabIndex={0} onClick={() => navigate(`/agent/assets/licenses/${l.id}`)} onKeyDown={(k) => (k.key === 'Enter' ? navigate(`/agent/assets/licenses/${l.id}`) : undefined)}>
                    <Table.Td>
                      <Text size="sm" fw={600}>
                        {l.product}
                      </Text>
                      {l.publisher && (
                        <Text size="xs" className={classes.muted}>
                          {l.publisher}
                        </Text>
                      )}
                    </Table.Td>
                    <Table.Td>{t(`assets.licenses.metric.${l.metric}`)}</Table.Td>
                    <Table.Td>
                      <Usage license={l} />
                    </Table.Td>
                    <Table.Td className="ei-mono">{l.validTo ? formatDate(l.validTo) : t('assets.licenses.perpetual')}</Table.Td>
                    <Table.Td>
                      <LicenseStatusBadge license={l} />
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Table.ScrollContainer>
        </Card>
      )}
      <LicenseModal opened={creating} onClose={() => setCreating(false)} canManage={canManage} />
      <LicenseModal opened={!!id} licenseId={id} onClose={() => navigate('/agent/assets/licenses')} canManage={canManage} />
    </Stack>
  );
}

function LicenseStatusBadge({ license }: { license: License }) {
  const { t } = useTranslation();
  const s = license.overused ? 'overused' : license.status;
  const days = license.status === 'expiring' && license.daysLeft != null ? ` · ${t('assets.licenses.daysLeft', { count: license.daysLeft })}` : '';
  return <TermBadge status={s} label={t(`assets.licenses.statusName.${s}`) + days} />;
}

function Usage({ license }: { license: License }) {
  const { t } = useTranslation();
  if (license.quantity == null) {
    return <Text size="sm">{t('assets.licenses.usageUnlimited', { used: license.used })}</Text>;
  }
  const pct = license.quantity === 0 ? 100 : Math.min(100, (license.used / license.quantity) * 100);
  return (
    <Stack gap={4} className={classes.usage}>
      <Text size="sm" className="ei-mono">
        {t('assets.licenses.usage', { used: license.used, quantity: license.quantity })}
      </Text>
      <Progress value={pct} size="sm" color={license.overused ? 'red' : pct >= 90 ? 'orange' : 'admiral'} aria-hidden />
    </Stack>
  );
}

function LicenseModal({ opened, licenseId, onClose, canManage }: { opened: boolean; licenseId?: string; onClose: () => void; canManage: boolean }) {
  const { t } = useTranslation();
  const license = useLicense(licenseId);
  const title = licenseId ? t('assets.licenses.editTitle', { product: license.data?.product ?? '' }) : t('assets.licenses.createTitle');
  return (
    <Modal opened={opened} onClose={onClose} size="lg" title={title}>
      {opened && (!licenseId || license.data) && (
        <Stack gap="lg">
          <LicenseForm key={license.data?.version ?? 'new'} license={license.data} onClose={onClose} readOnly={!canManage} />
          {license.data && <Assignments license={license.data} canManage={canManage} />}
        </Stack>
      )}
      {opened && licenseId && !license.data && <ListState state={license.error ? 'error' : 'loading'} error={license.error} rows={3} />}
    </Modal>
  );
}

type Values = {
  product: string;
  publisher: string;
  metric: LicenseMetric | '';
  quantity: number | '';
  validFrom: string;
  validTo: string;
  renewalNoticeDays: number | '';
  vendorId: string;
  contractId: string;
  cost: number | '';
  currency: string;
  ownerId: string;
  notes: string;
};

const CLEARABLE: (keyof Values)[] = ['publisher', 'quantity', 'validFrom', 'validTo', 'vendorId', 'contractId', 'cost', 'ownerId'];

function valuesOf(l?: License): Values {
  return {
    product: l?.product ?? '',
    publisher: l?.publisher ?? '',
    metric: l?.metric ?? '',
    quantity: l?.quantity ?? '',
    validFrom: l?.validFrom ?? '',
    validTo: l?.validTo ?? '',
    renewalNoticeDays: l?.renewalNoticeDays ?? 30,
    vendorId: l?.vendor?.id ?? '',
    contractId: l?.contract?.id ?? '',
    cost: l?.cost ?? '',
    currency: l?.currency ?? 'RUB',
    ownerId: l?.owner?.id ?? '',
    notes: l?.notes ?? '',
  };
}

function LicenseForm({ license, onClose, readOnly }: { license?: License; onClose: () => void; readOnly: boolean }) {
  const { t } = useTranslation();
  const [v, setV] = useState<Values>(() => valuesOf(license));
  const [touched, setTouched] = useState(false);
  const vendors = useVendors();
  const contracts = useContracts();
  const save = useSaveLicense();
  const set = <K extends keyof Values>(key: K) => (value: Values[K]) => setV((p) => ({ ...p, [key]: value }));

  const submit = () => {
    setTouched(true);
    if (!v.product.trim() || !v.metric) return;
    const fields = patchOf({ ...v, product: v.product.trim() }, license ? valuesOf(license) : null, CLEARABLE);
    save.mutate({ license, fields: fields as never }, { onSuccess: () => (license ? undefined : onClose()) });
  };

  return (
    <Stack gap="sm">
      {license?.overused && (
        <Text size="sm" c="var(--ei-danger)" role="alert">
          {t('assets.licenses.overusedWarning', { used: license.used, quantity: license.quantity })}
        </Text>
      )}
      <fieldset disabled={readOnly} style={{ border: 0, padding: 0, margin: 0 }}>
        <Stack gap="sm">
          <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="sm">
            <TextInput label={t('assets.licenses.field.product')} required value={v.product} onChange={(e) => set('product')(e.currentTarget.value)} error={touched && !v.product.trim() ? t('assets.form.required') : undefined} />
            <TextInput label={t('assets.licenses.field.publisher')} value={v.publisher} onChange={(e) => set('publisher')(e.currentTarget.value)} />
          </SimpleGrid>
          <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="sm">
            <Select
              label={t('assets.licenses.field.metric')}
              required
              value={v.metric || null}
              onChange={(m) => set('metric')((m as LicenseMetric) ?? '')}
              data={LICENSE_METRICS.map((m) => ({ value: m, label: t(`assets.licenses.metric.${m}`) }))}
              error={touched && !v.metric ? t('assets.form.required') : undefined}
            />
            <NumberInput label={t('assets.licenses.field.quantity')} description={t('assets.licenses.field.quantityHint')} min={0} allowDecimal={false} value={v.quantity} onChange={(x) => set('quantity')(typeof x === 'number' ? x : '')} />
          </SimpleGrid>
          <SimpleGrid cols={{ base: 1, sm: 3 }} spacing="sm">
            <TextInput type="date" label={t('assets.licenses.field.validFrom')} value={v.validFrom} onChange={(e) => set('validFrom')(e.currentTarget.value)} />
            <TextInput type="date" label={t('assets.licenses.field.validTo')} description={t('assets.licenses.field.validToHint')} value={v.validTo} onChange={(e) => set('validTo')(e.currentTarget.value)} />
            <NumberInput label={t('assets.licenses.field.notice')} min={0} max={365} allowDecimal={false} value={v.renewalNoticeDays} onChange={(x) => set('renewalNoticeDays')(typeof x === 'number' ? x : '')} />
          </SimpleGrid>
          <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="sm">
            <Select label={t('assets.field.vendor')} clearable searchable value={v.vendorId || null} onChange={(x) => set('vendorId')(x ?? '')} data={(vendors.data ?? []).map((x) => ({ value: x.id, label: x.name }))} />
            <Select label={t('assets.field.contract')} clearable searchable value={v.contractId || null} onChange={(x) => set('contractId')(x ?? '')} data={(contracts.data ?? []).map((c) => ({ value: c.id, label: `№ ${c.number} · ${c.title}` }))} />
          </SimpleGrid>
          <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="sm">
            <NumberInput label={t('assets.field.cost')} min={0} decimalScale={2} thousandSeparator=" " value={v.cost} onChange={(x) => set('cost')(typeof x === 'number' ? x : '')} />
            <PersonPicker label={t('assets.licenses.field.owner')} value={v.ownerId || null} onChange={(x) => set('ownerId')(x ?? '')} />
          </SimpleGrid>
          <Textarea label={t('assets.field.notes')} autosize minRows={2} value={v.notes} onChange={(e) => set('notes')(e.currentTarget.value)} />
        </Stack>
      </fieldset>
      <ErrorLine error={save.error} />
      {!readOnly && (
        <Group justify="flex-end" gap="xs">
          <Button variant="default" onClick={onClose}>
            {t('assets.form.cancel')}
          </Button>
          <Button onClick={submit} loading={save.isPending}>
            {t('assets.form.save')}
          </Button>
        </Group>
      )}
    </Stack>
  );
}

function Assignments({ license, canManage }: { license: License; canManage: boolean }) {
  const { t } = useTranslation();
  const list = useAssignments(license.id);
  const assign = useAssign(license.id);
  const unassign = useUnassign(license.id);
  const [target, setTarget] = useState<'device' | 'user'>(license.metric === 'user' ? 'user' : 'device');
  const [userId, setUserId] = useState<string | null>(null);
  const [assetId, setAssetId] = useState<string | null>(null);

  const submit = () => {
    const body = target === 'user' ? { userId: userId ?? undefined } : { assetId: assetId ?? undefined };
    assign.mutate(body, {
      onSuccess: () => {
        setUserId(null);
        setAssetId(null);
      },
    });
  };

  return (
    <Stack gap="sm">
      <SectionLabel>{t('assets.licenses.assignments')}</SectionLabel>
      <Usage license={license} />
      {(list.data ?? []).length === 0 ? (
        <Text size="sm" className={classes.muted}>
          {t('assets.licenses.assignmentsEmpty')}
        </Text>
      ) : (
        (list.data ?? []).map((a) => {
          const name = a.asset?.name ?? a.user?.name ?? '—';
          return (
            <Group key={a.id} justify="space-between" wrap="nowrap">
              <Text size="sm">{name}</Text>
              <Group gap="xs" wrap="nowrap">
                <Text size="xs" className={classes.muted}>
                  {formatDate(a.assignedAt)}
                </Text>
                {canManage && (
                  <ActionIcon variant="subtle" color="gray" aria-label={t('assets.licenses.releaseLabel', { name })} onClick={() => unassign.mutate(a.id)}>
                    <IconX size={16} />
                  </ActionIcon>
                )}
              </Group>
            </Group>
          );
        })
      )}
      {canManage && (
        <Stack gap="xs">
          <SegmentedControl
            size="xs"
            w="fit-content"
            value={target}
            onChange={(x) => setTarget(x as 'device' | 'user')}
            data={[
              { value: 'device', label: t('assets.licenses.toDevice') },
              { value: 'user', label: t('assets.licenses.toUser') },
            ]}
          />
          <Group gap="xs" align="flex-end">
            <div style={{ flex: 1 }}>
              {target === 'user' ? (
                <PersonPicker label={t('assets.licenses.toUser')} value={userId} onChange={setUserId} />
              ) : (
                <AssetPicker value={assetId} onChange={setAssetId} />
              )}
            </div>
            <Button variant="default" onClick={submit} loading={assign.isPending} disabled={target === 'user' ? !userId : !assetId}>
              {t('assets.licenses.assignTo')}
            </Button>
          </Group>
        </Stack>
      )}
      <ErrorLine error={assign.error ?? unassign.error} />
    </Stack>
  );
}

/** Выбор устройства по инвентарному номеру, названию или серийному номеру (поиск на сервере). */
function AssetPicker({ value, onChange }: { value: string | null; onChange: (id: string | null) => void }) {
  const { t } = useTranslation();
  const [search, setSearch] = useState('');
  const [q] = useDebouncedValue(search, 250);
  const found = useQuery({
    queryKey: ['assets', 'picker', q],
    queryFn: ({ signal }) => api.get<CursorPage<AssetRow>>('/assets', { query: { q, limit: 10 }, signal }).then((p) => p.items),
    enabled: q.trim().length >= 2,
  });
  return (
    <Select
      label={t('assets.licenses.assetTag')}
      searchable
      clearable
      value={value}
      onChange={onChange}
      searchValue={search}
      onSearchChange={setSearch}
      filter={({ options }) => options}
      nothingFoundMessage={q.trim().length >= 2 && found.isSuccess ? t('assets.licenses.assetNotFound') : undefined}
      data={(found.data ?? []).map((a) => ({ value: a.id, label: `${a.tag} · ${a.name}` }))}
    />
  );
}
