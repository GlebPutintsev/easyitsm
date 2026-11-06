import './i18n';
import {
  Alert,
  Anchor,
  Badge,
  Button,
  Group,
  Modal,
  NumberInput,
  PasswordInput,
  SegmentedControl,
  Select,
  Stack,
  Switch,
  Table,
  Tabs,
  Text,
  TextInput,
  Textarea,
  Title,
  Tooltip,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconPlus } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router';
import { newIdempotencyKey } from '@/shared/api/client';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import {
  checkMailbox,
  type Mailbox,
  type MailboxInput,
  pollMailbox,
  type Security,
  testTransport,
  type Transport,
  type TransportInput,
  useDeleteMailbox,
  useGroups,
  useInbound,
  useMailboxes,
  useOutgoing,
  useRelease,
  useRetry,
  useSaveMailbox,
  useSaveTransport,
  useTransport,
} from './api';

/** Почтовый канал (CHN-01, CHN-02): отправка, ящики, журналы входящих и исходящих писем. */
export function MailSettingsPage() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const tab = params.get('tab') ?? 'transport';
  return (
    <Stack gap="md" maw={1200}>
      <Title order={2}>{t('mail.title')}</Title>
      <Tabs
        value={tab}
        onChange={(v) => setParams(v && v !== 'transport' ? { tab: v } : {}, { replace: true })}
        keepMounted={false}
      >
        <Tabs.List>
          <Tabs.Tab value="transport">{t('mail.tabs.transport')}</Tabs.Tab>
          <Tabs.Tab value="mailboxes">{t('mail.tabs.mailboxes')}</Tabs.Tab>
          <Tabs.Tab value="inbound">{t('mail.tabs.inbound')}</Tabs.Tab>
          <Tabs.Tab value="outgoing">{t('mail.tabs.outgoing')}</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="transport" pt="md">
          <TransportPanel />
        </Tabs.Panel>
        <Tabs.Panel value="mailboxes" pt="md">
          <MailboxesPanel />
        </Tabs.Panel>
        <Tabs.Panel value="inbound" pt="md">
          <InboundPanel />
        </Tabs.Panel>
        <Tabs.Panel value="outgoing" pt="md">
          <OutgoingPanel />
        </Tabs.Panel>
      </Tabs>
    </Stack>
  );
}

function dateTime(iso: string | null, locale: string): string {
  if (!iso) return '—';
  return new Intl.DateTimeFormat(locale, { dateStyle: 'short', timeStyle: 'short' }).format(new Date(iso));
}

const SECURITY: Security[] = ['starttls', 'ssl', 'none'];

function securityOptions(t: (k: string) => string) {
  return SECURITY.map((s) => ({ value: s, label: s === 'none' ? t('mail.transport.securityNone') : s.toUpperCase() }));
}

// ================================================================ отправка

function TransportPanel() {
  const transport = useTransport();
  if (transport.isPending) return <ListState state="loading" rows={6} />;
  if (transport.isError) return <ListState state={listStateOf(transport, 1) ?? 'error'} error={transport.error} />;
  return <TransportForm key={transport.data?.version ?? 'new'} current={transport.data ?? null} />;
}

function TransportForm({ current }: { current: Transport | null }) {
  const { t } = useTranslation();
  const save = useSaveTransport();
  const [form, setForm] = useState<TransportInput>(() => ({
    protocol: current?.protocol ?? 'smtp',
    host: current?.host ?? '',
    port: current?.port ?? 587,
    security: current?.security ?? 'starttls',
    username: current?.username ?? '',
    graphTenant: current?.graphTenant ?? '',
    graphClientId: current?.graphClientId ?? '',
    fromAddress: current?.fromAddress ?? '',
    fromName: current?.fromName ?? 'Easy ITSM',
    replyTo: current?.replyTo ?? '',
    enabled: current?.enabled ?? true,
  }));
  const [secret, setSecret] = useState('');
  const [testTo, setTestTo] = useState('');
  const [testing, setTesting] = useState(false);
  const set = <K extends keyof TransportInput>(k: K, v: TransportInput[K]) => setForm((f) => ({ ...f, [k]: v }));
  const graph = form.protocol === 'graph';
  const secretSet = current != null && !current.fallback && current.secretSet;

  const submit = () =>
    save.mutate(
      {
        input: {
          ...form,
          replyTo: form.replyTo || null,
          secret: secret ? secret : undefined,
        },
        current,
      },
      {
        onSuccess: () => {
          setSecret('');
          notifications.show({ color: 'green', message: t('mail.transport.saved') });
        },
      },
    );

  const test = async () => {
    setTesting(true);
    try {
      const r = await testTransport(testTo.trim());
      notifications.show(
        r.ok
          ? { color: 'green', message: t('mail.transport.testOk') }
          : { color: 'red', title: t('mail.transport.testFailed'), message: r.error ?? '' },
      );
    } catch (e) {
      notifications.show({ color: 'red', title: t('mail.transport.testFailed'), message: (e as Error).message });
    } finally {
      setTesting(false);
    }
  };

  return (
    <Stack gap="md" maw={720}>
      <Text size="sm" c="dimmed">
        {t('mail.transport.intro')}
      </Text>
      {!current && <Alert color="yellow">{t('mail.transport.none')}</Alert>}
      {current?.fallback && <Alert color="gray">{t('mail.transport.fallback')}</Alert>}
      <SegmentedControl
        w="fit-content"
        value={form.protocol}
        onChange={(v) => set('protocol', v as TransportInput['protocol'])}
        data={[
          { value: 'smtp', label: t('mail.transport.smtp') },
          { value: 'graph', label: t('mail.transport.graph') },
        ]}
      />
      {graph ? (
        <Group grow align="flex-start">
          <TextInput label={t('mail.transport.graphTenant')} value={form.graphTenant ?? ''} onChange={(e) => set('graphTenant', e.currentTarget.value)} />
          <TextInput label={t('mail.transport.graphClientId')} value={form.graphClientId ?? ''} onChange={(e) => set('graphClientId', e.currentTarget.value)} />
        </Group>
      ) : (
        <>
          <Group grow align="flex-start">
            <TextInput label={t('mail.transport.host')} value={form.host ?? ''} onChange={(e) => set('host', e.currentTarget.value)} />
            <NumberInput label={t('mail.transport.port')} min={1} max={65535} maw={140} value={form.port ?? ''} onChange={(v) => set('port', typeof v === 'number' ? v : null)} />
            <Select
              label={t('mail.transport.security')}
              maw={180}
              data={securityOptions(t)}
              value={form.security ?? 'starttls'}
              allowDeselect={false}
              onChange={(v) => set('security', (v ?? 'starttls') as Security)}
            />
          </Group>
          <TextInput label={t('mail.transport.username')} value={form.username ?? ''} autoComplete="off" onChange={(e) => set('username', e.currentTarget.value)} />
        </>
      )}
      <PasswordInput
        label={graph ? t('mail.transport.clientSecret') : t('mail.transport.password')}
        description={secretSet ? t('mail.transport.secretSet') : undefined}
        value={secret}
        autoComplete="new-password"
        onChange={(e) => setSecret(e.currentTarget.value)}
      />
      <Group grow align="flex-start">
        <TextInput required type="email" label={t('mail.transport.fromAddress')} value={form.fromAddress} onChange={(e) => set('fromAddress', e.currentTarget.value)} />
        <TextInput label={t('mail.transport.fromName')} value={form.fromName ?? ''} onChange={(e) => set('fromName', e.currentTarget.value)} />
      </Group>
      <TextInput
        type="email"
        label={t('mail.transport.replyTo')}
        description={t('mail.transport.replyToHint')}
        value={form.replyTo ?? ''}
        onChange={(e) => set('replyTo', e.currentTarget.value)}
      />
      <Switch label={t('mail.transport.enabled')} checked={form.enabled} onChange={(e) => set('enabled', e.currentTarget.checked)} />
      <ErrorLine error={save.error} />
      <Group>
        <Button onClick={submit} loading={save.isPending} disabled={!form.fromAddress.trim()}>
          {t('mail.transport.save')}
        </Button>
      </Group>
      {current && (
        <Group align="flex-end" mt="md">
          <TextInput
            type="email"
            label={t('mail.transport.testTo')}
            placeholder={t('mail.transport.testToHint')}
            value={testTo}
            onChange={(e) => setTestTo(e.currentTarget.value)}
            w={320}
          />
          <Button variant="light" loading={testing} onClick={() => void test()}>
            {t('mail.transport.test')}
          </Button>
        </Group>
      )}
    </Stack>
  );
}

// ================================================================ ящики

function MailboxesPanel() {
  const { t, i18n } = useTranslation();
  const boxes = useMailboxes();
  const groups = useGroups();
  const remove = useDeleteMailbox();
  const [editing, setEditing] = useState<Mailbox | 'new' | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const rows = boxes.data ?? [];
  const state = listStateOf(boxes, rows.length);
  const groupName = (id: string | null) => (id ? groups.data?.find((g) => g.id === id)?.name ?? '—' : '—');

  const run = async (box: Mailbox, action: 'check' | 'poll') => {
    setBusy(`${box.id}:${action}`);
    try {
      if (action === 'check') {
        const r = await checkMailbox(box.id);
        notifications.show(r.ok ? { color: 'green', message: t('mail.mailboxes.checkOk') } : { color: 'red', message: r.error ?? '' });
      } else {
        const r = await pollMailbox(box.id);
        notifications.show(
          r.error ? { color: 'red', message: r.error } : { color: 'green', message: t('mail.mailboxes.polled', { count: r.processed }) },
        );
      }
      await boxes.refetch();
    } catch (e) {
      notifications.show({ color: 'red', message: (e as Error).message });
    } finally {
      setBusy(null);
    }
  };

  return (
    <Stack gap="md">
      <Group justify="space-between" align="flex-start">
        <Text size="sm" c="dimmed" maw={760}>
          {t('mail.mailboxes.intro')}
        </Text>
        <Button leftSection={<IconPlus size={16} />} onClick={() => setEditing('new')}>
          {t('mail.mailboxes.add')}
        </Button>
      </Group>
      {state ? (
        <ListState state={state} title={state === 'empty' ? t('mail.mailboxes.empty') : undefined} error={boxes.error} onRetry={() => void boxes.refetch()} />
      ) : (
        <Table verticalSpacing="xs" highlightOnHover>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t('mail.mailboxes.address')}</Table.Th>
              <Table.Th>{t('mail.mailboxes.protocol')}</Table.Th>
              <Table.Th>{t('mail.mailboxes.group')}</Table.Th>
              <Table.Th>{t('mail.mailboxes.authPolicy')}</Table.Th>
              <Table.Th>{t('mail.mailboxes.status')}</Table.Th>
              <Table.Th />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {rows.map((box) => (
              <Table.Tr key={box.id}>
                <Table.Td>
                  <Anchor component="button" size="sm" fw={500} onClick={() => setEditing(box)}>
                    {box.address}
                  </Anchor>
                  <Text size="xs" c="dimmed">
                    {t(`mail.mailboxes.${box.ticketType}`)}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <Text size="sm">{t(`mail.mailboxes.${box.protocol}`)}</Text>
                </Table.Td>
                <Table.Td>
                  <Text size="sm">{groupName(box.groupId)}</Text>
                </Table.Td>
                <Table.Td>
                  <Text size="sm">{t(`mail.mailboxes.policy.${box.authPolicy}`)}</Text>
                </Table.Td>
                <Table.Td>
                  {!box.enabled ? (
                    <Badge variant="outline" color="gray" size="sm">
                      {t('mail.mailboxes.off')}
                    </Badge>
                  ) : box.lastError ? (
                    <Tooltip label={box.lastError} multiline w={360}>
                      <Badge variant="light" color="red" size="sm">
                        {t('mail.inbound.verdicts.error')}
                      </Badge>
                    </Tooltip>
                  ) : (
                    <Badge variant="light" color="green" size="sm">
                      {t('mail.mailboxes.ok')}
                    </Badge>
                  )}
                  {box.protocol !== 'smtp' && (
                    <Text size="xs" c="dimmed">
                      {t('mail.mailboxes.lastPolled')}: {box.lastPolledAt ? dateTime(box.lastPolledAt, i18n.language) : t('mail.mailboxes.never')}
                    </Text>
                  )}
                </Table.Td>
                <Table.Td>
                  <Group gap={4} justify="flex-end" wrap="nowrap">
                    {box.protocol !== 'smtp' && (
                      <>
                        <Button size="compact-sm" variant="subtle" loading={busy === `${box.id}:check`} onClick={() => void run(box, 'check')}>
                          {t('mail.mailboxes.check')}
                        </Button>
                        <Button size="compact-sm" variant="subtle" loading={busy === `${box.id}:poll`} onClick={() => void run(box, 'poll')}>
                          {t('mail.mailboxes.poll')}
                        </Button>
                      </>
                    )}
                    <Button
                      size="compact-sm"
                      variant="subtle"
                      color="red"
                      loading={remove.isPending && remove.variables === box.id}
                      onClick={() => {
                        if (window.confirm(t('mail.mailboxes.deleteConfirm', { address: box.address }))) remove.mutate(box.id);
                      }}
                    >
                      {t('mail.mailboxes.delete')}
                    </Button>
                  </Group>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
      <ErrorLine error={remove.error} />
      {editing && <MailboxEditor current={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </Stack>
  );
}

function MailboxEditor({ current, onClose }: { current: Mailbox | null; onClose: () => void }) {
  const { t } = useTranslation();
  const groups = useGroups();
  const save = useSaveMailbox();
  const [key] = useState(newIdempotencyKey);
  const [secret, setSecret] = useState('');
  const [form, setForm] = useState<MailboxInput>(() => ({
    address: current?.address ?? '',
    protocol: current?.protocol ?? 'imap',
    host: current?.host ?? '',
    port: current?.port ?? 993,
    security: current?.security ?? 'ssl',
    username: current?.username ?? '',
    folder: current?.folder ?? 'INBOX',
    graphTenant: current?.graphTenant ?? '',
    graphClientId: current?.graphClientId ?? '',
    ticketType: current?.ticketType ?? 'incident',
    groupId: current?.groupId ?? null,
    authPolicy: current?.authPolicy ?? 'enforce',
    trustedAuthserv: current?.trustedAuthserv ?? '',
    enabled: current?.enabled ?? true,
  }));
  const set = <K extends keyof MailboxInput>(k: K, v: MailboxInput[K]) => setForm((f) => ({ ...f, [k]: v }));

  const submit = () =>
    save.mutate(
      { input: { ...form, secret: secret ? secret : undefined }, current, idempotencyKey: key },
      { onSuccess: onClose },
    );

  return (
    <Modal
      opened
      onClose={onClose}
      size="lg"
      title={current ? t('mail.mailboxes.editTitle', { address: current.address }) : t('mail.mailboxes.newTitle')}
    >
      <Stack gap="sm">
        <TextInput required type="email" label={t('mail.mailboxes.address')} value={form.address} onChange={(e) => set('address', e.currentTarget.value)} />
        <SegmentedControl
          value={form.protocol}
          onChange={(v) => set('protocol', v as MailboxInput['protocol'])}
          data={(['imap', 'graph', 'smtp'] as const).map((p) => ({ value: p, label: t(`mail.mailboxes.${p}`) }))}
        />
        {form.protocol === 'imap' && (
          <>
            <Group grow align="flex-start">
              <TextInput label={t('mail.transport.host')} value={form.host ?? ''} onChange={(e) => set('host', e.currentTarget.value)} />
              <NumberInput label={t('mail.transport.port')} min={1} max={65535} maw={120} value={form.port ?? ''} onChange={(v) => set('port', typeof v === 'number' ? v : null)} />
              <Select
                label={t('mail.transport.security')}
                maw={160}
                data={securityOptions(t)}
                value={form.security ?? 'ssl'}
                allowDeselect={false}
                onChange={(v) => set('security', (v ?? 'ssl') as Security)}
              />
            </Group>
            <Group grow align="flex-start">
              <TextInput label={t('mail.transport.username')} value={form.username ?? ''} autoComplete="off" onChange={(e) => set('username', e.currentTarget.value)} />
              <TextInput label={t('mail.mailboxes.folder')} value={form.folder ?? ''} onChange={(e) => set('folder', e.currentTarget.value)} />
            </Group>
          </>
        )}
        {form.protocol === 'graph' && (
          <Group grow align="flex-start">
            <TextInput label={t('mail.transport.graphTenant')} value={form.graphTenant ?? ''} onChange={(e) => set('graphTenant', e.currentTarget.value)} />
            <TextInput label={t('mail.transport.graphClientId')} value={form.graphClientId ?? ''} onChange={(e) => set('graphClientId', e.currentTarget.value)} />
          </Group>
        )}
        {form.protocol === 'smtp' ? (
          <Text size="sm" c="dimmed">
            {t('mail.mailboxes.smtpHint')}
          </Text>
        ) : (
          <PasswordInput
            label={form.protocol === 'graph' ? t('mail.transport.clientSecret') : t('mail.transport.password')}
            description={current?.secretSet ? t('mail.transport.secretSet') : undefined}
            value={secret}
            autoComplete="new-password"
            onChange={(e) => setSecret(e.currentTarget.value)}
          />
        )}
        <Group grow align="flex-start">
          <Select
            label={t('mail.mailboxes.ticketType')}
            data={[
              { value: 'incident', label: t('mail.mailboxes.incident') },
              { value: 'request', label: t('mail.mailboxes.request') },
            ]}
            value={form.ticketType}
            allowDeselect={false}
            onChange={(v) => set('ticketType', (v ?? 'incident') as MailboxInput['ticketType'])}
          />
          <Select
            label={t('mail.mailboxes.group')}
            placeholder={t('mail.mailboxes.groupNone')}
            data={(groups.data ?? []).map((g) => ({ value: g.id, label: g.name }))}
            value={form.groupId}
            clearable
            searchable
            onChange={(v) => set('groupId', v)}
          />
        </Group>
        <Select
          label={t('mail.mailboxes.authPolicy')}
          description={t(`mail.mailboxes.policyHint.${form.authPolicy}`)}
          data={(['enforce', 'strict', 'monitor'] as const).map((p) => ({ value: p, label: t(`mail.mailboxes.policy.${p}`) }))}
          value={form.authPolicy}
          allowDeselect={false}
          onChange={(v) => set('authPolicy', (v ?? 'enforce') as MailboxInput['authPolicy'])}
        />
        <Textarea
          label={t('mail.mailboxes.trustedAuthserv')}
          description={t('mail.mailboxes.trustedAuthservHint')}
          autosize
          minRows={1}
          value={form.trustedAuthserv ?? ''}
          onChange={(e) => set('trustedAuthserv', e.currentTarget.value)}
        />
        <Switch label={t('mail.mailboxes.enabled')} checked={form.enabled} onChange={(e) => set('enabled', e.currentTarget.checked)} />
        <ErrorLine error={save.error} />
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            {t('mail.mailboxes.cancel')}
          </Button>
          <Button onClick={submit} loading={save.isPending} disabled={!form.address.trim()}>
            {t('mail.mailboxes.save')}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}

// ================================================================ журналы

const VERDICTS = [
  'ticket_created',
  'comment_added',
  'quarantined',
  'auth_failed',
  'unknown_sender',
  'auto_reply',
  'loop',
  'rate_limited',
  'released',
  'error',
];

const verdictColor: Record<string, string> = {
  ticket_created: 'green',
  comment_added: 'green',
  released: 'green',
  quarantined: 'yellow',
  auth_failed: 'red',
  error: 'red',
};

function authBadge(label: string, value: string | null) {
  if (!value) return null;
  const color = value === 'pass' ? 'green' : value === 'fail' ? 'red' : 'gray';
  return (
    <Badge key={label} variant="light" color={color} size="xs" tt="none">
      {label} {value}
    </Badge>
  );
}

function InboundPanel() {
  const { t, i18n } = useTranslation();
  const [verdict, setVerdict] = useState<string | null>(null);
  const list = useInbound(verdict);
  const release = useRelease();
  const rows = list.data?.pages.flatMap((p) => p.items) ?? [];
  const state = listStateOf(list, rows.length, verdict != null);

  return (
    <Stack gap="md">
      <Group justify="space-between" align="flex-end">
        <Text size="sm" c="dimmed" maw={640}>
          {t('mail.inbound.intro')}
        </Text>
        <Select
          label={t('mail.inbound.filter')}
          w={240}
          clearable
          placeholder={t('mail.inbound.all')}
          data={VERDICTS.map((v) => ({ value: v, label: t(`mail.inbound.verdicts.${v}`) }))}
          value={verdict}
          onChange={setVerdict}
        />
      </Group>
      <ErrorLine error={release.error} />
      {state ? (
        <ListState
          state={state}
          title={state === 'empty' ? t('mail.inbound.empty') : undefined}
          error={list.error}
          onRetry={() => void list.refetch()}
          onResetFilters={() => setVerdict(null)}
        />
      ) : (
        <>
          <Table verticalSpacing="xs">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('mail.inbound.received')}</Table.Th>
                <Table.Th>{t('mail.inbound.from')}</Table.Th>
                <Table.Th>{t('mail.inbound.subject')}</Table.Th>
                <Table.Th>{t('mail.inbound.auth')}</Table.Th>
                <Table.Th>{t('mail.inbound.verdict')}</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((m) => (
                <Table.Tr key={m.id}>
                  <Table.Td>
                    <Text size="sm" style={{ whiteSpace: 'nowrap' }}>
                      {dateTime(m.receivedAt, i18n.language)}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm">{m.fromName || m.fromAddress}</Text>
                    {m.fromName && (
                      <Text size="xs" c="dimmed">
                        {m.fromAddress}
                      </Text>
                    )}
                  </Table.Td>
                  <Table.Td maw={320}>
                    <Text size="sm" lineClamp={2}>
                      {m.subject}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Group gap={4}>
                      {authBadge('SPF', m.spf)}
                      {authBadge('DKIM', m.dkim)}
                      {authBadge('DMARC', m.dmarc)}
                    </Group>
                  </Table.Td>
                  <Table.Td>
                    <Tooltip label={m.detail} disabled={!m.detail} multiline w={360}>
                      <Badge variant="light" color={verdictColor[m.verdict] ?? 'gray'} size="sm" tt="none">
                        {t(`mail.inbound.verdicts.${m.verdict}`, { defaultValue: m.verdict })}
                      </Badge>
                    </Tooltip>
                  </Table.Td>
                  <Table.Td ta="right">
                    {m.canRelease ? (
                      <Button
                        size="compact-sm"
                        variant="light"
                        loading={release.isPending && release.variables === m.id}
                        onClick={() =>
                          release.mutate(m.id, {
                            onSuccess: () => notifications.show({ color: 'green', message: t('mail.inbound.released') }),
                          })
                        }
                      >
                        {t('mail.inbound.release')}
                      </Button>
                    ) : (
                      m.ticketId && (
                        <Anchor component={Link} to={`/agent/tickets/${m.ticketId}`} size="sm">
                          {t('mail.inbound.openTicket')}
                        </Anchor>
                      )
                    )}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
          {list.hasNextPage && (
            <Button variant="subtle" w="fit-content" loading={list.isFetchingNextPage} onClick={() => void list.fetchNextPage()}>
              {t('mail.more')}
            </Button>
          )}
        </>
      )}
    </Stack>
  );
}

const STATUSES = ['pending', 'sent', 'failed', 'suppressed'] as const;
const statusColor: Record<string, string> = { pending: 'gray', sent: 'green', failed: 'red', suppressed: 'gray' };

function OutgoingPanel() {
  const { t, i18n } = useTranslation();
  const [status, setStatus] = useState<string | null>(null);
  const list = useOutgoing(status);
  const retry = useRetry();
  const rows = list.data?.pages.flatMap((p) => p.items) ?? [];
  const state = listStateOf(list, rows.length, status != null);

  return (
    <Stack gap="md">
      <Group justify="space-between" align="flex-end">
        <Text size="sm" c="dimmed" maw={640}>
          {t('mail.outgoing.intro')}
        </Text>
        <Select
          label={t('mail.outgoing.filter')}
          w={220}
          clearable
          placeholder={t('mail.outgoing.all')}
          data={STATUSES.map((s) => ({ value: s, label: t(`mail.outgoing.statuses.${s}`) }))}
          value={status}
          onChange={setStatus}
        />
      </Group>
      <ErrorLine error={retry.error} />
      {state ? (
        <ListState
          state={state}
          title={state === 'empty' ? t('mail.outgoing.empty') : undefined}
          error={list.error}
          onRetry={() => void list.refetch()}
          onResetFilters={() => setStatus(null)}
        />
      ) : (
        <>
          <Table verticalSpacing="xs">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('mail.outgoing.created')}</Table.Th>
                <Table.Th>{t('mail.outgoing.to')}</Table.Th>
                <Table.Th>{t('mail.outgoing.subject')}</Table.Th>
                <Table.Th>{t('mail.outgoing.status')}</Table.Th>
                <Table.Th>{t('mail.outgoing.attempts')}</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((m) => (
                <Table.Tr key={m.id}>
                  <Table.Td>
                    <Text size="sm" style={{ whiteSpace: 'nowrap' }}>
                      {dateTime(m.createdAt, i18n.language)}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm">{m.recipient}</Text>
                  </Table.Td>
                  <Table.Td maw={360}>
                    <Text size="sm" lineClamp={2}>
                      {m.subject}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Tooltip label={m.lastError} disabled={!m.lastError} multiline w={360}>
                      <Badge variant="light" color={statusColor[m.status]} size="sm" tt="none">
                        {t(`mail.outgoing.statuses.${m.status}`)}
                      </Badge>
                    </Tooltip>
                    {m.status === 'pending' && m.nextAttemptAt && m.attempts > 0 && (
                      <Text size="xs" c="dimmed">
                        {dateTime(m.nextAttemptAt, i18n.language)}
                      </Text>
                    )}
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm">{m.attempts}</Text>
                  </Table.Td>
                  <Table.Td ta="right">
                    {m.status === 'failed' && (
                      <Button
                        size="compact-sm"
                        variant="light"
                        loading={retry.isPending && retry.variables === m.id}
                        onClick={() => retry.mutate(m.id)}
                      >
                        {t('mail.outgoing.retry')}
                      </Button>
                    )}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
          {list.hasNextPage && (
            <Button variant="subtle" w="fit-content" loading={list.isFetchingNextPage} onClick={() => void list.fetchNextPage()}>
              {t('mail.more')}
            </Button>
          )}
        </>
      )}
    </Stack>
  );
}
