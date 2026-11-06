import {
  ActionIcon, Badge, Button, Checkbox, Group, Modal, NumberInput, Select, SimpleGrid, Stack, Table, Tabs, Text,
  TextInput, Textarea, Title,
} from '@mantine/core';
import { IconPlus, IconTrash } from '@tabler/icons-react';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router';
import { usePersonName } from '@/shared/api/people';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import { PersonPicker } from '@/shared/ui/PersonPicker';
import { useDutyMutations, useDutyShifts } from './api';
import {
  type GroupRow, type Holiday, type Schedule, WEEKDAYS, type Weekday, useGroupMutations, useGroups, useHolidays,
  useMembers, useScheduleMutations, useSchedules,
} from './moduleApi';
import { TIMEZONES } from './SetupWizard';
import { HistoryDrawer, SaveBar, formatTime } from './ui/History';
import classes from './ui/admin.module.css';

/** «Группы и графики» (10.16): Создать, Добавить участника, Назначить дежурство, Праздники. */
export function GroupsPage() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const tab = params.get('tab') ?? 'groups';
  return (
    <Stack gap="md" maw={1200}>
      <Title order={2}>{t('admin.groups.title')}</Title>
      <Tabs value={tab} onChange={(v) => setParams(v && v !== 'groups' ? { tab: v } : {}, { replace: true })} keepMounted={false}>
        <Tabs.List>
          <Tabs.Tab value="groups">{t('admin.groups.tabGroups')}</Tabs.Tab>
          <Tabs.Tab value="schedules">{t('admin.groups.tabSchedules')}</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="groups" pt="md"><GroupsPanel /></Tabs.Panel>
        <Tabs.Panel value="schedules" pt="md"><SchedulesPanel /></Tabs.Panel>
      </Tabs>
    </Stack>
  );
}

const GROUP_TYPES: GroupRow['type'][] = ['support', 'security', 'approval', 'other'];

function GroupsPanel() {
  const { t } = useTranslation();
  const groups = useGroups();
  const rows = groups.data?.items ?? [];
  const [selected, setSelected] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const current = rows.find((g) => g.id === selected) ?? rows[0] ?? null;
  const state = listStateOf(groups, rows.length);

  return (
    <SimpleGrid cols={{ base: 1, md: 3 }} spacing="md">
      <Stack gap="xs" className={classes.panel} style={{ alignSelf: 'start' }}>
        <Button leftSection={<IconPlus size={16} />} variant="default" onClick={() => setCreating(true)}>
          {t('admin.groups.create')}
        </Button>
        {state ? <ListState state={state} error={groups.error} rows={4} /> : rows.map((g) => (
          <button key={g.id} type="button" className={classes.listItem} aria-current={g.id === current?.id}
            onClick={() => setSelected(g.id)}>
            <Text size="sm" fw={500}>{g.name}</Text>
            <Text size="xs" className={classes.muted}>{t(`admin.groups.types.${g.type}`)}</Text>
          </button>
        ))}
      </Stack>
      <div style={{ gridColumn: 'span 2' }}>
        {current && <GroupDetails key={current.id} group={current} />}
      </div>
      <GroupModal opened={creating} onClose={() => setCreating(false)} onCreated={setSelected} />
    </SimpleGrid>
  );
}

function GroupModal({ opened, onClose, onCreated }: { opened: boolean; onClose: () => void; onCreated: (id: string) => void }) {
  const { t } = useTranslation();
  const save = useGroupMutations().save;
  const [name, setName] = useState('');
  const [type, setType] = useState<GroupRow['type']>('support');
  return (
    <Modal opened={opened} onClose={onClose} title={t('admin.groups.create')}>
      <Stack gap="sm">
        <TextInput label={t('admin.groups.name')} required value={name} onChange={(e) => setName(e.currentTarget.value)} />
        <Select label={t('admin.groups.type')} value={type} allowDeselect={false}
          data={GROUP_TYPES.map((v) => ({ value: v, label: t(`admin.groups.types.${v}`) }))}
          onChange={(v) => v && setType(v as GroupRow['type'])} />
        <ErrorLine error={save.error} />
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>{t('admin.cancel')}</Button>
          <Button disabled={!name.trim()} loading={save.isPending}
            onClick={() => save.mutate({ name: name.trim(), description: '', type }, {
              onSuccess: (g) => { setName(''); onCreated(g.id); onClose(); },
            })}>
            {t('admin.groups.create')}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}

function GroupDetails({ group }: { group: GroupRow }) {
  const { t } = useTranslation();
  const m = useGroupMutations();
  const initial = { name: group.name, description: group.description, type: group.type };
  const [form, setForm] = useState(initial);
  const [history, setHistory] = useState(false);
  const dirty = JSON.stringify(form) !== JSON.stringify(initial);
  const managed = group.source !== 'local';

  return (
    <Stack gap="md">
      <Stack gap="sm" className={classes.panel}>
        <Group justify="space-between">
          <Title order={3}>{group.name}</Title>
          {managed && <Badge variant="outline">{t('admin.groups.fromLdap')}</Badge>}
        </Group>
        <SimpleGrid cols={{ base: 1, sm: 2 }}>
          <TextInput label={t('admin.groups.name')} value={form.name} disabled={managed}
            onChange={(e) => setForm({ ...form, name: e.currentTarget.value })} />
          <Select label={t('admin.groups.type')} value={form.type} allowDeselect={false}
            data={GROUP_TYPES.map((v) => ({ value: v, label: t(`admin.groups.types.${v}`) }))}
            onChange={(v) => v && setForm({ ...form, type: v as GroupRow['type'] })} />
        </SimpleGrid>
        <Textarea label={t('admin.groups.description')} autosize minRows={2} value={form.description}
          onChange={(e) => setForm({ ...form, description: e.currentTarget.value })} />
        <SaveBar dirty={dirty} saving={m.save.isPending} error={m.save.error} onCancel={() => setForm(initial)}
          onHistory={() => setHistory(true)}
          onSave={() => m.save.mutate({ id: group.id, version: group.version, ...form })} />
      </Stack>
      <MembersPanel group={group} />
      <DutyPanel group={group} />
      <HistoryDrawer opened={history} onClose={() => setHistory(false)} entity="identity.group" entityId={group.id}
        title={group.name} />
    </Stack>
  );
}

function MembersPanel({ group }: { group: GroupRow }) {
  const { t } = useTranslation();
  const members = useMembers(group.id);
  const m = useGroupMutations();
  const [person, setPerson] = useState<string | null>(null);
  const [lead, setLead] = useState(false);
  const rows = members.data ?? [];
  const state = listStateOf(members, rows.length);
  const managed = group.source !== 'local';

  return (
    <Stack gap="sm" className={classes.panel}>
      <Title order={4}>{t('admin.groups.members')}</Title>
      {state ? <ListState state={state} error={members.error} rows={3} title={t('admin.groups.noMembers')} /> : (
        <Table verticalSpacing={6}>
          <Table.Tbody>
            {rows.map((r) => (
              <Table.Tr key={r.userId}>
                <Table.Td>
                  <Text size="sm">{r.displayName}</Text>
                  <Text size="xs" className={classes.mono}>{r.login}</Text>
                </Table.Td>
                <Table.Td>
                  {r.lead && <Badge variant="light">{t('admin.groups.lead')}</Badge>}
                  {!r.active && <Badge variant="outline" color="red">{t('admin.users.states.blocked')}</Badge>}
                </Table.Td>
                <Table.Td w={48}>
                  {!managed && (
                    <ActionIcon variant="subtle" color="red" aria-label={t('admin.groups.removeMember', { name: r.displayName })}
                      onClick={() => m.removeMember.mutate({ groupId: group.id, userId: r.userId })}>
                      <IconTrash size={16} />
                    </ActionIcon>
                  )}
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
      {!managed && (
        <Group align="flex-end" gap="xs">
          <div style={{ flex: 1, minWidth: 220 }}>
            <PersonPicker label={t('admin.groups.addMember')} value={person} onChange={setPerson}
              exclude={rows.map((r) => r.userId)} />
          </div>
          <Checkbox label={t('admin.groups.lead')} checked={lead} onChange={(e) => setLead(e.currentTarget.checked)} mb={8} />
          <Button variant="default" disabled={!person} loading={m.addMember.isPending}
            onClick={() => person && m.addMember.mutate({ groupId: group.id, userId: person, lead }, {
              onSuccess: () => { setPerson(null); setLead(false); },
            })}>
            {t('admin.groups.add')}
          </Button>
        </Group>
      )}
      <ErrorLine error={m.addMember.error ?? m.removeMember.error} />
    </Stack>
  );
}

function toLocalInput(d: Date) {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function DutyName({ id }: { id: string }) {
  return <>{usePersonName(id)}</>;
}

function DutyPanel({ group }: { group: GroupRow }) {
  const { t, i18n } = useTranslation();
  const range = useMemo(() => {
    const from = new Date();
    from.setHours(0, 0, 0, 0);
    const to = new Date(from.getTime() + 14 * 86_400_000);
    return { from: from.toISOString(), to: to.toISOString() };
  }, []);
  const shifts = useDutyShifts(group.id, range.from, range.to);
  const members = useMembers(group.id);
  const m = useDutyMutations();
  const start = new Date();
  start.setHours(9, 0, 0, 0);
  const [form, setForm] = useState({ userId: null as string | null, startsAt: toLocalInput(start),
    endsAt: toLocalInput(new Date(start.getTime() + 9 * 3_600_000)), note: '' });
  const rows = shifts.data ?? [];

  return (
    <Stack gap="sm" className={classes.panel}>
      <Title order={4}>{t('admin.duty.title')}</Title>
      {listStateOf(shifts, rows.length) ? (
        <ListState state={listStateOf(shifts, rows.length)!} error={shifts.error} rows={2} title={t('admin.duty.empty')} />
      ) : (
        <Table verticalSpacing={6}>
          <Table.Tbody>
            {rows.map((s) => (
              <Table.Tr key={s.id}>
                <Table.Td><DutyName id={s.userId} /></Table.Td>
                <Table.Td>{formatTime(s.startsAt, i18n.language)} — {formatTime(s.endsAt, i18n.language)}</Table.Td>
                <Table.Td className={classes.muted}>{s.note}</Table.Td>
                <Table.Td w={48}>
                  <ActionIcon variant="subtle" color="red" aria-label={t('admin.duty.remove')} onClick={() => m.remove.mutate(s.id)}>
                    <IconTrash size={16} />
                  </ActionIcon>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
      <Group align="flex-end" gap="xs" wrap="wrap">
        <Select label={t('admin.duty.who')} w={220} value={form.userId} onChange={(v) => setForm({ ...form, userId: v })}
          data={(members.data ?? []).filter((x) => x.active).map((x) => ({ value: x.userId, label: x.displayName }))} />
        <TextInput label={t('admin.duty.from')} type="datetime-local" value={form.startsAt}
          onChange={(e) => setForm({ ...form, startsAt: e.currentTarget.value })} />
        <TextInput label={t('admin.duty.to')} type="datetime-local" value={form.endsAt}
          onChange={(e) => setForm({ ...form, endsAt: e.currentTarget.value })} />
        <TextInput label={t('admin.duty.note')} value={form.note} onChange={(e) => setForm({ ...form, note: e.currentTarget.value })} />
        <Button variant="default" disabled={!form.userId} loading={m.create.isPending}
          onClick={() => form.userId && m.create.mutate({
            groupId: group.id, userId: form.userId, note: form.note,
            startsAt: new Date(form.startsAt).toISOString(), endsAt: new Date(form.endsAt).toISOString(),
          })}>
          {t('admin.duty.assign')}
        </Button>
      </Group>
      <ErrorLine error={m.create.error ?? m.remove.error} />
    </Stack>
  );
}

// ---------------------------------------------------------------- графики

function SchedulesPanel() {
  const { t } = useTranslation();
  const schedules = useSchedules();
  const rows = schedules.data?.items ?? [];
  const [selected, setSelected] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const current = creating ? null : rows.find((s) => s.id === selected) ?? rows[0] ?? null;
  const state = listStateOf(schedules, rows.length);
  return (
    <SimpleGrid cols={{ base: 1, md: 3 }} spacing="md">
      <Stack gap="xs" className={classes.panel} style={{ alignSelf: 'start' }}>
        <Button leftSection={<IconPlus size={16} />} variant="default" onClick={() => setCreating(true)}>
          {t('admin.schedules.create')}
        </Button>
        {state ? <ListState state={state} error={schedules.error} rows={3} /> : rows.map((s) => (
          <button key={s.id} type="button" className={classes.listItem} aria-current={!creating && s.id === current?.id}
            onClick={() => { setCreating(false); setSelected(s.id); }}>
            <Text size="sm" fw={500}>{s.name}</Text>
            <Text size="xs" className={classes.muted}>{s.timezone}</Text>
          </button>
        ))}
      </Stack>
      <div style={{ gridColumn: 'span 2' }}>
        {(creating || current) && (
          <Stack gap="md">
            <ScheduleEditor key={current?.id ?? 'new'} schedule={current}
              onSaved={(s) => { setCreating(false); setSelected(s.id); }} />
            {current && <HolidaysEditor key={`h-${current.id}`} schedule={current} />}
          </Stack>
        )}
      </div>
    </SimpleGrid>
  );
}

const DEFAULT_WEEK: Partial<Record<Weekday, string[]>> = {
  MONDAY: ['09:00-18:00'], TUESDAY: ['09:00-18:00'], WEDNESDAY: ['09:00-18:00'], THURSDAY: ['09:00-18:00'],
  FRIDAY: ['09:00-18:00'], SATURDAY: [], SUNDAY: [],
};

function weekText(week: Partial<Record<Weekday, string[]>>) {
  return Object.fromEntries(WEEKDAYS.map((d) => [d, (week[d] ?? []).join(', ')])) as Record<Weekday, string>;
}

function ScheduleEditor({ schedule, onSaved }: { schedule: Schedule | null; onSaved: (s: Schedule) => void }) {
  const { t } = useTranslation();
  const save = useScheduleMutations().save;
  const initial = { name: schedule?.name ?? '', timezone: schedule?.timezone ?? 'Europe/Moscow',
    week: weekText(schedule?.week ?? DEFAULT_WEEK) };
  const [form, setForm] = useState(initial);
  const [history, setHistory] = useState(false);
  const dirty = !schedule || JSON.stringify(form) !== JSON.stringify(initial);

  function submit() {
    const week = Object.fromEntries(WEEKDAYS.map((d) => [d, form.week[d].split(',').map((s) => s.trim()).filter(Boolean)]));
    save.mutate({ id: schedule?.id, version: schedule?.version, name: form.name, timezone: form.timezone, week }, { onSuccess: onSaved });
  }

  return (
    <Stack gap="sm" className={classes.panel}>
      <Title order={3}>{schedule?.name ?? t('admin.schedules.create')}</Title>
      <SimpleGrid cols={{ base: 1, sm: 2 }}>
        <TextInput label={t('admin.schedules.name')} required value={form.name}
          onChange={(e) => setForm({ ...form, name: e.currentTarget.value })} />
        <Select label={t('admin.setup.org.timezone')} data={TIMEZONES} searchable value={form.timezone}
          onChange={(v) => v && setForm({ ...form, timezone: v })} />
      </SimpleGrid>
      <Text size="sm" className={classes.muted}>{t('admin.schedules.hoursHint')}</Text>
      <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="xs">
        {WEEKDAYS.map((d) => (
          <TextInput key={d} label={t(`admin.schedules.days.${d}`)} placeholder={t('admin.schedules.dayOff')}
            value={form.week[d]} onChange={(e) => setForm({ ...form, week: { ...form.week, [d]: e.currentTarget.value } })} />
        ))}
      </SimpleGrid>
      <SaveBar dirty={dirty && !!form.name.trim()} saving={save.isPending} error={save.error} onSave={submit}
        onCancel={() => setForm(initial)} onHistory={schedule ? () => setHistory(true) : undefined} />
      {schedule && (
        <HistoryDrawer opened={history} onClose={() => setHistory(false)} entity="sla.schedule" entityId={schedule.id}
          title={schedule.name} />
      )}
    </Stack>
  );
}

function HolidaysEditor({ schedule }: { schedule: Schedule }) {
  const { t } = useTranslation();
  const [year, setYear] = useState(new Date().getFullYear());
  const holidays = useHolidays(schedule.id, year);
  return (
    <Stack gap="sm" className={classes.panel}>
      <Group justify="space-between">
        <Title order={4}>{t('admin.schedules.holidays')}</Title>
        <NumberInput aria-label={t('admin.schedules.year')} value={year} min={2000} max={2100} w={110}
          onChange={(v) => typeof v === 'number' && setYear(v)} />
      </Group>
      {holidays.isPending ? <ListState state="loading" rows={2} /> : holidays.error ? (
        <ListState state="error" error={holidays.error} />
      ) : (
        <HolidayList key={`${year}-${holidays.dataUpdatedAt}`} schedule={schedule} year={year} saved={holidays.data ?? []} />
      )}
    </Stack>
  );
}

function HolidayList({ schedule, year, saved }: { schedule: Schedule; year: number; saved: Holiday[] }) {
  const { t } = useTranslation();
  const save = useScheduleMutations().holidays;
  const [items, setItems] = useState<Holiday[]>(saved);
  const [history, setHistory] = useState(false);
  const dirty = JSON.stringify(items) !== JSON.stringify(saved);
  const set = (i: number, h: Partial<Holiday>) => setItems(items.map((x, j) => (j === i ? { ...x, ...h } : x)));

  return (
    <Stack gap="sm">
      <Stack gap={6}>
        {items.map((h, i) => (
          <Group key={i} gap="xs" wrap="nowrap" align="flex-end">
            <TextInput type="date" aria-label={t('admin.schedules.date')} value={h.date}
              onChange={(e) => set(i, { date: e.currentTarget.value })} />
            <TextInput aria-label={t('admin.schedules.holidayName')} placeholder={t('admin.schedules.holidayName')}
              value={h.name} onChange={(e) => set(i, { name: e.currentTarget.value })} style={{ flex: 1 }} />
            <TextInput aria-label={t('admin.schedules.specialHours')} placeholder={t('admin.schedules.dayOff')}
              value={h.hours.join(', ')} w={160}
              onChange={(e) => set(i, { hours: e.currentTarget.value.split(',').map((s) => s.trim()).filter(Boolean) })} />
            <ActionIcon variant="subtle" color="red" aria-label={t('admin.schedules.removeHoliday')} mb={6}
              onClick={() => setItems(items.filter((_, j) => j !== i))}>
              <IconTrash size={16} />
            </ActionIcon>
          </Group>
        ))}
        {!items.length && <Text size="sm" className={classes.muted}>{t('admin.schedules.noHolidays')}</Text>}
        <Button variant="subtle" leftSection={<IconPlus size={16} />} w="fit-content"
          onClick={() => setItems([...items, { date: `${year}-01-01`, name: '', hours: [] }])}>
          {t('admin.schedules.addHoliday')}
        </Button>
      </Stack>
      <SaveBar dirty={dirty} saving={save.isPending} error={save.error} onCancel={() => setItems(saved)}
        onHistory={() => setHistory(true)} onSave={() => save.mutate({ schedule, year, items })} />
      <HistoryDrawer opened={history} onClose={() => setHistory(false)} entity="sla.holidays"
        entityId={`${schedule.id}:${year}`} title={`${schedule.name} · ${year}`}
        onRestore={async (snap) => {
          await save.mutateAsync({ schedule, year, items: (snap as { items: Holiday[] }).items });
        }} />
    </Stack>
  );
}
