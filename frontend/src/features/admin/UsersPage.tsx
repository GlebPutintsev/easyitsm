import { ActionIcon, Badge, Button, Group, Menu, Modal, Select, Stack, Table, Text, TextInput, Title } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { notifications } from '@mantine/notifications';
import { IconDots, IconFileImport, IconRefresh, IconUserPlus } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { savedTenant } from '@/features/auth/api';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import { type OrgUnit, useOrgUnits } from './api';
import { type UserRow, useUserMutations, useUsers } from './moduleApi';
import { HistoryDrawer, formatTime } from './ui/History';
import classes from './ui/admin.module.css';

/** «Пользователи» (10.16): Пригласить, Синхронизировать, Блокировать, Сбросить MFA, Завершить сессии. */
export function UsersPage() {
  const { t, i18n } = useTranslation();
  const [q, setQ] = useState('');
  const [debounced] = useDebouncedValue(q, 300);
  const [state, setState] = useState<string | null>(null);
  const users = useUsers(debounced, state);
  const units = useOrgUnits();
  const m = useUserMutations();
  const [inviting, setInviting] = useState(false);
  const [editing, setEditing] = useState<UserRow | null>(null);
  const [history, setHistory] = useState(false);
  const rows = users.data?.pages.flatMap((p) => p.items) ?? [];
  const listState = listStateOf(users, rows.length, !!(debounced || state));
  const unitName = (id: string | null) => units.data?.find((u) => u.id === id)?.name ?? '—';
  const actionError = m.block.error ?? m.unblock.error ?? m.revokeSessions.error ?? m.resetMfa.error ?? m.sync.error;

  function done(key: string, values?: Record<string, unknown>) {
    notifications.show({ message: t(key, values), color: 'green' });
  }

  return (
    <Stack gap="md" maw={1200}>
      <Group justify="space-between" wrap="wrap">
        <Title order={2}>{t('admin.users.title')}</Title>
        <Group gap="xs">
          <Button variant="default" leftSection={<IconRefresh size={16} />} loading={m.sync.isPending}
            onClick={() => m.sync.mutate(undefined, { onSuccess: (r) => done('admin.users.synced', r) })}>
            {t('admin.users.sync')}
          </Button>
          <Button variant="default" leftSection={<IconFileImport size={16} />} component={Link} to="/admin/import?kind=users">
            {t('admin.users.import')}
          </Button>
          <Button variant="subtle" onClick={() => setHistory(true)}>
            {t('admin.history.button')}
          </Button>
          <Button leftSection={<IconUserPlus size={16} />} onClick={() => setInviting(true)}>
            {t('admin.users.invite')}
          </Button>
        </Group>
      </Group>
      <Group gap="xs">
        <TextInput placeholder={t('admin.users.search')} aria-label={t('admin.users.search')} value={q}
          onChange={(e) => setQ(e.currentTarget.value)} w={280} />
        <Select placeholder={t('admin.users.allStates')} aria-label={t('admin.users.state')} clearable value={state}
          onChange={setState} data={[
            { value: 'active', label: t('admin.users.states.active') },
            { value: 'blocked', label: t('admin.users.states.blocked') },
          ]} />
      </Group>
      <ErrorLine error={actionError} />
      {listState ? (
        <ListState state={listState} error={users.error} onRetry={() => void users.refetch()}
          onResetFilters={() => { setQ(''); setState(null); }} />
      ) : (
        <div className={classes.scroll}>
          <Table highlightOnHover verticalSpacing={8} miw={760}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('admin.users.name')}</Table.Th>
                <Table.Th>{t('admin.users.login')}</Table.Th>
                <Table.Th>{t('admin.users.department')}</Table.Th>
                <Table.Th>{t('admin.users.source')}</Table.Th>
                <Table.Th>{t('admin.users.lastLogin')}</Table.Th>
                <Table.Th>{t('admin.users.state')}</Table.Th>
                <Table.Th w={48} />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((u) => (
                <Table.Tr key={u.id}>
                  <Table.Td>
                    <Text size="sm" fw={500}>{u.displayName}</Text>
                    <Text size="xs" className={classes.muted}>{u.email ?? '—'}</Text>
                  </Table.Td>
                  <Table.Td className={classes.mono}>{u.login}</Table.Td>
                  <Table.Td>{unitName(u.departmentId)}</Table.Td>
                  <Table.Td>{t(`admin.users.sources.${u.source}`)}</Table.Td>
                  <Table.Td>{formatTime(u.lastLoginAt, i18n.language)}</Table.Td>
                  <Table.Td>
                    <Badge variant={u.state === 'active' ? 'light' : 'outline'} color={u.state === 'active' ? 'green' : 'red'}>
                      {t(`admin.users.states.${u.state}`)}
                    </Badge>
                  </Table.Td>
                  <Table.Td>
                    <Menu position="bottom-end" withinPortal>
                      <Menu.Target>
                        <ActionIcon variant="subtle" aria-label={t('admin.users.actionsFor', { name: u.displayName })}>
                          <IconDots size={18} />
                        </ActionIcon>
                      </Menu.Target>
                      <Menu.Dropdown>
                        <Menu.Item onClick={() => setEditing(u)}>{t('admin.users.edit')}</Menu.Item>
                        {u.state === 'active' ? (
                          <Menu.Item color="red" onClick={() => m.block.mutate(u.id, { onSuccess: () => done('admin.users.blocked') })}>
                            {t('admin.users.block')}
                          </Menu.Item>
                        ) : (
                          <Menu.Item onClick={() => m.unblock.mutate(u.id, { onSuccess: () => done('admin.users.unblocked') })}>
                            {t('admin.users.unblock')}
                          </Menu.Item>
                        )}
                        <Menu.Item onClick={() => m.revokeSessions.mutate(u.id, { onSuccess: (r) => done('admin.users.revoked', r) })}>
                          {t('admin.users.revokeSessions')}
                        </Menu.Item>
                        <Menu.Item onClick={() => m.resetMfa.mutate(u.id, { onSuccess: () => done('admin.users.mfaReset') })}>
                          {t('admin.users.resetMfa')}
                        </Menu.Item>
                      </Menu.Dropdown>
                    </Menu>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
          {users.hasNextPage && (
            <Button variant="subtle" mt="sm" onClick={() => void users.fetchNextPage()} loading={users.isFetchingNextPage}>
              {t('admin.more')}
            </Button>
          )}
        </div>
      )}
      <Text size="xs" className={classes.muted}>{t('admin.users.mfaNote')}</Text>
      <InviteModal opened={inviting} onClose={() => setInviting(false)} units={units.data ?? []} />
      {editing && <EditModal user={editing} onClose={() => setEditing(null)} units={units.data ?? []} />}
      <HistoryDrawer opened={history} onClose={() => setHistory(false)} entity="identity.user" title={t('admin.users.title')} />
    </Stack>
  );
}

function departmentOptions(units: OrgUnit[]) {
  return units.filter((u) => u.kind === 'department' && u.active).map((u) => ({ value: u.id, label: u.name }));
}

function InviteModal({ opened, onClose, units }: { opened: boolean; onClose: () => void; units: OrgUnit[] }) {
  const { t } = useTranslation();
  const invite = useUserMutations().invite;
  const empty = { login: '', displayName: '', email: '', departmentId: null as string | null };
  const [form, setForm] = useState(empty);
  const close = () => {
    setForm(empty);
    invite.reset();
    onClose();
  };
  return (
    <Modal opened={opened} onClose={close} title={t('admin.users.inviteTitle')}>
      <Stack gap="sm">
        <TextInput label={t('admin.users.displayName')} required value={form.displayName}
          onChange={(e) => setForm({ ...form, displayName: e.currentTarget.value })} />
        <TextInput label={t('admin.users.login')} required value={form.login}
          onChange={(e) => setForm({ ...form, login: e.currentTarget.value.trim() })} />
        <TextInput label={t('admin.users.email')} type="email" value={form.email}
          description={t('admin.users.inviteEmailHint')}
          onChange={(e) => setForm({ ...form, email: e.currentTarget.value.trim() })} />
        <Select label={t('admin.users.department')} clearable searchable data={departmentOptions(units)}
          value={form.departmentId} onChange={(v) => setForm({ ...form, departmentId: v })} />
        <ErrorLine error={invite.error} />
        <Group justify="flex-end">
          <Button variant="default" onClick={close}>{t('admin.cancel')}</Button>
          <Button disabled={!form.login || !form.displayName} loading={invite.isPending}
            onClick={() => invite.mutate({ ...form, tenant: savedTenant() }, { onSuccess: close })}>
            {t('admin.users.invite')}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}

function EditModal({ user, onClose, units }: { user: UserRow; onClose: () => void; units: OrgUnit[] }) {
  const { t } = useTranslation();
  const update = useUserMutations().update;
  const [form, setForm] = useState({ displayName: user.displayName, email: user.email ?? '', departmentId: user.departmentId });
  return (
    <Modal opened onClose={onClose} title={user.displayName}>
      <Stack gap="sm">
        <TextInput label={t('admin.users.displayName')} value={form.displayName} disabled={user.source !== 'local'}
          onChange={(e) => setForm({ ...form, displayName: e.currentTarget.value })} />
        <TextInput label={t('admin.users.email')} value={form.email} disabled={user.source !== 'local'}
          onChange={(e) => setForm({ ...form, email: e.currentTarget.value.trim() })} />
        <Select label={t('admin.users.department')} clearable searchable data={departmentOptions(units)}
          value={form.departmentId} onChange={(v) => setForm({ ...form, departmentId: v })} />
        <ErrorLine error={update.error} />
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>{t('admin.cancel')}</Button>
          <Button loading={update.isPending} onClick={() => update.mutate({ user, ...form }, { onSuccess: onClose })}>
            {t('admin.save')}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
