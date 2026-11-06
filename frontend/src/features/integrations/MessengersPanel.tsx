import {
  Alert,
  Anchor,
  Badge,
  Button,
  Checkbox,
  Group,
  Modal,
  PasswordInput,
  SegmentedControl,
  Select,
  Stack,
  Switch,
  Table,
  Text,
  TextInput,
} from '@mantine/core';
import { IconPlus } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { hasRole, useMe } from '@/features/auth/api';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import {
  DATA_FIELDS,
  type DataField,
  DEFAULT_API,
  MESSENGER_KINDS,
  NEEDS_APP_TOKEN,
  type Messenger,
  type MessengerInput,
  type MessengerKind,
  useCheckMessenger,
  useCreateMessenger,
  useDeleteMessenger,
  useGroups,
  useMessengers,
  useUpdateMessenger,
} from './api';

/** Боты Telegram и MAX: заявки из сообщений, уведомления и перечень передаваемых данных (CHN-04, CHN-06). */
export function MessengersPanel() {
  const { t, i18n } = useTranslation();
  const me = useMe();
  const canEdit = hasRole(me.data, 'ADM');
  const bots = useMessengers();
  const list = bots.data ?? [];
  const state = listStateOf(bots, list.length);
  const update = useUpdateMessenger();
  const [editing, setEditing] = useState<Messenger | 'new' | null>(null);

  return (
    <Stack gap="sm">
      <Group justify="space-between" align="flex-start" wrap="wrap">
        <Text size="sm" c="dimmed" maw={820}>
          {t('integrations.messengers.intro')}
        </Text>
        {canEdit && (
          <Button leftSection={<IconPlus size={16} />} onClick={() => setEditing('new')}>
            {t('integrations.messengers.add')}
          </Button>
        )}
      </Group>
      <ErrorLine error={update.error} />
      {state ? (
        <ListState
          state={state}
          title={state === 'empty' ? t('integrations.messengers.empty') : undefined}
          error={bots.error}
          onRetry={() => void bots.refetch()}
        />
      ) : (
        <Table.ScrollContainer minWidth={760}>
          <Table highlightOnHover verticalSpacing="sm" data-testid="messengers-table">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('integrations.messengers.columns.name')}</Table.Th>
                <Table.Th>{t('integrations.messengers.columns.kind')}</Table.Th>
                <Table.Th>{t('integrations.messengers.columns.users')}</Table.Th>
                <Table.Th>{t('integrations.messengers.columns.state')}</Table.Th>
                <Table.Th w={110}>{t('integrations.monitoring.columns.enabled')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {list.map((m) => (
                <Table.Tr key={m.id} style={{ cursor: 'pointer' }} onClick={() => setEditing(m)}>
                  <Table.Td>
                    <Anchor component="button" type="button" onClick={() => setEditing(m)}>
                      {m.name}
                    </Anchor>
                    {m.botUsername && (
                      <Text size="xs" c="dimmed">
                        @{m.botUsername}
                      </Text>
                    )}
                  </Table.Td>
                  <Table.Td>
                    <Badge variant="light">{t(`integrations.messengers.kinds.${m.kind}`)}</Badge>
                  </Table.Td>
                  <Table.Td>{m.linkedUsers}</Table.Td>
                  <Table.Td>
                    {m.lastError ? (
                      <Text size="xs" c="red">
                        {m.lastError}
                      </Text>
                    ) : (
                      <Text size="xs" c="dimmed">
                        {m.lastPollAt
                          ? t('integrations.messengers.polled', {
                              time: new Date(m.lastPollAt).toLocaleString(i18n.language, { dateStyle: 'short', timeStyle: 'medium' }),
                            })
                          : t('integrations.messengers.waiting')}
                      </Text>
                    )}
                  </Table.Td>
                  <Table.Td onClick={(e) => e.stopPropagation()}>
                    <Switch
                      aria-label={t('integrations.monitoring.enabled')}
                      checked={m.enabled}
                      disabled={!canEdit || update.isPending}
                      onChange={(e) => update.mutate({ id: m.id, version: m.version, input: { enabled: e.currentTarget.checked } })}
                    />
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
      {editing && <MessengerModal current={editing === 'new' ? null : editing} canEdit={canEdit} onClose={() => setEditing(null)} />}
    </Stack>
  );
}

function MessengerModal({ current, canEdit, onClose }: { current: Messenger | null; canEdit: boolean; onClose: () => void }) {
  const { t } = useTranslation();
  const groups = useGroups();
  const [kind, setKind] = useState<MessengerKind>(current?.kind ?? 'telegram');
  const [name, setName] = useState(current?.name ?? '');
  const [token, setToken] = useState('');
  const [appToken, setAppToken] = useState('');
  const needsApp = NEEDS_APP_TOKEN.includes(current?.kind ?? kind);
  const [baseUrl, setBaseUrl] = useState(current?.baseUrl ?? '');
  const [allowTickets, setAllowTickets] = useState(current?.allowTickets ?? true);
  const [ticketType, setTicketType] = useState<'incident' | 'request'>(current?.ticketType ?? 'incident');
  const [groupId, setGroupId] = useState<string | null>(current?.groupId ?? null);
  const [fields, setFields] = useState<DataField[]>(current?.dataFields ?? ['title', 'link']);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const create = useCreateMessenger();
  const update = useUpdateMessenger();
  const remove = useDeleteMessenger();
  const check = useCheckMessenger();
  const pending = create.isPending || update.isPending || remove.isPending;

  const submit = () => {
    const input: MessengerInput = {
      name,
      baseUrl: baseUrl.trim(),
      allowTickets,
      ticketType,
      dataFields: fields,
      ...(groupId ? { groupId } : { clearGroup: true }),
      ...(token.trim() ? { token: token.trim() } : {}),
      ...(needsApp && appToken.trim() ? { appToken: appToken.trim() } : {}),
    };
    if (current) update.mutate({ id: current.id, version: current.version, input }, { onSuccess: onClose });
    else create.mutate({ ...input, kind }, { onSuccess: onClose });
  };

  return (
    <Modal opened onClose={onClose} size="lg" title={current ? current.name : t('integrations.messengers.newTitle')}>
      <Stack gap="sm">
        {!current && (
          <SegmentedControl
            aria-label={t('integrations.messengers.columns.kind')}
            data={MESSENGER_KINDS.map((k) => ({ value: k, label: t(`integrations.messengers.kinds.${k}`) }))}
            value={kind}
            onChange={(v) => setKind(v as MessengerKind)}
          />
        )}
        <TextInput label={t('integrations.messengers.columns.name')} required maxLength={200} value={name} disabled={!canEdit} onChange={(e) => setName(e.currentTarget.value)} />
        <PasswordInput
          label={t('integrations.messengers.token')}
          description={current ? t('integrations.messengers.tokenKeep', { hint: current.tokenHint }) : t(`integrations.messengers.tokenHint.${kind}`)}
          required={!current}
          value={token}
          disabled={!canEdit}
          autoComplete="off"
          onChange={(e) => setToken(e.currentTarget.value)}
        />
        {needsApp && (
          <PasswordInput
            label={t('integrations.messengers.appToken')}
            description={
              current?.appTokenHint
                ? t('integrations.messengers.appTokenKeep', { hint: current.appTokenHint })
                : t('integrations.messengers.appTokenHint')
            }
            required={!current}
            value={appToken}
            disabled={!canEdit}
            autoComplete="off"
            onChange={(e) => setAppToken(e.currentTarget.value)}
          />
        )}
        <TextInput
          label={t('integrations.messengers.baseUrl')}
          description={t('integrations.messengers.baseUrlHint')}
          placeholder={DEFAULT_API[current?.kind ?? kind]}
          value={baseUrl}
          disabled={!canEdit}
          onChange={(e) => setBaseUrl(e.currentTarget.value)}
        />
        <Switch label={t('integrations.messengers.allowTickets')} checked={allowTickets} disabled={!canEdit} onChange={(e) => setAllowTickets(e.currentTarget.checked)} />
        {allowTickets && (
          <Group grow align="flex-start">
            <Select
              label={t('integrations.messengers.ticketType')}
              data={[
                { value: 'incident', label: t('integrations.messengers.types.incident') },
                { value: 'request', label: t('integrations.messengers.types.request') },
              ]}
              value={ticketType}
              allowDeselect={false}
              disabled={!canEdit}
              onChange={(v) => setTicketType((v as 'incident' | 'request') ?? 'incident')}
            />
            <Select
              label={t('integrations.monitoring.group')}
              placeholder={t('integrations.monitoring.routing')}
              searchable
              clearable
              data={(groups.data ?? []).map((g) => ({ value: g.id, label: g.name }))}
              value={groupId}
              disabled={!canEdit}
              onChange={setGroupId}
            />
          </Group>
        )}
        <Checkbox.Group
          label={t('integrations.messengers.fields')}
          description={t('integrations.messengers.fieldsHint')}
          value={fields}
          onChange={(v) => setFields(v as DataField[])}
        >
          <Stack gap={6} mt={6}>
            {DATA_FIELDS.map((f) => (
              <Checkbox key={f} value={f} disabled={!canEdit} label={t(`integrations.messengers.field.${f}`)} />
            ))}
          </Stack>
        </Checkbox.Group>
        {current && canEdit && (
          <Group gap="xs">
            <Button variant="default" loading={check.isPending} onClick={() => check.mutate(current.id)}>
              {t('integrations.messengers.check')}
            </Button>
            {check.data && (
              <Badge color={check.data.ok ? 'green' : 'red'} variant="light" data-testid="messenger-check">
                {check.data.ok ? `@${check.data.botUsername}` : check.data.error}
              </Badge>
            )}
          </Group>
        )}
        {current?.lastError && (
          <Alert color="red" variant="light">
            {current.lastError}
          </Alert>
        )}
        <ErrorLine error={create.error ?? update.error ?? remove.error ?? check.error} />
        <Group justify="space-between">
          <Group gap="xs">
            {current && canEdit && (confirmDelete ? (
              <Button color="red" loading={remove.isPending} disabled={pending} onClick={() => remove.mutate(current.id, { onSuccess: onClose })}>
                {t('integrations.messengers.confirmDelete')}
              </Button>
            ) : (
              <Button variant="subtle" color="red" disabled={pending} onClick={() => setConfirmDelete(true)}>
                {t('integrations.webhooks.delete')}
              </Button>
            ))}
          </Group>
          <Group gap="xs">
            <Button variant="default" onClick={onClose}>
              {t('integrations.webhooks.cancel')}
            </Button>
            {canEdit && (
              <Button
                loading={create.isPending || update.isPending}
                disabled={pending || !name.trim() || (!current && (!token.trim() || (needsApp && !appToken.trim())))}
                onClick={submit}
              >
                {current ? t('integrations.webhooks.save') : t('integrations.messengers.connect')}
              </Button>
            )}
          </Group>
        </Group>
      </Stack>
    </Modal>
  );
}
