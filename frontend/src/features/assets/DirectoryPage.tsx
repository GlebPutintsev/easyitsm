import { Anchor, Button, Card, Checkbox, Group, Modal, SimpleGrid, Stack, Table, Text, TextInput, Textarea, Title } from '@mantine/core';
import { IconPlus } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { hasRole, useMe } from '@/features/auth/api';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import { type Location, type Vendor, type VendorFields, useLocations, useSaveLocation, useSaveVendor, useVendors } from './api';
import { AssetsHeader, ToneBadge } from './ui';
import classes from './assets.module.css';

/** «Активы → Поставщики и места» (AST-04): справочники, на которые ссылаются активы, лицензии и договоры. */
export function DirectoryPage() {
  const me = useMe();
  const canManage = hasRole(me.data, 'AST', 'ADM');
  return (
    <Stack gap="lg" maw={1280}>
      <AssetsHeader />
      <Vendors canManage={canManage} />
      <Locations canManage={canManage} />
    </Stack>
  );
}

function Vendors({ canManage }: { canManage: boolean }) {
  const { t } = useTranslation();
  const vendors = useVendors();
  const rows = vendors.data ?? [];
  const state = listStateOf(vendors, rows.length);
  const [editing, setEditing] = useState<Vendor | 'new' | null>(null);
  return (
    <Stack gap="sm">
      <Group justify="space-between">
        <Title order={2} size="h3">
          {t('assets.directory.vendors')}
        </Title>
        {canManage && (
          <Button size="sm" variant="default" leftSection={<IconPlus size={16} />} onClick={() => setEditing('new')}>
            {t('assets.directory.addVendor')}
          </Button>
        )}
      </Group>
      {state ? (
        <ListState state={state} error={vendors.error} title={state === 'empty' ? t('assets.directory.vendorsEmpty') : undefined} onRetry={() => vendors.refetch()} rows={3} />
      ) : (
        <Card withBorder padding={0} radius="lg" className={classes.tableCard}>
          <Table.ScrollContainer minWidth={720}>
            <Table highlightOnHover verticalSpacing={10} className={classes.table}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t('assets.directory.field.name')}</Table.Th>
                  <Table.Th>{t('assets.directory.field.taxId')}</Table.Th>
                  <Table.Th>{t('assets.directory.field.contactName')}</Table.Th>
                  <Table.Th>{t('assets.directory.field.email')}</Table.Th>
                  <Table.Th>{t('assets.directory.field.phone')}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {rows.map((v) => (
                  <Table.Tr key={v.id} className={canManage ? classes.row : undefined} tabIndex={canManage ? 0 : undefined} onClick={() => canManage && setEditing(v)} onKeyDown={(k) => (canManage && k.key === 'Enter' ? setEditing(v) : undefined)}>
                    <Table.Td>
                      <Group gap="xs">
                        <Text size="sm" fw={600}>
                          {v.name}
                        </Text>
                        {!v.active && <ToneBadge tone="neutral">{t('assets.directory.inactive')}</ToneBadge>}
                      </Group>
                      {v.website && (
                        <Anchor href={v.website} target="_blank" rel="noreferrer noopener" size="xs" c="var(--ei-link)" onClick={(e) => e.stopPropagation()}>
                          {v.website}
                        </Anchor>
                      )}
                    </Table.Td>
                    <Table.Td className="ei-mono">{v.taxId ?? '—'}</Table.Td>
                    <Table.Td>{v.contactName ?? '—'}</Table.Td>
                    <Table.Td>{v.email ?? '—'}</Table.Td>
                    <Table.Td>{v.phone ?? '—'}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Table.ScrollContainer>
        </Card>
      )}
      <Modal opened={!!editing} onClose={() => setEditing(null)} title={t('assets.directory.vendorTitle')}>
        {editing && <VendorForm vendor={editing === 'new' ? undefined : editing} onClose={() => setEditing(null)} />}
      </Modal>
    </Stack>
  );
}

function VendorForm({ vendor, onClose }: { vendor?: Vendor; onClose: () => void }) {
  const { t } = useTranslation();
  const save = useSaveVendor();
  const [v, setV] = useState({
    name: vendor?.name ?? '',
    taxId: vendor?.taxId ?? '',
    contactName: vendor?.contactName ?? '',
    email: vendor?.email ?? '',
    phone: vendor?.phone ?? '',
    website: vendor?.website ?? '',
    notes: vendor?.notes ?? '',
    active: vendor?.active ?? true,
  });
  const [touched, setTouched] = useState(false);
  const text = (key: Exclude<keyof typeof v, 'active'>) => ({
    value: v[key],
    onChange: (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => {
      const value = e.currentTarget.value;
      setV((p) => ({ ...p, [key]: value }));
    },
  });
  const submit = () => {
    setTouched(true);
    if (!v.name.trim()) return;
    // Поставщик: пустая строка очищает поле на сервере, поэтому форма отправляется целиком.
    const fields: VendorFields = { ...v, name: v.name.trim() };
    save.mutate({ vendor, fields }, { onSuccess: onClose });
  };
  return (
    <Stack gap="sm">
      <TextInput label={t('assets.directory.field.name')} required {...text('name')} error={touched && !v.name.trim() ? t('assets.form.required') : undefined} />
      <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="sm">
        <TextInput label={t('assets.directory.field.taxId')} {...text('taxId')} classNames={{ input: 'ei-mono' }} />
        <TextInput label={t('assets.directory.field.contactName')} {...text('contactName')} />
        <TextInput type="email" label={t('assets.directory.field.email')} {...text('email')} />
        <TextInput type="tel" label={t('assets.directory.field.phone')} {...text('phone')} />
      </SimpleGrid>
      <TextInput type="url" label={t('assets.directory.field.website')} placeholder="https://" {...text('website')} />
      <Textarea label={t('assets.field.notes')} autosize minRows={2} {...text('notes')} />
      {vendor && <Checkbox label={t('assets.directory.field.active')} checked={v.active} onChange={(e) => { const checked = e.currentTarget.checked; setV((p) => ({ ...p, active: checked })); }} />}
      <ErrorLine error={save.error} />
      <Group justify="flex-end" gap="xs">
        <Button variant="default" onClick={onClose}>
          {t('assets.form.cancel')}
        </Button>
        <Button onClick={submit} loading={save.isPending}>
          {t('assets.form.save')}
        </Button>
      </Group>
    </Stack>
  );
}

function Locations({ canManage }: { canManage: boolean }) {
  const { t } = useTranslation();
  const locations = useLocations();
  const rows = locations.data ?? [];
  const state = listStateOf(locations, rows.length);
  const [editing, setEditing] = useState<Location | 'new' | null>(null);
  return (
    <Stack gap="sm">
      <Group justify="space-between">
        <Title order={2} size="h3">
          {t('assets.directory.locations')}
        </Title>
        {canManage && (
          <Button size="sm" variant="default" leftSection={<IconPlus size={16} />} onClick={() => setEditing('new')}>
            {t('assets.directory.addLocation')}
          </Button>
        )}
      </Group>
      {state ? (
        <ListState state={state} error={locations.error} title={state === 'empty' ? t('assets.directory.locationsEmpty') : undefined} onRetry={() => locations.refetch()} rows={3} />
      ) : (
        <Card withBorder padding={0} radius="lg" className={classes.tableCard}>
          <Table highlightOnHover verticalSpacing={10} className={classes.table}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('assets.directory.field.name')}</Table.Th>
                <Table.Th>{t('assets.directory.field.address')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((l) => (
                <Table.Tr key={l.id} className={canManage ? classes.row : undefined} tabIndex={canManage ? 0 : undefined} onClick={() => canManage && setEditing(l)} onKeyDown={(k) => (canManage && k.key === 'Enter' ? setEditing(l) : undefined)}>
                  <Table.Td>
                    <Group gap="xs">
                      <Text size="sm" fw={600}>
                        {l.name}
                      </Text>
                      {!l.active && <ToneBadge tone="neutral">{t('assets.directory.inactive')}</ToneBadge>}
                    </Group>
                  </Table.Td>
                  <Table.Td>{l.address ?? '—'}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Card>
      )}
      <Modal opened={!!editing} onClose={() => setEditing(null)} title={t('assets.directory.locationTitle')}>
        {editing && <LocationForm location={editing === 'new' ? undefined : editing} onClose={() => setEditing(null)} />}
      </Modal>
    </Stack>
  );
}

function LocationForm({ location, onClose }: { location?: Location; onClose: () => void }) {
  const { t } = useTranslation();
  const save = useSaveLocation();
  const [name, setName] = useState(location?.name ?? '');
  const [address, setAddress] = useState(location?.address ?? '');
  const [active, setActive] = useState(location?.active ?? true);
  const [touched, setTouched] = useState(false);
  const submit = () => {
    setTouched(true);
    if (!name.trim()) return;
    save.mutate({ location, fields: { name: name.trim(), address, active } }, { onSuccess: onClose });
  };
  return (
    <Stack gap="sm">
      <TextInput label={t('assets.directory.field.name')} required value={name} onChange={(e) => setName(e.currentTarget.value)} error={touched && !name.trim() ? t('assets.form.required') : undefined} />
      <TextInput label={t('assets.directory.field.address')} value={address} onChange={(e) => setAddress(e.currentTarget.value)} />
      {location && <Checkbox label={t('assets.directory.field.active')} checked={active} onChange={(e) => setActive(e.currentTarget.checked)} />}
      <ErrorLine error={save.error} />
      <Group justify="flex-end" gap="xs">
        <Button variant="default" onClick={onClose}>
          {t('assets.form.cancel')}
        </Button>
        <Button onClick={submit} loading={save.isPending}>
          {t('assets.form.save')}
        </Button>
      </Group>
    </Stack>
  );
}
