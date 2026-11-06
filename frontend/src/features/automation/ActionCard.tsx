import { ActionIcon, Anchor, Checkbox, Group, SegmentedControl, Select, Stack, Text, TextInput, Textarea } from '@mantine/core';
import { IconArrowDown, IconArrowUp, IconTrash } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { PersonPicker } from '@/shared/ui/PersonPicker';
import { type Action, type ActionType, type NotifyTarget, useGroups, useStatuses, useWebhooks } from './api';
import css from './automation.module.css';

export const ACTION_TYPES: ActionType[] = [
  'assign',
  'set_priority',
  'set_status',
  'add_comment',
  'add_watcher',
  'escalate',
  'notify',
  'webhook',
];

export const NOTIFY_TARGETS: NotifyTarget[] = ['assignee', 'group', 'requester', 'watchers', 'user'];

/** Действие с параметрами по умолчанию для выбранного типа. */
export function newAction(type: ActionType): Action {
  switch (type) {
    case 'set_priority':
      return { type, priority: 'P1' };
    case 'add_comment':
      return { type, body: '', visibility: 'internal' };
    case 'escalate':
      return { type, kind: 'hierarchical', reason: '' };
    case 'notify':
      return { type, target: 'assignee', title: '', body: '', email: false };
    default:
      return { type };
  }
}

interface Props {
  action: Action;
  index: number;
  count: number;
  onChange: (a: Action) => void;
  onMove: (delta: -1 | 1) => void;
  onRemove: () => void;
}

/** Карточка действия: тип и его параметры. Порядок важен: действия выполняются сверху вниз в одной транзакции. */
export function ActionCard({ action, index, count, onChange, onMove, onRemove }: Props) {
  const { t } = useTranslation();
  const groups = useGroups();
  const statuses = useStatuses();
  const set = (patch: Partial<Action>) => onChange({ ...action, ...patch });
  const groupData = (groups.data ?? []).map((g) => ({ value: g.id, label: g.name }));
  if (action.groupId && !groupData.some((g) => g.value === action.groupId)) {
    groupData.push({ value: action.groupId, label: action.groupId });
  }

  return (
    <Stack gap="xs" className={css.actionCard} data-testid={`action-${index}`}>
      <Group justify="space-between" wrap="nowrap">
        <Group gap="xs" wrap="nowrap">
          <Text size="sm" fw={700} c="dimmed" w={20}>
            {index + 1}.
          </Text>
          <Select
            aria-label={t('automation.columns.actions')}
            size="sm"
            w={260}
            data={ACTION_TYPES.map((a) => ({ value: a, label: t(`automation.actionTypes.${a}`) }))}
            value={action.type}
            onChange={(v) => v && onChange(newAction(v as ActionType))}
            allowDeselect={false}
          />
        </Group>
        <Group gap={4} wrap="nowrap">
          <ActionIcon variant="subtle" color="gray" disabled={index === 0} aria-label={t('automation.editor.moveUp')} onClick={() => onMove(-1)}>
            <IconArrowUp size={16} />
          </ActionIcon>
          <ActionIcon variant="subtle" color="gray" disabled={index === count - 1} aria-label={t('automation.editor.moveDown')} onClick={() => onMove(1)}>
            <IconArrowDown size={16} />
          </ActionIcon>
          <ActionIcon variant="subtle" color="gray" aria-label={t('automation.editor.remove')} onClick={onRemove}>
            <IconTrash size={16} />
          </ActionIcon>
        </Group>
      </Group>

      {action.type === 'assign' && (
        <Group grow align="flex-start">
          <Select label={t('automation.editor.group')} searchable clearable data={groupData} value={action.groupId ?? null} onChange={(v) => set({ groupId: v })} />
          <PersonPicker
            label={t('automation.editor.assignee')}
            description={t('automation.editor.assigneeHint')}
            value={action.assigneeId ?? null}
            onChange={(v) => set({ assigneeId: v })}
          />
        </Group>
      )}

      {action.type === 'set_priority' && (
        <SegmentedControl
          w="fit-content"
          data={['P1', 'P2', 'P3', 'P4']}
          value={action.priority ?? 'P1'}
          onChange={(v) => set({ priority: v })}
          aria-label={t('automation.fields.priority')}
        />
      )}

      {action.type === 'set_status' && (
        <Stack gap="xs">
          <Group grow align="flex-start">
            <Select
              label={t('automation.editor.status')}
              searchable
              data={(statuses.data ?? []).map((s) => ({ value: s.code, label: `${s.name} · ${s.code}` }))}
              value={action.status ?? null}
              onChange={(v) => set({ status: v })}
            />
            <TextInput label={t('automation.editor.reasonCode')} value={action.reasonCode ?? ''} onChange={(e) => set({ reasonCode: e.currentTarget.value || null })} />
            <TextInput label={t('automation.editor.resolutionCode')} value={action.resolutionCode ?? ''} onChange={(e) => set({ resolutionCode: e.currentTarget.value || null })} />
          </Group>
          <Textarea label={t('automation.editor.comment')} autosize minRows={2} value={action.body ?? ''} onChange={(e) => set({ body: e.currentTarget.value || null })} />
        </Stack>
      )}

      {action.type === 'add_comment' && (
        <Stack gap="xs">
          <SegmentedControl
            w="fit-content"
            aria-label={t('automation.editor.visibility')}
            data={[
              { value: 'internal', label: t('automation.values.visibility.internal') },
              { value: 'public', label: t('automation.values.visibility.public') },
            ]}
            value={action.visibility ?? 'internal'}
            onChange={(v) => set({ visibility: v as 'internal' | 'public' })}
          />
          <Textarea
            label={t('automation.editor.body')}
            description={t('automation.editor.bodyHint')}
            autosize
            minRows={3}
            maxLength={10000}
            value={action.body ?? ''}
            onChange={(e) => set({ body: e.currentTarget.value })}
            styles={action.visibility === 'public' ? undefined : { input: { background: 'var(--ei-note)', borderColor: 'var(--ei-note-border)' } }}
          />
        </Stack>
      )}

      {action.type === 'add_watcher' && (
        <PersonPicker label={t('automation.editor.user')} value={action.userId ?? null} onChange={(v) => set({ userId: v })} />
      )}

      {action.type === 'escalate' && (
        <Stack gap="xs">
          <SegmentedControl
            w="fit-content"
            aria-label={t('automation.editor.kind')}
            data={[
              { value: 'hierarchical', label: t('automation.values.escalationKind.hierarchical') },
              { value: 'functional', label: t('automation.values.escalationKind.functional') },
            ]}
            value={action.kind ?? 'hierarchical'}
            onChange={(v) => set({ kind: v as 'functional' | 'hierarchical', groupId: v === 'functional' ? action.groupId : null })}
          />
          <Group grow align="flex-start">
            {action.kind === 'functional' && (
              <Select label={t('automation.editor.group')} searchable data={groupData} value={action.groupId ?? null} onChange={(v) => set({ groupId: v })} />
            )}
            <TextInput label={t('automation.editor.reason')} description={t('automation.editor.bodyHint')} value={action.reason ?? ''} onChange={(e) => set({ reason: e.currentTarget.value })} />
          </Group>
        </Stack>
      )}

      {action.type === 'notify' && <NotifyFields action={action} groupData={groupData} set={set} />}

      {action.type === 'webhook' && <WebhookField value={action.webhookId ?? null} onChange={(v) => set({ webhookId: v })} />}
    </Stack>
  );
}

function NotifyFields({
  action,
  groupData,
  set,
}: {
  action: Action;
  groupData: { value: string; label: string }[];
  set: (patch: Partial<Action>) => void;
}) {
  const { t } = useTranslation();
  return (
    <Stack gap="xs">
      <Group grow align="flex-start">
        <Select
          label={t('automation.editor.target')}
          data={NOTIFY_TARGETS.map((v) => ({ value: v, label: t(`automation.values.target.${v}`) }))}
          value={action.target ?? 'assignee'}
          onChange={(v) => set({ target: (v as NotifyTarget) ?? 'assignee', userId: null, groupId: null })}
          allowDeselect={false}
        />
        {action.target === 'user' && (
          <PersonPicker label={t('automation.editor.user')} value={action.userId ?? null} onChange={(v) => set({ userId: v })} />
        )}
        {action.target === 'group' && (
          <Select
            label={t('automation.editor.group')}
            description={t('automation.editor.groupOfTicket')}
            searchable
            clearable
            data={groupData}
            value={action.groupId ?? null}
            onChange={(v) => set({ groupId: v })}
          />
        )}
      </Group>
      <TextInput
        label={t('automation.editor.notifyTitle')}
        description={t('automation.editor.notifyTitleHint')}
        maxLength={200}
        value={action.title ?? ''}
        onChange={(e) => set({ title: e.currentTarget.value || null })}
      />
      <Textarea
        label={t('automation.editor.body')}
        description={t('automation.editor.bodyHint')}
        autosize
        minRows={2}
        maxLength={10000}
        value={action.body ?? ''}
        onChange={(e) => set({ body: e.currentTarget.value })}
      />
      <Checkbox label={t('automation.editor.email')} checked={!!action.email} onChange={(e) => set({ email: e.currentTarget.checked })} />
      <Text size="xs" c="dimmed">
        {t('automation.editor.notifyNote')}
      </Text>
    </Stack>
  );
}

function WebhookField({ value, onChange }: { value: string | null; onChange: (v: string | null) => void }) {
  const { t } = useTranslation();
  const hooks = useWebhooks();
  const data = (hooks.data ?? []).map((h) => ({
    value: h.id,
    label: h.enabled ? h.name : `${h.name} (${t('automation.editor.webhookOff')})`,
    disabled: !h.enabled,
  }));
  if (value && !data.some((d) => d.value === value)) {
    data.push({ value, label: t('automation.editor.webhookMissing'), disabled: true });
  }
  return (
    <Stack gap={4}>
      <Select
        label={t('automation.editor.webhook')}
        placeholder={hooks.data?.length === 0 ? t('automation.editor.webhookNone') : undefined}
        searchable
        data={data}
        value={value}
        onChange={onChange}
      />
      <Text size="xs" c="dimmed">
        {t('automation.editor.webhookNote')}{' '}
        <Anchor component={Link} to="/admin/integrations?tab=webhooks" size="xs">
          {t('automation.editor.webhookManage')}
        </Anchor>
      </Text>
    </Stack>
  );
}
