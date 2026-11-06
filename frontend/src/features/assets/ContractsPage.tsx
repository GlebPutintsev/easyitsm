import { Button, Card, Checkbox, Group, Modal, NumberInput, Select, SimpleGrid, Stack, Table, Text, TextInput, Textarea } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { IconPlus } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate, useParams } from 'react-router';
import { hasRole, useMe } from '@/features/auth/api';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import { PersonPicker } from '@/shared/ui/PersonPicker';
import { CONTRACT_KINDS, type Contract, type ContractKind, type ContractStatus, useContract, useContracts, useSaveContract, useVendors } from './api';
import { AssetsHeader, TermBadge, formatDate, formatMoney, patchOf } from './ui';
import classes from './assets.module.css';

const STATUSES: ContractStatus[] = ['active', 'expiring', 'expired', 'terminated'];

/** «Активы → Договоры» (AST-04, 10.14): сроки и напоминания о продлении. Ближайшие к окончанию — сверху. */
export function ContractsPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { id } = useParams();
  const me = useMe();
  const canManage = hasRole(me.data, 'AST', 'ADM');
  const [q, setQ] = useState('');
  const [status, setStatus] = useState<ContractStatus | undefined>();
  const [debounced] = useDebouncedValue(q, 300);
  const contracts = useContracts(debounced, status);
  const rows = contracts.data ?? [];
  const state = listStateOf(contracts, rows.length, !!q || !!status);
  const [creating, setCreating] = useState(false);
  const open = (cid: string) => navigate(`/agent/assets/contracts/${cid}`);

  return (
    <Stack gap="md" maw={1280}>
      <AssetsHeader
        action={
          canManage && (
            <Button leftSection={<IconPlus size={16} />} onClick={() => setCreating(true)}>
              {t('assets.contracts.add')}
            </Button>
          )
        }
      />
      <Group gap="xs" align="flex-end" role="search" wrap="wrap">
        <TextInput label={t('assets.filters.search')} placeholder={t('assets.contracts.search')} value={q} onChange={(e) => setQ(e.currentTarget.value)} style={{ flex: 1, minWidth: 220 }} />
        <Select
          label={t('assets.licenses.status')}
          placeholder={t('assets.licenses.allStatuses')}
          clearable
          value={status ?? null}
          onChange={(v) => setStatus((v as ContractStatus) ?? undefined)}
          data={STATUSES.map((s) => ({ value: s, label: t(`assets.contracts.statusName.${s}`) }))}
          w={200}
        />
      </Group>
      {state ? (
        <ListState state={state} error={contracts.error} title={state === 'empty' ? t('assets.contracts.empty') : undefined} onRetry={() => contracts.refetch()} onResetFilters={() => { setQ(''); setStatus(undefined); }} />
      ) : (
        <Card withBorder padding={0} radius="lg" className={classes.tableCard}>
          <Table.ScrollContainer minWidth={900}>
            <Table highlightOnHover verticalSpacing={10} className={classes.table}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t('assets.contracts.columns.number')}</Table.Th>
                  <Table.Th>{t('assets.contracts.columns.title')}</Table.Th>
                  <Table.Th>{t('assets.contracts.columns.vendor')}</Table.Th>
                  <Table.Th>{t('assets.contracts.columns.endsOn')}</Table.Th>
                  <Table.Th>{t('assets.contracts.columns.owner')}</Table.Th>
                  <Table.Th>{t('assets.contracts.columns.status')}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {rows.map((c) => (
                  <Table.Tr key={c.id} className={classes.row} tabIndex={0} onClick={() => open(c.id)} onKeyDown={(k) => (k.key === 'Enter' ? open(c.id) : undefined)}>
                    <Table.Td className="ei-mono">{c.number}</Table.Td>
                    <Table.Td>
                      <Text size="sm" fw={600}>
                        {c.title}
                      </Text>
                      <Text size="xs" className={classes.muted}>
                        {t(`assets.contracts.kind.${c.kind}`)}
                        {c.amount != null ? ` · ${formatMoney(c.amount, c.currency)}` : ''}
                      </Text>
                    </Table.Td>
                    <Table.Td>{c.vendor?.name ?? '—'}</Table.Td>
                    <Table.Td className="ei-mono">{c.endsOn ? formatDate(c.endsOn) : t('assets.contracts.openEnded')}</Table.Td>
                    <Table.Td>{c.owner?.name ?? '—'}</Table.Td>
                    <Table.Td>
                      <ContractStatusBadge contract={c} />
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Table.ScrollContainer>
        </Card>
      )}
      <ContractModal opened={creating} onClose={() => setCreating(false)} canManage={canManage} />
      <ContractModal opened={!!id} contractId={id} onClose={() => navigate('/agent/assets/contracts')} canManage={canManage} />
    </Stack>
  );
}

function ContractStatusBadge({ contract }: { contract: Contract }) {
  const { t } = useTranslation();
  const days = contract.status === 'expiring' && contract.daysLeft != null ? ` · ${t('assets.licenses.daysLeft', { count: contract.daysLeft })}` : '';
  return <TermBadge status={contract.status} label={t(`assets.contracts.statusName.${contract.status}`) + days} />;
}

function ContractModal({ opened, contractId, onClose, canManage }: { opened: boolean; contractId?: string; onClose: () => void; canManage: boolean }) {
  const { t } = useTranslation();
  const contract = useContract(contractId);
  const title = contractId ? t('assets.contracts.editTitle', { number: contract.data?.number ?? '' }) : t('assets.contracts.createTitle');
  return (
    <Modal opened={opened} onClose={onClose} size="lg" title={title}>
      {opened && (!contractId || contract.data) && <ContractForm key={contract.data?.version ?? 'new'} contract={contract.data} onClose={onClose} readOnly={!canManage} />}
      {opened && contractId && !contract.data && <ListState state={contract.error ? 'error' : 'loading'} error={contract.error} rows={3} />}
    </Modal>
  );
}

type Values = {
  number: string;
  title: string;
  kind: ContractKind | '';
  vendorId: string;
  startsOn: string;
  endsOn: string;
  renewalNoticeDays: number | '';
  autoRenew: boolean;
  amount: number | '';
  currency: string;
  ownerId: string;
  notes: string;
  terminated: boolean;
};

const CLEARABLE: (keyof Values)[] = ['vendorId', 'endsOn', 'amount', 'ownerId'];

function valuesOf(c?: Contract): Values {
  return {
    number: c?.number ?? '',
    title: c?.title ?? '',
    kind: c?.kind ?? '',
    vendorId: c?.vendor?.id ?? '',
    startsOn: c?.startsOn ?? '',
    endsOn: c?.endsOn ?? '',
    renewalNoticeDays: c?.renewalNoticeDays ?? 30,
    autoRenew: c?.autoRenew ?? false,
    amount: c?.amount ?? '',
    currency: c?.currency ?? 'RUB',
    ownerId: c?.owner?.id ?? '',
    notes: c?.notes ?? '',
    terminated: c?.terminated ?? false,
  };
}

function ContractForm({ contract, onClose, readOnly }: { contract?: Contract; onClose: () => void; readOnly: boolean }) {
  const { t } = useTranslation();
  const [v, setV] = useState<Values>(() => valuesOf(contract));
  const [touched, setTouched] = useState(false);
  const vendors = useVendors();
  const save = useSaveContract();
  const set = <K extends keyof Values>(key: K) => (value: Values[K]) => setV((p) => ({ ...p, [key]: value }));
  const req = (ok: boolean) => (touched && !ok ? t('assets.form.required') : undefined);

  const submit = () => {
    setTouched(true);
    if (!v.number.trim() || !v.title.trim() || !v.kind || !v.startsOn) return;
    const fields = patchOf({ ...v, number: v.number.trim(), title: v.title.trim() }, contract ? valuesOf(contract) : null, CLEARABLE);
    save.mutate({ contract, fields: fields as never }, { onSuccess: onClose });
  };

  return (
    <Stack gap="sm">
      {contract && (
        <Group gap="xs">
          <ContractStatusBadge contract={contract} />
          <Text size="xs" className={classes.muted}>
            {t('assets.contracts.linked', { assets: contract.assets, licenses: contract.licenses })}
            {contract.remindedFor ? ` · ${t('assets.contracts.reminded')}` : ''}
          </Text>
        </Group>
      )}
      <fieldset disabled={readOnly} style={{ border: 0, padding: 0, margin: 0 }}>
        <Stack gap="sm">
          <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="sm">
            <TextInput label={t('assets.contracts.field.number')} required value={v.number} onChange={(e) => set('number')(e.currentTarget.value)} error={req(!!v.number.trim())} classNames={{ input: 'ei-mono' }} />
            <Select label={t('assets.contracts.field.kind')} required value={v.kind || null} onChange={(k) => set('kind')((k as ContractKind) ?? '')} data={CONTRACT_KINDS.map((k) => ({ value: k, label: t(`assets.contracts.kind.${k}`) }))} error={req(!!v.kind)} />
          </SimpleGrid>
          <TextInput label={t('assets.contracts.field.title')} required value={v.title} onChange={(e) => set('title')(e.currentTarget.value)} error={req(!!v.title.trim())} />
          <Select label={t('assets.field.vendor')} clearable searchable value={v.vendorId || null} onChange={(x) => set('vendorId')(x ?? '')} data={(vendors.data ?? []).filter((x) => x.active || x.id === v.vendorId).map((x) => ({ value: x.id, label: x.name }))} />
          <SimpleGrid cols={{ base: 1, sm: 3 }} spacing="sm">
            <TextInput type="date" label={t('assets.contracts.field.startsOn')} required value={v.startsOn} onChange={(e) => set('startsOn')(e.currentTarget.value)} error={req(!!v.startsOn)} />
            <TextInput type="date" label={t('assets.contracts.field.endsOn')} value={v.endsOn} onChange={(e) => set('endsOn')(e.currentTarget.value)} />
            <NumberInput label={t('assets.contracts.field.notice')} min={0} max={365} allowDecimal={false} value={v.renewalNoticeDays} onChange={(x) => set('renewalNoticeDays')(typeof x === 'number' ? x : '')} />
          </SimpleGrid>
          <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="sm">
            <NumberInput label={t('assets.contracts.field.amount')} min={0} decimalScale={2} thousandSeparator=" " value={v.amount} onChange={(x) => set('amount')(typeof x === 'number' ? x : '')} />
            <Select label={t('assets.field.currency')} value={v.currency} onChange={(x) => set('currency')(x ?? 'RUB')} data={['RUB', 'USD', 'EUR', 'CNY', 'KZT', 'BYN']} allowDeselect={false} />
          </SimpleGrid>
          <PersonPicker label={t('assets.contracts.field.owner')} description={t('assets.contracts.field.ownerHint')} value={v.ownerId || null} onChange={(x) => set('ownerId')(x ?? '')} />
          <Group gap="lg">
            <Checkbox label={t('assets.contracts.field.autoRenew')} checked={v.autoRenew} onChange={(e) => set('autoRenew')(e.currentTarget.checked)} />
            {contract && <Checkbox label={t('assets.contracts.field.terminated')} checked={v.terminated} onChange={(e) => set('terminated')(e.currentTarget.checked)} />}
          </Group>
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
