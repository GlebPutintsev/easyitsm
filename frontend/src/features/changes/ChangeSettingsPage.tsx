import { ActionIcon, Badge, Button, Checkbox, Group, Modal, NumberInput, Select, SimpleGrid, Stack, Switch, Table, Tabs, Text, Textarea, TextInput, Title } from '@mantine/core';
import { IconPencil, IconPlus, IconTrash } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import {
  type ChangeSettings,
  type ChangeType,
  type Freeze,
  type RiskLevel,
  type Route,
  type StepInput,
  type Template,
  useChangeSettings,
  useDeleteFreeze,
  useFreezes,
  useSaveChangeSettings,
  useSaveFreeze,
  useSaveTemplate,
  useTemplates,
} from './api';
import classes from './changes.module.css';
import { CiPicker } from './CiPicker';
import { fromLocalInput, PHASES, toLocalInput } from './model';

/** «Управление → Изменения»: шаблоны (CHG-08), заморозки (CHG-05), маршруты согласования (CHG-04, CHG-12). */
export function ChangeSettingsPage() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const tab = params.get('tab') ?? 'templates';
  return (
    <Stack gap="md" maw={1200}>
      <div>
        <Title order={1} fz={24}>
          {t('changes.settings.title')}
        </Title>
        <Text size="sm" c="dimmed">
          {t('changes.settings.subtitle')}
        </Text>
      </div>
      <Tabs value={tab} onChange={(v) => setParams(v && v !== 'templates' ? { tab: v } : {}, { replace: true })} keepMounted={false}>
        <Tabs.List>
          <Tabs.Tab value="templates">{t('changes.settings.templates')}</Tabs.Tab>
          <Tabs.Tab value="freezes">{t('changes.settings.freezes')}</Tabs.Tab>
          <Tabs.Tab value="routes">{t('changes.settings.routes')}</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="templates" pt="md">
          <TemplatesPanel />
        </Tabs.Panel>
        <Tabs.Panel value="freezes" pt="md">
          <FreezesPanel />
        </Tabs.Panel>
        <Tabs.Panel value="routes" pt="md">
          <RoutesPanel />
        </Tabs.Panel>
      </Tabs>
    </Stack>
  );
}

// ---------------------------------------------------------------- шаблоны

function TemplatesPanel() {
  const { t, i18n } = useTranslation();
  const templates = useTemplates();
  const [editing, setEditing] = useState<Template | 'new' | null>(null);
  const list = templates.data ?? [];
  const state = listStateOf(templates, list.length);
  return (
    <Stack gap="sm">
      <Group justify="flex-end">
        <Button leftSection={<IconPlus size={16} />} onClick={() => setEditing('new')}>
          {t('changes.settings.newTemplate')}
        </Button>
      </Group>
      {state ? (
        <ListState state={state} title={state === 'empty' ? t('changes.settings.noTemplates') : undefined} error={templates.error} onRetry={() => void templates.refetch()} />
      ) : (
        <div className={classes.tableScroll}>
          <Table verticalSpacing="sm" highlightOnHover miw={760}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('changes.settings.columns.name')}</Table.Th>
                <Table.Th w={150}>{t('changes.columns.type')}</Table.Th>
                <Table.Th w={180}>{t('changes.settings.columns.pipelineKey')}</Table.Th>
                <Table.Th w={90}>{t('changes.settings.columns.steps')}</Table.Th>
                <Table.Th w={150}>{t('changes.settings.columns.updated')}</Table.Th>
                <Table.Th w={60} />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {list.map((tpl) => (
                <Table.Tr key={tpl.id}>
                  <Table.Td>
                    <Text fw={600}>{tpl.name}</Text>
                    <Group gap={4}>
                      {tpl.preapproved && (
                        <Badge size="xs" color="green" variant="light">
                          {t('changes.settings.preapproved')}
                        </Badge>
                      )}
                      {!tpl.active && (
                        <Badge size="xs" color="gray" variant="light">
                          {t('changes.settings.inactive')}
                        </Badge>
                      )}
                    </Group>
                  </Table.Td>
                  <Table.Td>{t(`changes.type.${tpl.changeType}`)}</Table.Td>
                  <Table.Td>
                    <span className={classes.number}>{tpl.pipelineKey ?? '—'}</span>
                  </Table.Td>
                  <Table.Td>{tpl.steps.length}</Table.Td>
                  <Table.Td>{new Date(tpl.updatedAt).toLocaleDateString(i18n.language)}</Table.Td>
                  <Table.Td>
                    <ActionIcon variant="subtle" aria-label={t('changes.settings.edit')} onClick={() => setEditing(tpl)}>
                      <IconPencil size={16} />
                    </ActionIcon>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </div>
      )}
      {editing && <TemplateDialog template={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </Stack>
  );
}

function stepsToText(steps: StepInput[]): Record<string, string> {
  const out: Record<string, string> = {};
  for (const ph of PHASES) {
    out[ph] = steps
      .filter((s) => s.phase === ph)
      .map((s) => `${s.title}${s.durationMinutes ? ` | ${s.durationMinutes}` : ''}`)
      .join('\n');
  }
  return out;
}

/** Шаги шаблона одной строкой: «Название | минуты». Подробности шагов правятся уже в изменении. */
function textToSteps(text: Record<string, string>): StepInput[] {
  return PHASES.flatMap((phase) =>
    (text[phase] ?? '')
      .split('\n')
      .map((l) => l.trim())
      .filter(Boolean)
      .map((l) => {
        const [title = '', minutes] = l.split('|').map((x) => x.trim());
        return { phase, title, durationMinutes: Number(minutes) || 0 };
      }),
  );
}

function TemplateDialog({ template, onClose }: { template: Template | null; onClose: () => void }) {
  const { t } = useTranslation();
  const save = useSaveTemplate();
  const content = (template?.content ?? {}) as Record<string, unknown>;
  const [name, setName] = useState(template?.name ?? '');
  const [description, setDescription] = useState(template?.description ?? '');
  const [changeType, setChangeType] = useState<ChangeType>(template?.changeType ?? 'standard');
  const [riskLevel, setRiskLevel] = useState<RiskLevel | null>(template?.riskLevel ?? 'low');
  const [pipelineKey, setPipelineKey] = useState(template?.pipelineKey ?? '');
  const [active, setActive] = useState(template?.active ?? true);
  const [goal, setGoal] = useState(String(content.goal ?? ''));
  const [justification, setJustification] = useState(String(content.justification ?? ''));
  const [impact, setImpact] = useState(String(content.impact ?? ''));
  const [downtime, setDowntime] = useState<number | string>(typeof content.downtimeMinutes === 'number' ? content.downtimeMinutes : 0);
  const [steps, setSteps] = useState(stepsToText(template?.steps ?? []));
  const [serviceIds, setServiceIds] = useState(template?.serviceIds ?? []);
  const [ciIds, setCiIds] = useState(template?.ciIds ?? []);
  const keyValid = !pipelineKey || /^[a-z0-9][a-z0-9._-]{1,63}$/.test(pipelineKey);

  const submit = () =>
    save.mutate(
      {
        id: template?.id,
        version: template?.version,
        input: {
          name: name.trim(),
          description: description.trim() || null,
          changeType,
          preapproved: changeType === 'standard',
          riskLevel: changeType === 'standard' ? (riskLevel ?? 'low') : riskLevel,
          pipelineKey: pipelineKey.trim() || null,
          active,
          content: { goal, justification, impact, downtimeMinutes: Number(downtime) || 0 },
          steps: textToSteps(steps),
          serviceIds,
          ciIds,
        },
      },
      { onSuccess: onClose },
    );

  return (
    <Modal opened onClose={onClose} title={template ? t('changes.settings.editTemplate') : t('changes.settings.newTemplate')} size="xl" centered>
      <Stack gap="sm">
        <SimpleGrid cols={{ base: 1, sm: 2 }}>
          <TextInput label={t('changes.settings.columns.name')} withAsterisk value={name} onChange={(e) => setName(e.currentTarget.value)} />
          <Select
            label={t('changes.columns.type')}
            value={changeType}
            onChange={(v) => setChangeType((v as ChangeType) ?? 'standard')}
            allowDeselect={false}
            data={(['standard', 'normal', 'emergency'] as const).map((v) => ({ value: v, label: t(`changes.type.${v}`) }))}
          />
          <Select
            label={t('changes.settings.riskLevel')}
            clearable={changeType !== 'standard'}
            value={riskLevel}
            onChange={(v) => setRiskLevel(v as RiskLevel | null)}
            data={(['low', 'medium', 'high'] as const).map((v) => ({ value: v, label: t(`changes.risk.level.${v}`) }))}
          />
          <TextInput
            label={t('changes.settings.columns.pipelineKey')}
            description={t('changes.settings.pipelineHint')}
            value={pipelineKey}
            onChange={(e) => setPipelineKey(e.currentTarget.value)}
            error={keyValid ? undefined : t('changes.settings.pipelineInvalid')}
          />
        </SimpleGrid>
        {changeType === 'standard' && (
          <Text size="sm" c="dimmed">
            {t('changes.settings.standardHint')}
          </Text>
        )}
        <Textarea label={t('changes.settings.description')} autosize minRows={1} value={description} onChange={(e) => setDescription(e.currentTarget.value)} />
        <Textarea label={t('changes.fields.goal')} autosize minRows={1} value={goal} onChange={(e) => setGoal(e.currentTarget.value)} />
        <Textarea label={t('changes.fields.justification')} autosize minRows={1} value={justification} onChange={(e) => setJustification(e.currentTarget.value)} />
        <Textarea label={t('changes.fields.impact')} autosize minRows={1} value={impact} onChange={(e) => setImpact(e.currentTarget.value)} />
        <NumberInput label={t('changes.fields.downtime')} min={0} value={downtime} onChange={setDowntime} suffix={` ${t('changes.duration.min')}`} w={220} />
        <CiPicker label={t('changes.fields.services')} services value={serviceIds} onChange={setServiceIds} />
        <CiPicker label={t('changes.fields.cis')} value={ciIds} onChange={setCiIds} />
        <Text size="sm" fw={600}>
          {t('changes.settings.steps')}
        </Text>
        <Text size="xs" c="dimmed">
          {t('changes.settings.stepsHint')}
        </Text>
        <SimpleGrid cols={{ base: 1, sm: 2 }}>
          {PHASES.map((ph) => (
            <Textarea key={ph} label={t(`changes.phase.${ph}`)} autosize minRows={2} value={steps[ph] ?? ''} onChange={(e) => {
              const v = e.currentTarget.value;
              setSteps((s) => ({ ...s, [ph]: v }));
            }} />
          ))}
        </SimpleGrid>
        <Switch label={t('changes.settings.active')} checked={active} onChange={(e) => setActive(e.currentTarget.checked)} />
        <ErrorLine error={save.error} />
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            {t('actions.cancel')}
          </Button>
          <Button disabled={!name.trim() || !keyValid} loading={save.isPending} onClick={submit}>
            {t('changes.actions.save')}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}

// ---------------------------------------------------------------- заморозки

function FreezesPanel() {
  const { t, i18n } = useTranslation();
  const freezes = useFreezes();
  const remove = useDeleteFreeze();
  const [editing, setEditing] = useState<Freeze | 'new' | null>(null);
  const list = freezes.data ?? [];
  const state = listStateOf(freezes, list.length);
  const fmt = (iso: string) => new Date(iso).toLocaleString(i18n.language, { dateStyle: 'short', timeStyle: 'short' });
  return (
    <Stack gap="sm">
      <Group justify="space-between">
        <Text size="sm" c="dimmed">
          {t('changes.settings.freezesHint')}
        </Text>
        <Button leftSection={<IconPlus size={16} />} onClick={() => setEditing('new')}>
          {t('changes.settings.newFreeze')}
        </Button>
      </Group>
      <ErrorLine error={remove.error} />
      {state ? (
        <ListState state={state} title={state === 'empty' ? t('changes.settings.noFreezes') : undefined} error={freezes.error} onRetry={() => void freezes.refetch()} />
      ) : (
        <Table verticalSpacing="sm">
          <Table.Tbody>
            {list.map((f) => (
              <Table.Tr key={f.id}>
                <Table.Td>
                  <Text fw={600}>{f.title}</Text>
                  {f.reason && (
                    <Text size="xs" c="dimmed">
                      {f.reason}
                    </Text>
                  )}
                </Table.Td>
                <Table.Td>
                  {fmt(f.startsAt)} – {fmt(f.endsAt)}
                </Table.Td>
                <Table.Td>
                  {f.scope === 'all' ? t('changes.settings.scopeAll') : t('changes.settings.scopeTargeted', { n: f.serviceIds.length + f.ciIds.length })}
                </Table.Td>
                <Table.Td w={90}>
                  <Group gap={4} wrap="nowrap">
                    <ActionIcon variant="subtle" aria-label={t('changes.settings.edit')} onClick={() => setEditing(f)}>
                      <IconPencil size={16} />
                    </ActionIcon>
                    <ActionIcon variant="subtle" color="red" aria-label={t('changes.settings.delete')} onClick={() => remove.mutate(f)}>
                      <IconTrash size={16} />
                    </ActionIcon>
                  </Group>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
      {editing && <FreezeDialog freeze={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </Stack>
  );
}

function FreezeDialog({ freeze, onClose }: { freeze: Freeze | null; onClose: () => void }) {
  const { t } = useTranslation();
  const save = useSaveFreeze();
  const [title, setTitle] = useState(freeze?.title ?? '');
  const [reason, setReason] = useState(freeze?.reason ?? '');
  const [start, setStart] = useState(toLocalInput(freeze?.startsAt));
  const [end, setEnd] = useState(toLocalInput(freeze?.endsAt));
  const [scope, setScope] = useState<'all' | 'targeted'>(freeze?.scope ?? 'all');
  const [serviceIds, setServiceIds] = useState(freeze?.serviceIds ?? []);
  const [ciIds, setCiIds] = useState(freeze?.ciIds ?? []);
  const s = fromLocalInput(start);
  const e = fromLocalInput(end);
  const valid = !!title.trim() && !!s && !!e && e > s && (scope === 'all' || serviceIds.length + ciIds.length > 0);
  return (
    <Modal opened onClose={onClose} title={freeze ? t('changes.settings.editFreeze') : t('changes.settings.newFreeze')} size="lg" centered>
      <Stack gap="sm">
        <TextInput label={t('changes.settings.freezeTitle')} withAsterisk value={title} onChange={(ev) => setTitle(ev.currentTarget.value)} />
        <Textarea label={t('changes.settings.freezeReason')} autosize minRows={2} value={reason} onChange={(ev) => setReason(ev.currentTarget.value)} />
        <Group grow>
          <TextInput type="datetime-local" label={t('changes.settings.from')} withAsterisk value={start} onChange={(ev) => setStart(ev.currentTarget.value)} />
          <TextInput type="datetime-local" label={t('changes.settings.to')} withAsterisk value={end} onChange={(ev) => setEnd(ev.currentTarget.value)} />
        </Group>
        <Select
          label={t('changes.settings.scope')}
          value={scope}
          allowDeselect={false}
          onChange={(v) => setScope((v as 'all' | 'targeted') ?? 'all')}
          data={[
            { value: 'all', label: t('changes.settings.scopeAll') },
            { value: 'targeted', label: t('changes.settings.scopeTargetedLabel') },
          ]}
        />
        {scope === 'targeted' && (
          <>
            <CiPicker label={t('changes.fields.services')} services value={serviceIds} onChange={setServiceIds} />
            <CiPicker label={t('changes.fields.cis')} value={ciIds} onChange={setCiIds} />
          </>
        )}
        <ErrorLine error={save.error} />
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            {t('actions.cancel')}
          </Button>
          <Button
            disabled={!valid}
            loading={save.isPending}
            onClick={() =>
              save.mutate(
                {
                  id: freeze?.id,
                  version: freeze?.version,
                  input: { title: title.trim(), reason: reason.trim() || null, startsAt: s!, endsAt: e!, scope, serviceIds: scope === 'all' ? [] : serviceIds, ciIds: scope === 'all' ? [] : ciIds },
                },
                { onSuccess: onClose },
              )
            }
          >
            {t('changes.actions.save')}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}

// ---------------------------------------------------------------- маршруты

function RoutesPanel() {
  const settings = useChangeSettings();
  if (settings.isPending) return <ListState state="loading" />;
  if (settings.error || !settings.data) return <ListState state="error" error={settings.error} onRetry={() => void settings.refetch()} />;
  return <RoutesForm key={settings.data.version ?? 0} initial={settings.data} />;
}

function RoutesForm({ initial }: { initial: ChangeSettings }) {
  const { t } = useTranslation();
  const save = useSaveChangeSettings();
  const [doc, setDoc] = useState<ChangeSettings>(initial);
  const setRoute = (level: 'low' | 'medium' | 'high', patch: Partial<Route>) => setDoc((d) => ({ ...d, [level]: { ...d[level], ...patch } }));
  return (
    <Stack gap="md" maw={820}>
      <div className={classes.card}>
        <Stack gap="sm">
          <div className={classes.sectionLabel}>{t('changes.settings.routesTitle')}</div>
          <Text size="sm" c="dimmed">
            {t('changes.settings.routesHint')}
          </Text>
          <Table verticalSpacing="sm">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('changes.columns.risk')}</Table.Th>
                <Table.Th>{t('changes.approvals.kind.manager')}</Table.Th>
                <Table.Th>{t('changes.approvals.kind.cab')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {(['low', 'medium', 'high'] as const).map((level) => (
                <Table.Tr key={level}>
                  <Table.Td fw={600}>{t(`changes.risk.level.${level}`)}</Table.Td>
                  <Table.Td>
                    <Checkbox aria-label={`${t(`changes.risk.level.${level}`)}: ${t('changes.approvals.kind.manager')}`} checked={doc[level].manager} onChange={(e) => setRoute(level, { manager: e.currentTarget.checked })} />
                  </Table.Td>
                  <Table.Td>
                    <Checkbox aria-label={`${t(`changes.risk.level.${level}`)}: CAB`} checked={doc[level].cab} onChange={(e) => setRoute(level, { cab: e.currentTarget.checked })} />
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Stack>
      </div>
      <div className={classes.card}>
        <Stack gap="sm">
          <div className={classes.sectionLabel}>{t('changes.type.emergency')}</div>
          <Switch
            label={t('changes.settings.emergencyPre')}
            checked={doc.emergency.preApproval}
            onChange={(e) => {
              const preApproval = e.currentTarget.checked;
              setDoc((d) => ({ ...d, emergency: { ...d.emergency, preApproval } }));
            }}
          />
          <NumberInput
            label={t('changes.settings.emergencyDays')}
            min={1}
            max={30}
            w={260}
            value={doc.emergency.postApprovalBusinessDays}
            onChange={(v) => setDoc((d) => ({ ...d, emergency: { ...d.emergency, postApprovalBusinessDays: Number(v) || 1 } }))}
          />
        </Stack>
      </div>
      <div className={classes.card}>
        <Stack gap="sm">
          <div className={classes.sectionLabel}>{t('changes.settings.general')}</div>
          <Switch label={t('changes.settings.requireAffected')} checked={doc.requireAffected} onChange={(e) => {
            const v = e.currentTarget.checked;
            setDoc((d) => ({ ...d, requireAffected: v }));
          }} />
          <TextInput label={t('changes.fields.timeZone')} w={260} value={doc.timeZone} onChange={(e) => {
            const v = e.currentTarget.value;
            setDoc((d) => ({ ...d, timeZone: v }));
          }} />
        </Stack>
      </div>
      <ErrorLine error={save.error} />
      <Group justify="flex-end">
        <Button loading={save.isPending} onClick={() => save.mutate(doc)}>
          {t('changes.actions.save')}
        </Button>
      </Group>
    </Stack>
  );
}
