import {
  ActionIcon, Badge, Button, Checkbox, Group, Modal, MultiSelect, Select, Stack, Switch, Table, Tabs, Text,
  TextInput, Title,
} from '@mantine/core';
import { IconArrowDown, IconArrowUp, IconPencil, IconPlus } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import { type Dictionary, type DictionaryKind, type OrgUnit, useDictionaries, useOrgUnits, useSaveDictionary, useSaveOrgUnit } from './api';
import {
  type PriorityMatrix, type TicketCategory, type Workflow, type WorkflowStatus, usePriorityMatrix, useTicketCategories,
  useTicketSettingsMutations, useWorkflows,
} from './moduleApi';
import { HistoryDrawer, SaveBar } from './ui/History';
import classes from './ui/admin.module.css';

const TABS = ['categories', 'statuses', 'priorities', 'codes', 'departments'] as const;

/** «Справочники» (ADM-02, ADM-03): категории, статусы, матрица приоритета, коды причин, подразделения. */
export function DictionariesPage() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const tab = params.get('tab') ?? 'categories';
  return (
    <Stack gap="md" maw={1100}>
      <Title order={2}>{t('admin.dicts.title')}</Title>
      <Tabs value={tab} onChange={(v) => setParams(v && v !== 'categories' ? { tab: v } : {}, { replace: true })} keepMounted={false}>
        <Tabs.List>
          {TABS.map((k) => <Tabs.Tab key={k} value={k}>{t(`admin.dicts.tabs.${k}`)}</Tabs.Tab>)}
        </Tabs.List>
        <Tabs.Panel value="categories" pt="md"><CategoriesPanel /></Tabs.Panel>
        <Tabs.Panel value="statuses" pt="md"><StatusesPanel /></Tabs.Panel>
        <Tabs.Panel value="priorities" pt="md"><MatrixPanel /></Tabs.Panel>
        <Tabs.Panel value="codes" pt="md"><CodesPanel /></Tabs.Panel>
        <Tabs.Panel value="departments" pt="md"><DepartmentsPanel /></Tabs.Panel>
      </Tabs>
    </Stack>
  );
}

const TICKET_TYPES = ['incident', 'request', 'problem', 'change', 'task'];

// ---------------------------------------------------------------- категории

function CategoriesPanel() {
  const { t } = useTranslation();
  const cats = useTicketCategories();
  const [editing, setEditing] = useState<Partial<TicketCategory> | null>(null);
  const [history, setHistory] = useState(false);
  const rows = cats.data ?? [];
  const state = listStateOf(cats, rows.length);
  const name = (id: string | null) => rows.find((c) => c.id === id)?.name ?? '';
  return (
    <Stack gap="sm">
      <Group gap="xs">
        <Button leftSection={<IconPlus size={16} />} onClick={() => setEditing({ ticketTypes: ['incident', 'request'], active: true })}>
          {t('admin.dicts.addCategory')}
        </Button>
        <Button variant="subtle" onClick={() => setHistory(true)}>{t('admin.history.button')}</Button>
      </Group>
      {state ? <ListState state={state} error={cats.error} /> : (
        <Table highlightOnHover verticalSpacing={6}>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t('admin.dicts.name')}</Table.Th>
              <Table.Th>{t('admin.dicts.types')}</Table.Th>
              <Table.Th>{t('admin.dicts.state')}</Table.Th>
              <Table.Th w={48} />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {rows.map((c) => (
              <Table.Tr key={c.id}>
                <Table.Td>{c.parentId ? `${name(c.parentId)} / ${c.name}` : c.name}</Table.Td>
                <Table.Td>{c.ticketTypes.map((x) => t(`admin.dicts.ticketTypes.${x}`)).join(', ')}</Table.Td>
                <Table.Td><ActiveBadge active={c.active} /></Table.Td>
                <Table.Td>
                  <ActionIcon variant="subtle" aria-label={t('admin.dicts.edit', { name: c.name })} onClick={() => setEditing(c)}>
                    <IconPencil size={16} />
                  </ActionIcon>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
      {editing && <CategoryModal value={editing} all={rows} onClose={() => setEditing(null)} />}
      <HistoryDrawer opened={history} onClose={() => setHistory(false)} entity="tickets.category" title={t('admin.dicts.tabs.categories')} />
    </Stack>
  );
}

function ActiveBadge({ active }: { active: boolean }) {
  const { t } = useTranslation();
  return <Badge variant={active ? 'light' : 'outline'}>{active ? t('admin.dicts.active') : t('admin.dicts.inactive')}</Badge>;
}

function CategoryModal({ value, all, onClose }: { value: Partial<TicketCategory>; all: TicketCategory[]; onClose: () => void }) {
  const { t } = useTranslation();
  const save = useTicketSettingsMutations().category;
  const [form, setForm] = useState({ name: value.name ?? '', parentId: value.parentId ?? null,
    ticketTypes: value.ticketTypes ?? [], active: value.active ?? true });
  return (
    <Modal opened onClose={onClose} title={value.id ? value.name : t('admin.dicts.addCategory')}>
      <Stack gap="sm">
        <TextInput label={t('admin.dicts.name')} required value={form.name} onChange={(e) => setForm({ ...form, name: e.currentTarget.value })} />
        <Select label={t('admin.dicts.parent')} clearable value={form.parentId}
          data={all.filter((c) => c.id !== value.id && !c.parentId).map((c) => ({ value: c.id, label: c.name }))}
          onChange={(v) => setForm({ ...form, parentId: v })} />
        <MultiSelect label={t('admin.dicts.types')} value={form.ticketTypes}
          data={TICKET_TYPES.map((x) => ({ value: x, label: t(`admin.dicts.ticketTypes.${x}`) }))}
          onChange={(v) => setForm({ ...form, ticketTypes: v })} />
        <Switch label={t('admin.dicts.active')} checked={form.active} onChange={(e) => setForm({ ...form, active: e.currentTarget.checked })} />
        <ErrorLine error={save.error} />
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>{t('admin.cancel')}</Button>
          <Button disabled={!form.name.trim()} loading={save.isPending}
            onClick={() => save.mutate({ id: value.id, version: value.version, ...form }, { onSuccess: onClose })}>
            {t('admin.save')}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}

// ---------------------------------------------------------------- статусы

const CATEGORIES = ['draft', 'new', 'approval', 'assigned', 'in_progress', 'pending', 'resolved', 'closed', 'cancelled'];

function StatusesPanel() {
  const { t } = useTranslation();
  const workflows = useWorkflows();
  const [type, setType] = useState('incident');
  const wf = workflows.data?.find((w) => w.type === type);
  if (workflows.isPending) return <ListState state="loading" />;
  if (workflows.error) return <ListState state="error" error={workflows.error} />;
  return (
    <Stack gap="sm">
      <Select label={t('admin.dicts.workflowType')} value={type} allowDeselect={false} w={260}
        data={(workflows.data ?? []).map((w) => ({ value: w.type, label: t(`admin.dicts.ticketTypes.${w.type}`) }))}
        onChange={(v) => v && setType(v)} />
      {wf && <WorkflowEditor key={`${wf.type}-${wf.version}`} wf={wf} />}
    </Stack>
  );
}

function WorkflowEditor({ wf }: { wf: Workflow }) {
  const { t } = useTranslation();
  const save = useTicketSettingsMutations().workflow;
  const [statuses, setStatuses] = useState<WorkflowStatus[]>(wf.statuses);
  const [history, setHistory] = useState(false);
  const [code, setCode] = useState('');
  const dirty = JSON.stringify(statuses) !== JSON.stringify(wf.statuses);
  const set = (i: number, s: Partial<WorkflowStatus>) => setStatuses(statuses.map((x, j) => (j === i ? { ...x, ...s } : x)));
  const used = new Set(wf.transitions.flatMap((tr) => [tr.from, tr.to]));
  const validCode = /^[a-z][a-z0-9_]{0,39}$/.test(code) && !statuses.some((s) => s.code === code);

  return (
    <Stack gap="sm" className={classes.panel}>
      <Text size="sm" className={classes.muted}>{t('admin.dicts.statusesHint')}</Text>
      <Table verticalSpacing={6}>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>{t('admin.dicts.code')}</Table.Th>
            <Table.Th>{t('admin.dicts.name')}</Table.Th>
            <Table.Th>{t('admin.dicts.category')}</Table.Th>
            <Table.Th>{t('admin.dicts.transitions')}</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {statuses.map((s, i) => (
            <Table.Tr key={s.code}>
              <Table.Td className={classes.mono}>{s.code}</Table.Td>
              <Table.Td>
                <TextInput aria-label={t('admin.dicts.name')} value={s.name} onChange={(e) => set(i, { name: e.currentTarget.value })} />
              </Table.Td>
              <Table.Td>
                <Select aria-label={t('admin.dicts.category')} value={s.category} allowDeselect={false} disabled={used.has(s.code)}
                  data={CATEGORIES.map((c) => ({ value: c, label: t(`admin.dicts.statusCategories.${c}`) }))}
                  onChange={(v) => v && set(i, { category: v })} />
              </Table.Td>
              <Table.Td>
                <Text size="sm">{wf.transitions.filter((tr) => tr.from === s.code).length}</Text>
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
      <Group gap="xs" align="flex-end">
        <TextInput label={t('admin.dicts.newStatusCode')} value={code} onChange={(e) => setCode(e.currentTarget.value.trim())}
          error={code && !validCode ? t('admin.dicts.codeRule') : undefined} />
        <Button variant="default" leftSection={<IconPlus size={16} />} disabled={!validCode}
          onClick={() => { setStatuses([...statuses, { code, name: code, category: 'in_progress', initial: false }]); setCode(''); }}>
          {t('admin.dicts.addStatus')}
        </Button>
      </Group>
      <SaveBar dirty={dirty} saving={save.isPending} error={save.error} onCancel={() => setStatuses(wf.statuses)}
        onHistory={() => setHistory(true)} onSave={() => save.mutate({ wf: { ...wf, statuses } })} />
      <HistoryDrawer opened={history} onClose={() => setHistory(false)} entity="tickets.workflow" entityId={wf.type}
        title={t(`admin.dicts.ticketTypes.${wf.type}`)}
        onRestore={async (snap) => {
          const s = snap as Pick<Workflow, 'statuses' | 'transitions'>;
          await save.mutateAsync({ wf: { ...wf, statuses: s.statuses, transitions: s.transitions }, restore: true });
        }} />
    </Stack>
  );
}

// ---------------------------------------------------------------- матрица приоритета

const LEVELS = ['high', 'medium', 'low'];
const PRIORITIES = ['P1', 'P2', 'P3', 'P4'];

function MatrixPanel() {
  const matrix = usePriorityMatrix();
  if (matrix.isPending) return <ListState state="loading" />;
  if (matrix.error || !matrix.data) return <ListState state="error" error={matrix.error} />;
  return <MatrixEditor key={matrix.data.version} matrix={matrix.data} />;
}

function MatrixEditor({ matrix }: { matrix: PriorityMatrix }) {
  const { t } = useTranslation();
  const save = useTicketSettingsMutations().matrix;
  const [cells, setCells] = useState(matrix.cells);
  const [history, setHistory] = useState(false);
  const dirty = JSON.stringify(cells) !== JSON.stringify(matrix.cells);
  return (
    <Stack gap="sm" className={classes.panel}>
      <Text size="sm" className={classes.muted}>{t('admin.dicts.matrixHint')}</Text>
      <div className={classes.scroll}>
        <Table withTableBorder withColumnBorders maw={560}>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t('admin.dicts.impactUrgency')}</Table.Th>
              {LEVELS.map((u) => <Table.Th key={u}>{t(`admin.dicts.levels.${u}`)}</Table.Th>)}
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {LEVELS.map((impact) => (
              <Table.Tr key={impact}>
                <Table.Th>{t(`admin.dicts.levels.${impact}`)}</Table.Th>
                {LEVELS.map((urgency) => (
                  <Table.Td key={urgency}>
                    <Select aria-label={t('admin.dicts.cell', { impact: t(`admin.dicts.levels.${impact}`), urgency: t(`admin.dicts.levels.${urgency}`) })}
                      data={PRIORITIES} allowDeselect={false} value={cells[impact]?.[urgency] ?? null}
                      onChange={(v) => v && setCells({ ...cells, [impact]: { ...cells[impact], [urgency]: v } })} />
                  </Table.Td>
                ))}
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </div>
      <SaveBar dirty={dirty} saving={save.isPending} error={save.error} onCancel={() => setCells(matrix.cells)}
        onHistory={() => setHistory(true)} onSave={() => save.mutate({ m: { ...matrix, cells } })} />
      <HistoryDrawer opened={history} onClose={() => setHistory(false)} entity="tickets.priority_matrix" entityId=""
        title={t('admin.dicts.tabs.priorities')}
        onRestore={async (snap) => {
          await save.mutateAsync({ m: { ...matrix, cells: (snap as PriorityMatrix).cells }, restore: true });
        }} />
    </Stack>
  );
}

// ---------------------------------------------------------------- коды причин

function CodesPanel() {
  const { t } = useTranslation();
  const dicts = useDictionaries();
  const [kind, setKind] = useState<DictionaryKind>('resolution');
  const d = dicts.data?.find((x) => x.kind === kind);
  if (dicts.isPending) return <ListState state="loading" />;
  if (dicts.error) return <ListState state="error" error={dicts.error} />;
  return (
    <Stack gap="sm">
      <Select label={t('admin.dicts.codeKind')} value={kind} allowDeselect={false} w={260}
        data={(['resolution', 'pending', 'cancel'] as const).map((k) => ({ value: k, label: t(`admin.dicts.codeKinds.${k}`) }))}
        onChange={(v) => v && setKind(v as DictionaryKind)} />
      {d && <CodesEditor key={`${d.kind}-${d.version}`} dict={d} />}
    </Stack>
  );
}

function CodesEditor({ dict }: { dict: Dictionary }) {
  const { t } = useTranslation();
  const save = useSaveDictionary();
  const [items, setItems] = useState(dict.items);
  const [history, setHistory] = useState(false);
  const [code, setCode] = useState('');
  const saved = new Set(dict.items.map((i) => i.code));
  const dirty = JSON.stringify(items) !== JSON.stringify(dict.items);
  const set = (i: number, v: Partial<Dictionary['items'][number]>) => setItems(items.map((x, j) => (j === i ? { ...x, ...v } : x)));
  const move = (i: number, d: number) => {
    const next = [...items];
    next.splice(i + d, 0, ...next.splice(i, 1));
    setItems(next);
  };
  const validCode = /^[a-z][a-z0-9_]{0,39}$/.test(code) && !items.some((x) => x.code === code);

  return (
    <Stack gap="sm" className={classes.panel}>
      <Text size="sm" className={classes.muted}>{t('admin.dicts.codesHint')}</Text>
      <Table verticalSpacing={6}>
        <Table.Tbody>
          {items.map((it, i) => (
            <Table.Tr key={it.code}>
              <Table.Td className={classes.mono} w={160}>{it.code}</Table.Td>
              <Table.Td>
                <TextInput aria-label={t('admin.dicts.name')} value={it.name} onChange={(e) => set(i, { name: e.currentTarget.value })} />
              </Table.Td>
              <Table.Td w={120}>
                <Checkbox label={t('admin.dicts.active')} checked={it.active} onChange={(e) => set(i, { active: e.currentTarget.checked })} />
              </Table.Td>
              <Table.Td w={120}>
                <Group gap={2} wrap="nowrap">
                  <ActionIcon variant="subtle" disabled={i === 0} aria-label={t('admin.dicts.up')} onClick={() => move(i, -1)}>
                    <IconArrowUp size={16} />
                  </ActionIcon>
                  <ActionIcon variant="subtle" disabled={i === items.length - 1} aria-label={t('admin.dicts.down')} onClick={() => move(i, 1)}>
                    <IconArrowDown size={16} />
                  </ActionIcon>
                  {!saved.has(it.code) && (
                    <Button size="compact-xs" variant="subtle" color="red" onClick={() => setItems(items.filter((_, j) => j !== i))}>
                      {t('admin.dicts.removeNew')}
                    </Button>
                  )}
                </Group>
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
      <Group gap="xs" align="flex-end">
        <TextInput label={t('admin.dicts.newCode')} value={code} onChange={(e) => setCode(e.currentTarget.value.trim())}
          error={code && !validCode ? t('admin.dicts.codeRule') : undefined} />
        <Button variant="default" leftSection={<IconPlus size={16} />} disabled={!validCode}
          onClick={() => { setItems([...items, { code, name: code, active: true }]); setCode(''); }}>
          {t('admin.dicts.addCode')}
        </Button>
      </Group>
      <SaveBar dirty={dirty} saving={save.isPending} error={save.error} onCancel={() => setItems(dict.items)}
        onHistory={() => setHistory(true)} onSave={() => save.mutate({ ...dict, items })} />
      <HistoryDrawer opened={history} onClose={() => setHistory(false)} entity="admin.dictionary" entityId={dict.kind}
        title={t(`admin.dicts.codeKinds.${dict.kind}`)}
        onRestore={async (snap) => {
          const old = (snap as Dictionary).items;
          // Коды, добавленные после той версии, удалить нельзя: они остаются выключенными.
          const extra = dict.items.filter((x) => !old.some((o) => o.code === x.code)).map((x) => ({ ...x, active: false }));
          await save.mutateAsync({ ...dict, items: [...old, ...extra] });
        }} />
    </Stack>
  );
}

// ---------------------------------------------------------------- подразделения

function DepartmentsPanel() {
  const { t } = useTranslation();
  const units = useOrgUnits();
  const [editing, setEditing] = useState<Partial<OrgUnit> | null>(null);
  const [history, setHistory] = useState(false);
  const rows = units.data ?? [];
  const state = listStateOf(units, rows.length);
  const name = (id: string | null) => rows.find((u) => u.id === id)?.name ?? '—';
  return (
    <Stack gap="sm">
      <Group gap="xs">
        <Button leftSection={<IconPlus size={16} />} onClick={() => setEditing({ kind: 'department', active: true })}>
          {t('admin.dicts.addDepartment')}
        </Button>
        <Button variant="default" onClick={() => setEditing({ kind: 'organization', active: true })}>
          {t('admin.dicts.addOrganization')}
        </Button>
        <Button variant="subtle" onClick={() => setHistory(true)}>{t('admin.history.button')}</Button>
      </Group>
      {state ? <ListState state={state} error={units.error} title={t('admin.dicts.noDepartments')} /> : (
        <Table highlightOnHover verticalSpacing={6}>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t('admin.dicts.name')}</Table.Th>
              <Table.Th>{t('admin.dicts.kind')}</Table.Th>
              <Table.Th>{t('admin.dicts.code')}</Table.Th>
              <Table.Th>{t('admin.dicts.parent')}</Table.Th>
              <Table.Th>{t('admin.dicts.state')}</Table.Th>
              <Table.Th w={48} />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {rows.map((u) => (
              <Table.Tr key={u.id}>
                <Table.Td>{u.name}</Table.Td>
                <Table.Td>{t(`admin.dicts.kinds.${u.kind}`)}</Table.Td>
                <Table.Td className={classes.mono}>{u.code ?? '—'}</Table.Td>
                <Table.Td>{name(u.parentId)}</Table.Td>
                <Table.Td><ActiveBadge active={u.active} /></Table.Td>
                <Table.Td>
                  <ActionIcon variant="subtle" aria-label={t('admin.dicts.edit', { name: u.name })} onClick={() => setEditing(u)}>
                    <IconPencil size={16} />
                  </ActionIcon>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
      {editing && <OrgUnitModal value={editing} all={rows} onClose={() => setEditing(null)} />}
      <HistoryDrawer opened={history} onClose={() => setHistory(false)} entity="admin.org_unit" title={t('admin.dicts.tabs.departments')} />
    </Stack>
  );
}

function OrgUnitModal({ value, all, onClose }: { value: Partial<OrgUnit>; all: OrgUnit[]; onClose: () => void }) {
  const { t } = useTranslation();
  const save = useSaveOrgUnit();
  const [form, setForm] = useState({ name: value.name ?? '', code: value.code ?? '', parentId: value.parentId ?? null,
    active: value.active ?? true });
  return (
    <Modal opened onClose={onClose} title={value.id ? value.name : t(`admin.dicts.kinds.${value.kind}`)}>
      <Stack gap="sm">
        <TextInput label={t('admin.dicts.name')} required value={form.name} onChange={(e) => setForm({ ...form, name: e.currentTarget.value })} />
        <TextInput label={t('admin.dicts.code')} value={form.code} onChange={(e) => setForm({ ...form, code: e.currentTarget.value })} />
        <Select label={t('admin.dicts.parent')} clearable searchable value={form.parentId}
          data={all.filter((u) => u.id !== value.id).map((u) => ({ value: u.id, label: u.name }))}
          onChange={(v) => setForm({ ...form, parentId: v })} />
        {value.id && <Switch label={t('admin.dicts.active')} checked={form.active} onChange={(e) => setForm({ ...form, active: e.currentTarget.checked })} />}
        <ErrorLine error={save.error} />
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>{t('admin.cancel')}</Button>
          <Button disabled={!form.name.trim()} loading={save.isPending}
            onClick={() => save.mutate({ ...value, kind: value.kind ?? 'department', ...form }, { onSuccess: onClose })}>
            {t('admin.save')}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
