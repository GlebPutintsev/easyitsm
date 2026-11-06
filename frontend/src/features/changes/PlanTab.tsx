import { ActionIcon, Alert, Badge, Button, Checkbox, Group, Menu, NumberInput, Select, Stack, Table, Text, Textarea, TextInput } from '@mantine/core';
import { IconArrowDown, IconArrowUp, IconCheck, IconDots, IconPlus, IconTrash, IconX } from '@tabler/icons-react';
import { Fragment, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { type Change, type Phase, type Step, type StepInput, type StepStatus, useChangeAction } from './api';
import classes from './changes.module.css';
import { formatInZone, fromLocalInput, minutesLabel, PHASES, stepsOf, toLocalInput } from './model';

/** «План»: подготовка, внедрение, проверка и возврат (CHG-03), отметки выполнения во время работ. */
export function PlanTab({ change }: { change: Change }) {
  const { t } = useTranslation();
  const [editing, setEditing] = useState(false);
  const c = change;
  return (
    <Stack gap="md">
      <Totals change={c} />
      {editing ? (
        <PlanEditor change={c} onDone={() => setEditing(false)} />
      ) : (
        <>
          {c.permissions.editPlan && (
            <Group justify="flex-end">
              <Button variant="default" onClick={() => setEditing(true)}>
                {t('changes.plan.edit')}
              </Button>
            </Group>
          )}
          <PlanTable change={c} />
        </>
      )}
    </Stack>
  );
}

function Totals({ change: c }: { change: Change }) {
  const { t, i18n } = useTranslation();
  const p = c.plan;
  return (
    <Group gap="lg" wrap="wrap">
      {(['prepare', 'implement', 'verify', 'rollback'] as const).map((ph) => (
        <div key={ph}>
          <Text size="xs" c="dimmed" fw={500}>
            {t(`changes.phase.${ph}`)}
          </Text>
          <Text fw={700}>{minutesLabel(p[`${ph}Minutes`], t)}</Text>
        </div>
      ))}
      <div>
        <Text size="xs" c="dimmed" fw={500}>
          {t('changes.plan.planEnd')}
        </Text>
        <Text fw={700}>{formatInZone(p.planEnd, i18n.language, c.timeZone)}</Text>
      </div>
      {p.fitsWindow === false && (
        <Alert color="orange" py={6} px="sm">
          {t('changes.plan.overWindow')}
        </Alert>
      )}
    </Group>
  );
}

function PlanTable({ change: c }: { change: Change }) {
  const { t, i18n } = useTranslation();
  const action = useChangeAction(c);
  const canRecord = c.permissions.recordSteps;
  const record = (step: Step, status: StepStatus, note?: string) =>
    action.mutate({ path: `/steps/${step.id}/result`, body: { status, note } });

  return (
    <Stack gap="xs">
      <ErrorLine error={action.error} />
      <div className={classes.tableScroll}>
        <Table verticalSpacing="xs" miw={1100} withTableBorder withColumnBorders>
          <Table.Thead>
            <Table.Tr>
              <Table.Th w={36}>#</Table.Th>
              <Table.Th>{t('changes.plan.columns.title')}</Table.Th>
              <Table.Th>{t('changes.plan.columns.description')}</Table.Th>
              <Table.Th w={150}>{t('changes.plan.columns.owner')}</Table.Th>
              <Table.Th w={120}>{t('changes.plan.columns.start')}</Table.Th>
              <Table.Th w={90}>{t('changes.plan.columns.duration')}</Table.Th>
              <Table.Th w={110}>{t('changes.plan.columns.interruption')}</Table.Th>
              <Table.Th w={160}>{t('changes.plan.columns.check')}</Table.Th>
              <Table.Th w={150}>{t('changes.plan.columns.status')}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {PHASES.map((ph) => {
              const steps = stepsOf(c.steps, ph);
              return (
                <Fragment key={ph}>
                  <Table.Tr className={classes.phaseRow}>
                    <Table.Td colSpan={9}>{t(`changes.phase.${ph}`)}</Table.Td>
                  </Table.Tr>
                  {steps.length === 0 && (
                    <Table.Tr>
                      <Table.Td colSpan={9}>
                        <Text size="sm" c="dimmed">
                          {t('changes.plan.noSteps')}
                        </Text>
                      </Table.Td>
                    </Table.Tr>
                  )}
                  {steps.map((s) => (
                    <Table.Tr key={s.id}>
                      <Table.Td>{s.seq}</Table.Td>
                      <Table.Td fw={600}>{s.title}</Table.Td>
                      <Table.Td>
                        <Text size="sm" style={{ whiteSpace: 'pre-wrap' }}>
                          {s.description}
                        </Text>
                        {s.rollbackRef && (
                          <Text size="xs" c="dimmed">
                            {t('changes.plan.rollbackRef', { n: s.rollbackRef })}
                          </Text>
                        )}
                      </Table.Td>
                      <Table.Td>
                        <Text size="sm">{s.ownerName ?? '—'}</Text>
                        {s.team && (
                          <Text size="xs" c="dimmed">
                            {s.team}
                          </Text>
                        )}
                        {s.contacts && (
                          <Text size="xs" c="dimmed">
                            {s.contacts}
                          </Text>
                        )}
                      </Table.Td>
                      <Table.Td>
                        <Text size="sm">{ph === 'rollback' ? '—' : formatInZone(s.start, i18n.language, c.timeZone)}</Text>
                      </Table.Td>
                      <Table.Td>
                        <Text size="sm">
                          {s.upto ? t('changes.plan.upto') + ' ' : ''}
                          {minutesLabel(s.durationMinutes, t)}
                        </Text>
                      </Table.Td>
                      <Table.Td>
                        <Text size="sm">{s.interruption ?? '—'}</Text>
                      </Table.Td>
                      <Table.Td>
                        <Text size="sm">{s.checkCriteria ?? '—'}</Text>
                      </Table.Td>
                      <Table.Td>
                        <StepStatusCell step={s} canRecord={canRecord && (ph !== 'rollback' || !!c.rollbackStartedAt)} pending={action.isPending} onRecord={record} />
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Fragment>
              );
            })}
          </Table.Tbody>
        </Table>
      </div>
    </Stack>
  );
}

function StepStatusCell({
  step,
  canRecord,
  pending,
  onRecord,
}: {
  step: Step;
  canRecord: boolean;
  pending: boolean;
  onRecord: (s: Step, status: StepStatus, note?: string) => void;
}) {
  const { t } = useTranslation();
  const [failing, setFailing] = useState(false);
  const [note, setNote] = useState('');
  const color = { pending: 'gray', done: 'green', failed: 'red', skipped: 'gray' }[step.status];
  return (
    <Stack gap={4}>
      <Group gap={4} wrap="nowrap">
        <Badge color={color} variant={step.status === 'pending' ? 'outline' : 'light'} radius="sm">
          {t(`changes.stepStatus.${step.status}`)}
        </Badge>
        {canRecord && (
          <Menu withinPortal position="bottom-end">
            <Menu.Target>
              <ActionIcon variant="subtle" size="sm" aria-label={t('changes.plan.record')} disabled={pending}>
                <IconDots size={16} />
              </ActionIcon>
            </Menu.Target>
            <Menu.Dropdown>
              <Menu.Item leftSection={<IconCheck size={14} />} onClick={() => onRecord(step, 'done')}>
                {t('changes.stepStatus.done')}
              </Menu.Item>
              <Menu.Item leftSection={<IconX size={14} />} onClick={() => setFailing(true)}>
                {t('changes.stepStatus.failed')}
              </Menu.Item>
              <Menu.Item onClick={() => onRecord(step, 'skipped')}>{t('changes.stepStatus.skipped')}</Menu.Item>
              {step.status !== 'pending' && <Menu.Item onClick={() => onRecord(step, 'pending')}>{t('changes.plan.resetStatus')}</Menu.Item>}
            </Menu.Dropdown>
          </Menu>
        )}
      </Group>
      {step.resultNote && (
        <Text size="xs" c="dimmed">
          {step.resultNote}
        </Text>
      )}
      {failing && (
        <Stack gap={4}>
          <Textarea size="xs" autosize minRows={2} placeholder={t('changes.plan.failNote')} value={note} onChange={(e) => setNote(e.currentTarget.value)} />
          <Group gap={4}>
            <Button size="compact-xs" color="red" disabled={!note.trim()} onClick={() => { onRecord(step, 'failed', note.trim()); setFailing(false); }}>
              {t('changes.stepStatus.failed')}
            </Button>
            <Button size="compact-xs" variant="default" onClick={() => setFailing(false)}>
              {t('actions.cancel')}
            </Button>
          </Group>
        </Stack>
      )}
    </Stack>
  );
}

type Draft = StepInput & { key: string };

function toDraft(s: Step): Draft {
  return {
    key: s.id,
    id: s.id,
    phase: s.phase,
    title: s.title,
    description: s.description,
    ownerName: s.ownerName,
    team: s.team,
    contacts: s.contacts,
    plannedStart: s.plannedStart,
    durationMinutes: s.durationMinutes,
    upto: s.upto,
    interruption: s.interruption,
    checkCriteria: s.checkCriteria,
    rollbackRef: s.rollbackRef,
  };
}

let seq = 0;

function withoutKey(d: Draft): StepInput {
  const copy: Partial<Draft> = { ...d };
  delete copy.key;
  return copy as StepInput;
}

function PlanEditor({ change: c, onDone }: { change: Change; onDone: () => void }) {
  const { t } = useTranslation();
  const save = useChangeAction(c);
  const [drafts, setDrafts] = useState<Draft[]>(() => PHASES.flatMap((ph) => stepsOf(c.steps, ph).map(toDraft)));
  const update = (key: string, patch: Partial<Draft>) => setDrafts((all) => all.map((d) => (d.key === key ? { ...d, ...patch } : d)));
  const move = (key: string, dir: -1 | 1) =>
    setDrafts((all) => {
      const i = all.findIndex((d) => d.key === key);
      const j = i + dir;
      const a = all[i];
      const b = all[j];
      if (!a || !b || a.phase !== b.phase) return all;
      const next = [...all];
      next[i] = b;
      next[j] = a;
      return next;
    });
  const add = (phase: Phase) =>
    setDrafts((all) => {
      const last = all.map((d) => d.phase).lastIndexOf(phase);
      const next = [...all];
      next.splice(last + 1 || all.length, 0, { key: `new-${++seq}`, phase, title: '', durationMinutes: 15 });
      return next;
    });
  const valid = drafts.every((d) => d.title.trim());

  const submit = () =>
    save.mutate(
      {
        method: 'PUT',
        path: '/plan',
        body: {
          steps: drafts.map((d) => ({
            ...withoutKey(d),
            title: d.title.trim(),
            durationMinutes: Number(d.durationMinutes ?? 0),
          })),
        },
      },
      { onSuccess: onDone },
    );

  return (
    <Stack gap="md">
      {PHASES.map((ph) => (
        <Stack key={ph} gap="xs">
          <div className={classes.sectionLabel}>{t(`changes.phase.${ph}`)}</div>
          {drafts
            .filter((d) => d.phase === ph)
            .map((d, i) => (
              <div key={d.key} className={classes.card}>
                <Stack gap="xs">
                  <Group gap="xs" wrap="nowrap" align="flex-end">
                    <Text fw={700} w={24}>
                      {i + 1}
                    </Text>
                    <TextInput style={{ flex: 1 }} label={t('changes.plan.columns.title')} withAsterisk value={d.title} onChange={(e) => update(d.key, { title: e.currentTarget.value })} error={!d.title.trim() ? ' ' : undefined} />
                    <NumberInput w={140} label={t('changes.plan.columns.duration')} min={0} max={10080} suffix={` ${t('changes.duration.min')}`} value={d.durationMinutes ?? 0} onChange={(v) => update(d.key, { durationMinutes: Number(v) || 0 })} />
                    <ActionIcon variant="subtle" aria-label={t('changes.plan.up')} onClick={() => move(d.key, -1)}>
                      <IconArrowUp size={16} />
                    </ActionIcon>
                    <ActionIcon variant="subtle" aria-label={t('changes.plan.down')} onClick={() => move(d.key, 1)}>
                      <IconArrowDown size={16} />
                    </ActionIcon>
                    <ActionIcon variant="subtle" color="red" aria-label={t('changes.plan.remove')} onClick={() => setDrafts((all) => all.filter((x) => x.key !== d.key))}>
                      <IconTrash size={16} />
                    </ActionIcon>
                  </Group>
                  <Textarea label={t('changes.plan.columns.description')} autosize minRows={1} value={d.description ?? ''} onChange={(e) => update(d.key, { description: e.currentTarget.value })} />
                  <Group grow wrap="wrap">
                    <TextInput label={t('changes.plan.columns.owner')} value={d.ownerName ?? ''} onChange={(e) => update(d.key, { ownerName: e.currentTarget.value })} />
                    <TextInput label={t('changes.plan.team')} value={d.team ?? ''} onChange={(e) => update(d.key, { team: e.currentTarget.value })} />
                    <TextInput label={t('changes.plan.contacts')} value={d.contacts ?? ''} onChange={(e) => update(d.key, { contacts: e.currentTarget.value })} />
                  </Group>
                  <Group grow wrap="wrap" align="flex-end">
                    {ph !== 'rollback' && (
                      <TextInput type="datetime-local" label={t('changes.plan.plannedStart')} description={t('changes.plan.plannedStartHint')} value={toLocalInput(d.plannedStart)} onChange={(e) => update(d.key, { plannedStart: fromLocalInput(e.currentTarget.value) })} />
                    )}
                    <TextInput label={t('changes.plan.columns.interruption')} value={d.interruption ?? ''} onChange={(e) => update(d.key, { interruption: e.currentTarget.value })} />
                    {ph === 'verify' && (
                      <TextInput label={t('changes.plan.columns.check')} value={d.checkCriteria ?? ''} onChange={(e) => update(d.key, { checkCriteria: e.currentTarget.value })} />
                    )}
                    {ph === 'rollback' && (
                      <Select
                        label={t('changes.plan.rollbackOf')}
                        clearable
                        value={d.rollbackRef ? String(d.rollbackRef) : null}
                        onChange={(v) => update(d.key, { rollbackRef: v ? Number(v) : null })}
                        data={drafts.filter((x) => x.phase === 'rollback').map((_, k) => ({ value: String(k + 1), label: String(k + 1) }))}
                      />
                    )}
                    <Checkbox label={t('changes.plan.upto')} checked={!!d.upto} onChange={(e) => update(d.key, { upto: e.currentTarget.checked })} />
                  </Group>
                </Stack>
              </div>
            ))}
          <Button variant="subtle" leftSection={<IconPlus size={16} />} w="fit-content" onClick={() => add(ph)}>
            {t('changes.plan.add')}
          </Button>
        </Stack>
      ))}
      <ErrorLine error={save.error} />
      <Group justify="flex-end">
        <Button variant="default" onClick={onDone}>
          {t('actions.cancel')}
        </Button>
        <Button onClick={submit} disabled={!valid} loading={save.isPending}>
          {t('changes.plan.save')}
        </Button>
      </Group>
    </Stack>
  );
}
