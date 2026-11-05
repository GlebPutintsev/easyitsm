import {
  ActionIcon,
  Badge,
  Button,
  Card,
  Checkbox,
  Group,
  Modal,
  NumberInput,
  SegmentedControl,
  Stack,
  TagsInput,
  Text,
  TextInput,
  Textarea,
  Title,
} from '@mantine/core';
import { IconArrowDown, IconArrowUp, IconPlus, IconTrash } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import { ErrorLine } from '@/features/portal/TicketPage';
import { type Flow, type FlowInput, type StepDef, useFlows, useSaveFlow } from '../api';
import { UUID_RE } from '../users';
import { ConditionEditor } from './ConditionEditor';

/** Шаблоны маршрутов согласования (REQ-03): шаги последовательно, внутри шага все, любой или кворум. */
export function FlowsPanel() {
  const { t } = useTranslation();
  const flows = useFlows();
  const [editing, setEditing] = useState<Flow | 'new' | null>(null);
  const list = flows.data ?? [];
  const state = listStateOf(flows, list.length);
  return (
    <Stack gap="sm">
      <Group justify="space-between">
        <Text size="sm" c="dimmed" maw={640}>
          {t('admin.flows.hint')}
        </Text>
        <Button leftSection={<IconPlus size={16} />} onClick={() => setEditing('new')}>
          {t('admin.flows.new')}
        </Button>
      </Group>
      {state ? (
        <ListState state={state} title={state === 'empty' ? t('admin.flows.empty') : undefined} error={flows.error} onRetry={() => void flows.refetch()} />
      ) : (
        list.map((f) => (
          <Card key={f.id} withBorder padding="sm" style={{ cursor: 'pointer' }} onClick={() => setEditing(f)}>
            <Group justify="space-between">
              <Stack gap={2}>
                <Text fw={600}>{f.name}</Text>
                <Text size="sm" c="dimmed">
                  {f.steps.map((s) => s.name).join(' → ') || t('admin.flows.noSteps')}
                </Text>
              </Stack>
              {!f.active && <Badge color="gray">{t('admin.catalog.inactive')}</Badge>}
            </Group>
          </Card>
        ))
      )}
      {editing && <FlowDialog flow={editing === 'new' ? undefined : editing} onClose={() => setEditing(null)} />}
    </Stack>
  );
}

const EMPTY_STEP: StepDef = { name: '', mode: 'all', approvers: [], allowDelegation: false };

function FlowDialog({ flow, onClose }: { flow?: Flow; onClose: () => void }) {
  const { t } = useTranslation();
  const save = useSaveFlow();
  const [input, setInput] = useState<FlowInput>(
    flow ? { name: flow.name, description: flow.description, steps: flow.steps, active: flow.active } : { name: '', description: '', steps: [{ ...EMPTY_STEP }], active: true },
  );
  const setStep = (i: number, patch: Partial<StepDef>) =>
    setInput((x) => ({ ...x, steps: x.steps.map((s, j) => (j === i ? { ...s, ...patch } : s)) }));
  const move = (i: number, d: -1 | 1) =>
    setInput((x) => {
      const steps = [...x.steps];
      const j = i + d;
      if (j < 0 || j >= steps.length) return x;
      const [moved] = steps.splice(i, 1);
      if (moved) steps.splice(j, 0, moved);
      return { ...x, steps };
    });
  const valid =
    input.name.trim() &&
    input.steps.every(
      (s) => s.name.trim() && s.approvers.length > 0 && s.approvers.every((a) => UUID_RE.test(a)) && (s.mode !== 'quorum' || (s.quorum && s.quorum <= s.approvers.length)),
    );

  return (
    <Modal opened onClose={onClose} size="xl" title={flow ? flow.name : t('admin.flows.new')}>
      <Stack gap="sm">
        <TextInput label={t('admin.catalog.name')} required value={input.name} onChange={(e) => setInput({ ...input, name: e.currentTarget.value })} />
        <Textarea label={t('admin.catalog.description')} autosize value={input.description} onChange={(e) => setInput({ ...input, description: e.currentTarget.value })} />
        <Checkbox label={t('admin.catalog.active')} checked={input.active} onChange={(e) => setInput({ ...input, active: e.currentTarget.checked })} />
        <Title order={5}>{t('admin.flows.steps')}</Title>
        {input.steps.map((s, i) => (
          <Card key={i} withBorder padding="sm">
            <Stack gap="xs">
              <Group justify="space-between">
                <Text fw={600} size="sm">
                  {t('admin.flows.step', { n: i + 1 })}
                </Text>
                <Group gap={0}>
                  <ActionIcon variant="subtle" aria-label={t('admin.catalog.up')} disabled={i === 0} onClick={() => move(i, -1)}>
                    <IconArrowUp size={16} />
                  </ActionIcon>
                  <ActionIcon variant="subtle" aria-label={t('admin.catalog.down')} disabled={i === input.steps.length - 1} onClick={() => move(i, 1)}>
                    <IconArrowDown size={16} />
                  </ActionIcon>
                  <ActionIcon variant="subtle" color="red" aria-label={t('admin.catalog.remove')} onClick={() => setInput({ ...input, steps: input.steps.filter((_, j) => j !== i) })}>
                    <IconTrash size={16} />
                  </ActionIcon>
                </Group>
              </Group>
              <TextInput label={t('admin.flows.stepName')} required value={s.name} onChange={(e) => setStep(i, { name: e.currentTarget.value })} />
              <Group align="flex-end">
                <SegmentedControl
                  value={s.mode}
                  onChange={(v) => setStep(i, { mode: v as StepDef['mode'], quorum: v === 'quorum' ? s.quorum ?? 1 : null })}
                  data={(['all', 'any', 'quorum'] as const).map((m) => ({ value: m, label: t(`admin.flows.modes.${m}`) }))}
                />
                {s.mode === 'quorum' && (
                  <NumberInput label={t('admin.flows.quorum')} w={120} min={1} max={s.approvers.length || 1} value={s.quorum ?? 1} onChange={(v) => setStep(i, { quorum: Number(v) || 1 })} />
                )}
              </Group>
              <TagsInput
                label={t('admin.flows.approvers')}
                description={t('admin.flows.approversHint')}
                value={s.approvers}
                onChange={(v) => setStep(i, { approvers: v.map((x) => x.trim()) })}
                error={s.approvers.some((a) => !UUID_RE.test(a)) ? t('admin.flows.approverInvalid') : undefined}
                splitChars={[',', ' ', ';']}
              />
              <Group grow>
                <NumberInput
                  label={t('admin.flows.dueHours')}
                  min={1}
                  value={s.dueMinutes ? s.dueMinutes / 60 : ''}
                  onChange={(v) => setStep(i, { dueMinutes: v === '' ? null : Math.round(Number(v) * 60) })}
                />
                <Checkbox mt="lg" label={t('admin.flows.allowDelegation')} checked={!!s.allowDelegation} onChange={(e) => setStep(i, { allowDelegation: e.currentTarget.checked })} />
              </Group>
              <ConditionEditor label={t('admin.flows.condition')} fields={[]} value={s.condition} onChange={(c) => setStep(i, { condition: c })} />
            </Stack>
          </Card>
        ))}
        <Group>
          <Button variant="subtle" leftSection={<IconPlus size={14} />} onClick={() => setInput({ ...input, steps: [...input.steps, { ...EMPTY_STEP }] })} disabled={input.steps.length >= 10}>
            {t('admin.flows.addStep')}
          </Button>
        </Group>
        <ErrorLine error={save.error} />
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            {t('actions.cancel')}
          </Button>
          <Button loading={save.isPending} disabled={!valid} onClick={() => save.mutate({ id: flow?.id, version: flow?.version, input }, { onSuccess: onClose })}>
            {t('admin.catalog.save')}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
