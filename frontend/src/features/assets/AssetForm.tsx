import { Button, Group, Modal, NumberInput, Select, SimpleGrid, Stack, TextInput, Textarea } from '@mantine/core';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { ASSET_KINDS, type Asset, type AssetFields, type AssetKind, useContracts, useCreateAsset, useLocations, useUpdateAsset, useVendors } from './api';
import { CiPicker } from './CiPicker';
import { patchOf } from './ui';

interface Props {
  opened: boolean;
  onClose: () => void;
  /** Изменяемый актив; без него форма создаёт новый */
  asset?: Asset;
  onSaved?: (asset: Asset) => void;
}

type Values = {
  tag: string;
  name: string;
  kind: AssetKind | '';
  manufacturer: string;
  model: string;
  serial: string;
  locationId: string;
  ciId: string;
  vendorId: string;
  contractId: string;
  purchasedOn: string;
  warrantyTo: string;
  serviceLifeMonths: number | '';
  cost: number | '';
  currency: string;
  notes: string;
};

const CLEARABLE: (keyof Values)[] = [
  'manufacturer',
  'model',
  'serial',
  'locationId',
  'ciId',
  'vendorId',
  'contractId',
  'purchasedOn',
  'warrantyTo',
  'serviceLifeMonths',
  'cost',
];

function valuesOf(a?: Asset): Values {
  return {
    tag: a?.tag ?? '',
    name: a?.name ?? '',
    kind: a?.kind ?? '',
    manufacturer: a?.manufacturer ?? '',
    model: a?.model ?? '',
    serial: a?.serial ?? '',
    locationId: a?.location?.id ?? '',
    ciId: a?.ci?.id ?? '',
    vendorId: a?.vendor?.id ?? '',
    contractId: a?.contract?.id ?? '',
    purchasedOn: a?.purchasedOn ?? '',
    warrantyTo: a?.warrantyTo ?? '',
    serviceLifeMonths: a?.serviceLifeMonths ?? '',
    cost: a?.cost ?? '',
    currency: a?.currency ?? 'RUB',
    notes: a?.notes ?? '',
  };
}

/** Создание и изменение актива. Очищенное поле уходит в clear, пустое новое не отправляется. */
export function AssetForm({ opened, onClose, asset, onSaved }: Props) {
  const { t } = useTranslation();
  return (
    <Modal opened={opened} onClose={onClose} size="lg" title={t(asset ? 'assets.form.editTitle' : 'assets.form.createTitle')}>
      {opened && <Body asset={asset} onClose={onClose} onSaved={onSaved} />}
    </Modal>
  );
}

function Body({ asset, onClose, onSaved }: Omit<Props, 'opened'>) {
  const { t } = useTranslation();
  const [v, setV] = useState<Values>(() => valuesOf(asset));
  const [touched, setTouched] = useState(false);
  const locations = useLocations();
  const vendors = useVendors();
  const contracts = useContracts();
  const create = useCreateAsset();
  // Хук изменения нужен только с активом; без него используется заглушка с тем же типом.
  const update = useUpdateAsset(asset ?? ({ id: '', version: 0 } as Asset));
  const mutation = asset ? update : create;

  const set = <K extends keyof Values>(key: K) => (value: Values[K]) => setV((prev) => ({ ...prev, [key]: value }));
  const text = (key: keyof Values) => ({
    value: v[key] as string,
    onChange: (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => set(key)(e.currentTarget.value as never),
  });
  const required = (value: string) => (touched && !value.trim() ? t('assets.form.required') : undefined);

  const submit = () => {
    setTouched(true);
    if (!v.tag.trim() || !v.name.trim() || !v.kind) return;
    const initial = asset ? valuesOf(asset) : null;
    const fields = patchOf({ ...v, tag: v.tag.trim(), name: v.name.trim() }, initial, CLEARABLE) as unknown as AssetFields;
    mutation.mutate(fields, {
      onSuccess: (saved) => {
        onClose();
        onSaved?.(saved);
      },
    });
  };

  return (
    <Stack gap="sm">
      <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="sm">
        <TextInput label={t('assets.field.tag')} required {...text('tag')} error={required(v.tag)} classNames={{ input: 'ei-mono' }} />
        <Select
          label={t('assets.field.kind')}
          required
          value={v.kind || null}
          onChange={(k) => set('kind')((k as AssetKind) ?? '')}
          data={ASSET_KINDS.map((k) => ({ value: k, label: t(`assets.kind.${k}`) }))}
          error={touched && !v.kind ? t('assets.form.required') : undefined}
        />
      </SimpleGrid>
      <TextInput label={t('assets.field.name')} required {...text('name')} error={required(v.name)} />
      <SimpleGrid cols={{ base: 1, sm: 3 }} spacing="sm">
        <TextInput label={t('assets.field.manufacturer')} {...text('manufacturer')} />
        <TextInput label={t('assets.field.model')} {...text('model')} />
        <TextInput label={t('assets.field.serial')} {...text('serial')} classNames={{ input: 'ei-mono' }} />
      </SimpleGrid>
      <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="sm">
        <Select
          label={t('assets.field.location')}
          clearable
          searchable
          value={v.locationId || null}
          onChange={(x) => set('locationId')(x ?? '')}
          data={(locations.data ?? []).filter((l) => l.active || l.id === v.locationId).map((l) => ({ value: l.id, label: l.name }))}
        />
        <Select
          label={t('assets.field.vendor')}
          clearable
          searchable
          value={v.vendorId || null}
          onChange={(x) => set('vendorId')(x ?? '')}
          data={(vendors.data ?? []).filter((x) => x.active || x.id === v.vendorId).map((x) => ({ value: x.id, label: x.name }))}
        />
      </SimpleGrid>
      <Select
        label={t('assets.field.contract')}
        clearable
        searchable
        value={v.contractId || null}
        onChange={(x) => set('contractId')(x ?? '')}
        data={(contracts.data ?? []).map((c) => ({ value: c.id, label: `№ ${c.number} · ${c.title}` }))}
      />
      <SimpleGrid cols={{ base: 1, sm: 3 }} spacing="sm">
        <TextInput type="date" label={t('assets.field.purchasedOn')} {...text('purchasedOn')} />
        <TextInput type="date" label={t('assets.field.warrantyTo')} {...text('warrantyTo')} />
        <NumberInput
          label={t('assets.field.serviceLife')}
          min={1}
          max={600}
          allowDecimal={false}
          value={v.serviceLifeMonths}
          onChange={(x) => set('serviceLifeMonths')(typeof x === 'number' ? x : '')}
        />
      </SimpleGrid>
      <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="sm">
        <NumberInput
          label={t('assets.field.cost')}
          min={0}
          decimalScale={2}
          thousandSeparator=" "
          value={v.cost}
          onChange={(x) => set('cost')(typeof x === 'number' ? x : '')}
        />
        <Select label={t('assets.field.currency')} value={v.currency} onChange={(x) => set('currency')(x ?? 'RUB')} data={['RUB', 'USD', 'EUR', 'CNY', 'KZT', 'BYN']} allowDeselect={false} />
      </SimpleGrid>
      <CiPicker value={v.ciId} current={asset?.ci ?? null} onChange={set('ciId')} />
      <Textarea label={t('assets.field.notes')} autosize minRows={2} maxRows={6} {...text('notes')} />
      <ErrorLine error={mutation.error} />
      <Group justify="flex-end" gap="xs">
        <Button variant="default" onClick={onClose}>
          {t('assets.form.cancel')}
        </Button>
        <Button onClick={submit} loading={mutation.isPending}>
          {t('assets.form.save')}
        </Button>
      </Group>
    </Stack>
  );
}
