import { Alert, Anchor, Badge, Button, Code, CopyButton, Group, Modal, Stack, Switch, Table, TagsInput, Text, TextInput } from '@mantine/core';
import { IconPlus } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { hasRole, useMe } from '@/features/auth/api';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import {
  type Webhook,
  type WebhookTestResult,
  type WebhookWithSecret,
  useCreateWebhook,
  useDeleteWebhook,
  usePolicy,
  useRotateWebhookSecret,
  useSavePolicy,
  useTestWebhook,
  useUpdateWebhook,
  useWebhooks,
} from './api';
import { SIGNATURE_EXAMPLE } from './snippets';

/** Исходящие вебхуки: разрешённые адреса, получатели, тест и ключ подписи. События шлют правила автоматизации. */
export function WebhooksPanel() {
  const { t, i18n } = useTranslation();
  const me = useMe();
  const canEdit = hasRole(me.data, 'ADM');
  const hooks = useWebhooks();
  const list = hooks.data ?? [];
  const state = listStateOf(hooks, list.length);
  const update = useUpdateWebhook();
  const [editing, setEditing] = useState<Webhook | 'new' | null>(null);
  const [issued, setIssued] = useState<WebhookWithSecret | null>(null);

  return (
    <Stack gap="md">
      <Text size="sm" c="dimmed" maw={820}>
        {t('integrations.webhooks.intro')}
      </Text>
      <PolicyEditor canEdit={canEdit} />
      <Group justify="space-between">
        <Text fw={600}>{t('integrations.webhooks.receivers')}</Text>
        {canEdit && (
          <Button leftSection={<IconPlus size={16} />} onClick={() => setEditing('new')}>
            {t('integrations.webhooks.add')}
          </Button>
        )}
      </Group>
      <ErrorLine error={update.error} />
      {state ? (
        <ListState
          state={state}
          title={state === 'empty' ? t('integrations.webhooks.empty') : undefined}
          error={hooks.error}
          onRetry={() => void hooks.refetch()}
        />
      ) : (
        <Table.ScrollContainer minWidth={680}>
          <Table highlightOnHover verticalSpacing="sm" data-testid="webhooks-table">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('integrations.webhooks.columns.name')}</Table.Th>
                <Table.Th>{t('integrations.webhooks.columns.url')}</Table.Th>
                <Table.Th>{t('integrations.webhooks.columns.lastUsed')}</Table.Th>
                <Table.Th w={110}>{t('integrations.monitoring.columns.enabled')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {list.map((h) => (
                <Table.Tr key={h.id} style={{ cursor: 'pointer' }} onClick={() => setEditing(h)}>
                  <Table.Td>
                    <Anchor component="button" type="button" onClick={() => setEditing(h)}>
                      {h.name}
                    </Anchor>
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm" ff="monospace" lineClamp={1} style={{ overflowWrap: 'anywhere' }}>
                      {h.url}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm" c="dimmed">
                      {h.lastUsedAt
                        ? new Date(h.lastUsedAt).toLocaleString(i18n.language, { dateStyle: 'short', timeStyle: 'short' })
                        : t('integrations.monitoring.never')}
                    </Text>
                  </Table.Td>
                  <Table.Td onClick={(e) => e.stopPropagation()}>
                    <Switch
                      aria-label={t('integrations.monitoring.enabled')}
                      checked={h.enabled}
                      disabled={!canEdit || update.isPending}
                      onChange={(e) => update.mutate({ id: h.id, version: h.version, input: { enabled: e.currentTarget.checked } })}
                    />
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
      {editing && (
        <WebhookModal
          current={editing === 'new' ? null : editing}
          canEdit={canEdit}
          onClose={() => setEditing(null)}
          onSecret={(w) => {
            setEditing(null);
            setIssued(w);
          }}
        />
      )}
      {issued && <SecretModal issued={issued} onClose={() => setIssued(null)} />}
    </Stack>
  );
}

/** Список разрешённых адресов: пока он пуст, вебхуки никуда не отправляются. */
function PolicyEditor({ canEdit }: { canEdit: boolean }) {
  const { t } = useTranslation();
  const policy = usePolicy();
  const save = useSavePolicy();
  // Черновик списка: null — показывается сохранённый.
  const [draft, setDraft] = useState<string[] | null>(null);
  const hosts = draft ?? policy.data?.allowedHosts ?? [];
  const dirty = !!policy.data && draft !== null && draft.join('\n') !== policy.data.allowedHosts.join('\n');
  return (
    <Stack gap="xs" maw={820}>
      <TagsInput
        label={t('integrations.webhooks.allowed')}
        description={t('integrations.webhooks.allowedHint')}
        placeholder={canEdit ? 'hooks.example.ru, *.example.ru, 10.20.0.0/16' : undefined}
        value={hosts}
        onChange={setDraft}
        disabled={!canEdit || !policy.data}
        splitChars={[',', ' ']}
        clearable
      />
      {policy.data && policy.data.allowedHosts.length === 0 && !dirty && (
        <Alert color="yellow" variant="light">
          {t('integrations.webhooks.allowedEmpty')}
        </Alert>
      )}
      <ErrorLine error={save.error} />
      {canEdit && dirty && (
        <Group gap="xs">
          <Button
            size="xs"
            loading={save.isPending}
            onClick={() => policy.data && save.mutate({ version: policy.data.version, allowedHosts: hosts }, { onSuccess: () => setDraft(null) })}
          >
            {t('integrations.webhooks.saveAllowed')}
          </Button>
          <Button size="xs" variant="subtle" onClick={() => setDraft(null)}>
            {t('integrations.webhooks.cancel')}
          </Button>
        </Group>
      )}
    </Stack>
  );
}

function WebhookModal({
  current,
  canEdit,
  onClose,
  onSecret,
}: {
  current: Webhook | null;
  canEdit: boolean;
  onClose: () => void;
  onSecret: (w: WebhookWithSecret) => void;
}) {
  const { t } = useTranslation();
  const [name, setName] = useState(current?.name ?? '');
  const [url, setUrl] = useState(current?.url ?? 'https://');
  const create = useCreateWebhook();
  const update = useUpdateWebhook();
  const rotate = useRotateWebhookSecret();
  const remove = useDeleteWebhook();
  const test = useTestWebhook();
  const [result, setResult] = useState<WebhookTestResult | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const pending = create.isPending || update.isPending || rotate.isPending || remove.isPending;

  const submit = () => {
    if (current) update.mutate({ id: current.id, version: current.version, input: { name, url } }, { onSuccess: onClose });
    else create.mutate({ name, url }, { onSuccess: onSecret });
  };

  return (
    <Modal opened onClose={onClose} size="lg" title={current ? current.name : t('integrations.webhooks.newTitle')}>
      <Stack gap="sm">
        <TextInput label={t('integrations.webhooks.columns.name')} required maxLength={200} value={name} disabled={!canEdit} onChange={(e) => setName(e.currentTarget.value)} />
        <TextInput
          label={t('integrations.webhooks.columns.url')}
          description={t('integrations.webhooks.urlHint')}
          required
          maxLength={2000}
          value={url}
          disabled={!canEdit}
          onChange={(e) => setUrl(e.currentTarget.value)}
        />
        {current && canEdit && (
          <Group gap="xs">
            <Button variant="default" loading={test.isPending} onClick={() => test.mutate(current.id, { onSuccess: setResult })}>
              {t('integrations.webhooks.test')}
            </Button>
            {result && (
              <Badge color={result.ok ? 'green' : 'red'} variant="light" data-testid="webhook-test-result">
                {result.ok
                  ? t('integrations.webhooks.testOk', { status: result.status })
                  : (result.error ?? t('integrations.webhooks.testFail'))}
              </Badge>
            )}
          </Group>
        )}
        <ErrorLine error={create.error ?? update.error ?? rotate.error ?? remove.error ?? test.error} />
        <Group justify="space-between">
          <Group gap="xs">
            {current && canEdit && (
              <>
                <Button variant="default" loading={rotate.isPending} disabled={pending} onClick={() => rotate.mutate(current.id, { onSuccess: onSecret })}>
                  {t('integrations.webhooks.rotate')}
                </Button>
                {confirmDelete ? (
                  <Button color="red" loading={remove.isPending} disabled={pending} onClick={() => remove.mutate(current.id, { onSuccess: onClose })}>
                    {t('integrations.webhooks.confirmDelete')}
                  </Button>
                ) : (
                  <Button variant="subtle" color="red" disabled={pending} onClick={() => setConfirmDelete(true)}>
                    {t('integrations.webhooks.delete')}
                  </Button>
                )}
              </>
            )}
          </Group>
          <Group gap="xs">
            <Button variant="default" onClick={onClose}>
              {t('integrations.webhooks.cancel')}
            </Button>
            {canEdit && (
              <Button loading={create.isPending || update.isPending} disabled={pending || !name.trim() || !url.trim()} onClick={submit}>
                {current ? t('integrations.webhooks.save') : t('integrations.webhooks.create')}
              </Button>
            )}
          </Group>
        </Group>
      </Stack>
    </Modal>
  );
}

/** Ключ подписи виден только сейчас: потом его можно только перевыпустить. */
function SecretModal({ issued, onClose }: { issued: WebhookWithSecret; onClose: () => void }) {
  const { t } = useTranslation();
  return (
    <Modal opened onClose={onClose} size="xl" title={t('integrations.webhooks.secretTitle', { name: issued.webhook.name })}>
      <Stack gap="sm">
        <Alert color="yellow" variant="light">
          {t('integrations.webhooks.secretOnce')}
        </Alert>
        <Group gap="xs" wrap="nowrap">
          <Code style={{ overflowWrap: 'anywhere', flex: 1 }} data-testid="issued-secret">
            {issued.secret}
          </Code>
          <CopyButton value={issued.secret}>
            {({ copied, copy }) => (
              <Button size="xs" onClick={copy}>
                {copied ? t('integrations.copied') : t('integrations.copy')}
              </Button>
            )}
          </CopyButton>
        </Group>
        <Text size="sm">{t('integrations.webhooks.verify')}</Text>
        <Code block>{SIGNATURE_EXAMPLE}</Code>
        <Group justify="flex-end">
          <Button onClick={onClose}>{t('integrations.token.done')}</Button>
        </Group>
      </Stack>
    </Modal>
  );
}
