import { Anchor, Button, Group, Modal, NumberInput, Select, Stack, Switch, Text, TextInput, Textarea, Title } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconArrowLeft, IconPlus } from '@tabler/icons-react';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams } from 'react-router';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { ListState } from '@/shared/ui/ListState';
import { type Action, type Condition, type Rule, type RuleInput, useDeleteRule, useRule, useSaveRule } from './api';
import { ActionCard, newAction } from './ActionCard';
import { ConditionRow } from './ConditionRow';
import { TestPanel } from './TestPanel';
import { type Trigger, TRIGGERS, conditionComplete, fieldsFor } from './model';
import css from './automation.module.css';

const EMPTY: Required<RuleInput> = {
  name: '',
  description: '',
  trigger: 'ticket_created',
  conditions: { match: 'all', items: [] },
  actions: [newAction('set_priority')],
  enabled: false,
  position: 0,
};

function draftOf(r: Rule): Required<RuleInput> {
  return {
    name: r.name,
    description: r.description,
    trigger: r.trigger,
    conditions: { match: r.conditions?.match ?? 'all', items: r.conditions?.items ?? [] },
    actions: r.actions,
    enabled: r.enabled,
    position: r.position,
  };
}

/** Конструктор правила «Когда — Если — Тогда» (AUT-01) с проверкой на заявке. */
export function RuleEditorPage() {
  const { id } = useParams();
  const isNew = !id || id === 'new';
  const rule = useRule(isNew ? undefined : id);
  const { t } = useTranslation();

  if (!isNew && rule.isPending) return <ListState state="loading" />;
  if (!isNew && rule.error) return <ListState state="error" error={rule.error} onRetry={() => void rule.refetch()} />;
  // После сохранения версия меняется, и редактор начинает с сохранённого правила.
  return <Editor key={rule.data ? `${rule.data.id}:${rule.data.version}` : 'new'} rule={isNew ? undefined : rule.data} title={isNew ? t('automation.editor.newTitle') : (rule.data?.name ?? '')} />;
}

function Editor({ rule, title }: { rule?: Rule; title: string }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const save = useSaveRule();
  const remove = useDeleteRule();
  const initial = useMemo(() => (rule ? draftOf(rule) : EMPTY), [rule]);
  const [draft, setDraft] = useState<Required<RuleInput>>(initial);
  const [confirmDelete, setConfirmDelete] = useState(false);

  const set = <K extends keyof RuleInput>(k: K, v: Required<RuleInput>[K]) => setDraft((d) => ({ ...d, [k]: v }));
  const items = draft.conditions.items;
  const setItems = (next: Condition[]) => set('conditions', { ...draft.conditions, items: next });
  const setActions = (next: Action[]) => set('actions', next);
  const dirty = JSON.stringify(draft) !== JSON.stringify(initial);
  const complete = draft.name.trim() !== '' && draft.actions.length > 0 && items.every(conditionComplete);

  const changeTrigger = (trigger: Trigger) => {
    // Поля события другого триггера в условиях недоступны: такие условия убираются.
    const allowed = new Set(fieldsFor(trigger).map((f) => f.key));
    setDraft((d) => ({ ...d, trigger, conditions: { ...d.conditions, items: d.conditions.items.filter((c) => allowed.has(c.field)) } }));
  };

  const submit = () =>
    save.mutate(
      { id: rule?.id, version: rule?.version, input: draft },
      {
        onSuccess: (saved) => {
          notifications.show({ color: 'teal', message: t('automation.editor.saved') });
          if (!rule) navigate(`/admin/automation/rules/${saved.id}`, { replace: true });
        },
      },
    );

  return (
    <Stack gap="md" maw={1000}>
      <Anchor component={Link} to="/admin/automation" size="sm">
        <Group gap={4}>
          <IconArrowLeft size={14} />
          {t('automation.editor.back')}
        </Group>
      </Anchor>
      <Group justify="space-between" align="flex-end">
        <Title order={2}>{title}</Title>
        {rule && (
          <Anchor component={Link} to={`/admin/automation?tab=runs&rule=${rule.id}`} size="sm">
            {t('automation.tabs.runs')}
          </Anchor>
        )}
      </Group>

      <Group grow align="flex-start">
        <TextInput label={t('automation.editor.name')} required maxLength={200} value={draft.name} onChange={(e) => set('name', e.currentTarget.value)} data-autofocus />
        <NumberInput
          label={t('automation.editor.position')}
          description={t('automation.editor.positionHint')}
          maw={220}
          value={draft.position}
          onChange={(v) => set('position', Number(v) || 0)}
        />
      </Group>
      <Textarea label={t('automation.editor.description')} autosize minRows={1} maxLength={2000} value={draft.description} onChange={(e) => set('description', e.currentTarget.value)} />
      <Switch
        label={t('automation.editor.enabled')}
        description={t('automation.editor.enabledHint')}
        checked={draft.enabled}
        onChange={(e) => set('enabled', e.currentTarget.checked)}
      />

      <div>
        <section className={css.step} aria-label={t('automation.editor.when')}>
          <span className={css.stepLabel}>{t('automation.editor.when')}</span>
          <Select
            label={t('automation.editor.trigger')}
            maw={360}
            data={TRIGGERS.map((tr) => ({ value: tr, label: t(`automation.triggers.${tr}`) }))}
            value={draft.trigger}
            onChange={(v) => v && changeTrigger(v as Trigger)}
            allowDeselect={false}
          />
        </section>
        <div className={css.connector} />
        <section className={css.step} aria-label={t('automation.editor.if')}>
          <span className={css.stepLabel}>{t('automation.editor.if')}</span>
          <Stack gap="sm">
            {items.length > 1 && (
              <Select
                aria-label={t('automation.editor.if')}
                maw={320}
                data={[
                  { value: 'all', label: t('automation.editor.matchAll') },
                  { value: 'any', label: t('automation.editor.matchAny') },
                ]}
                value={draft.conditions.match}
                onChange={(v) => set('conditions', { ...draft.conditions, match: (v as 'all' | 'any') ?? 'all' })}
                allowDeselect={false}
              />
            )}
            {items.length === 0 && (
              <Text size="sm" c="dimmed">
                {t('automation.editor.noConditions')}
              </Text>
            )}
            {items.map((c, i) => (
              <ConditionRow
                key={i}
                index={i}
                trigger={draft.trigger}
                condition={c}
                onChange={(next) => setItems(items.map((x, j) => (j === i ? next : x)))}
                onRemove={() => setItems(items.filter((_, j) => j !== i))}
              />
            ))}
            <Button
              variant="subtle"
              w="fit-content"
              leftSection={<IconPlus size={16} />}
              disabled={items.length >= 20}
              onClick={() => setItems([...items, { field: 'priority', op: 'in', value: [] }])}
            >
              {t('automation.editor.addCondition')}
            </Button>
          </Stack>
        </section>
        <div className={css.connector} />
        <section className={css.step} aria-label={t('automation.editor.then')}>
          <span className={css.stepLabel}>{t('automation.editor.then')}</span>
          <Stack gap="sm">
            {draft.actions.map((a, i) => (
              <ActionCard
                key={i}
                index={i}
                count={draft.actions.length}
                action={a}
                onChange={(next) => setActions(draft.actions.map((x, j) => (j === i ? next : x)))}
                onMove={(delta) => {
                  const next = [...draft.actions];
                  const [moved] = next.splice(i, 1);
                  next.splice(i + delta, 0, moved!);
                  setActions(next);
                }}
                onRemove={() => setActions(draft.actions.filter((_, j) => j !== i))}
              />
            ))}
            <Button
              variant="subtle"
              w="fit-content"
              leftSection={<IconPlus size={16} />}
              disabled={draft.actions.length >= 10}
              onClick={() => setActions([...draft.actions, newAction('add_comment')])}
            >
              {t('automation.editor.addAction')}
            </Button>
            <Text size="xs" c="dimmed">
              {t('automation.editor.loopNote')}
            </Text>
          </Stack>
        </section>
      </div>

      <TestPanel rule={draft} disabled={!complete} />

      <ErrorLine error={save.error ?? remove.error} />
      <Group justify="space-between">
        <Group gap="xs">
          <Button onClick={submit} loading={save.isPending} disabled={!complete || (!dirty && !!rule)}>
            {t('automation.editor.save')}
          </Button>
          <Button variant="default" component={Link} to="/admin/automation">
            {t('actions.cancel')}
          </Button>
          {dirty && rule && (
            <Text size="sm" c="dimmed">
              {t('automation.editor.unsaved')}
            </Text>
          )}
        </Group>
        {rule && (
          <Button variant="subtle" color="red" onClick={() => setConfirmDelete(true)}>
            {t('automation.editor.delete')}
          </Button>
        )}
      </Group>

      {rule && (
        <Modal opened={confirmDelete} onClose={() => setConfirmDelete(false)} title={t('automation.editor.delete')} centered>
          <Stack gap="sm">
            <Text size="sm">{t('automation.editor.deleteConfirm', { name: rule.name })}</Text>
            <Group justify="flex-end">
              <Button variant="default" onClick={() => setConfirmDelete(false)}>
                {t('actions.cancel')}
              </Button>
              <Button
                color="red"
                loading={remove.isPending}
                onClick={() => remove.mutate(rule.id, { onSuccess: () => navigate('/admin/automation', { replace: true }) })}
              >
                {t('automation.editor.delete')}
              </Button>
            </Group>
          </Stack>
        </Modal>
      )}
    </Stack>
  );
}
