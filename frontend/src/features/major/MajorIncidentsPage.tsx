import { Anchor, Badge, Button, Group, Modal, NumberInput, SegmentedControl, Select, Stack, Switch, Table, Tabs, Text, TextInput, Textarea, Title } from '@mantine/core';
import { IconPlus, IconTrash } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router';
import { useQueryClient } from '@tanstack/react-query';
import { useMutation } from '@tanstack/react-query';
import { hasRole, useMe } from '@/features/auth/api';
import { useDirectory } from '@/features/agent/api/hooks';
import { usePersonName } from '@/shared/api/people';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import { PersonPicker } from '@/shared/ui/PersonPicker';
import { durationText, majorApi, useChains, useMajorList, useTemplates, type Audience, type Chain, type MajorFilter, type Step, type Template } from './api';
import classes from './major.module.css';
import { MajorStateBadge, dateTimeOf } from './ui';

const VARS = ['number', 'title', 'services', 'status', 'hypothesis', 'nextUpdate', 'startedAt', 'duration'];

/** «Крупные инциденты» рабочего места: список и настройки (шаблоны сообщений, цепочки оповещения). */
export function MajorIncidentsPage() {
  const { t } = useTranslation();
  const me = useMe().data;
  const [params, setParams] = useSearchParams();
  const tab = params.get('tab') ?? 'list';
  const editor = hasRole(me, 'MIM', 'ADM');
  return (
    <Stack gap="md" maw={1200}>
      <div>
        <Title order={2}>{t('major.title')}</Title>
        <Text size="sm" c="dimmed">
          {t('major.subtitle')}
        </Text>
      </div>
      <Tabs value={tab} onChange={(v) => setParams(v && v !== 'list' ? { tab: v } : {}, { replace: true })} keepMounted={false}>
        <Tabs.List>
          <Tabs.Tab value="list">{t('major.tabs.list')}</Tabs.Tab>
          {editor && <Tabs.Tab value="templates">{t('major.tabs.templates')}</Tabs.Tab>}
          {editor && <Tabs.Tab value="chains">{t('major.tabs.chains')}</Tabs.Tab>}
        </Tabs.List>
        <Tabs.Panel value="list" pt="md">
          <MajorList />
        </Tabs.Panel>
        {editor && (
          <Tabs.Panel value="templates" pt="md">
            <TemplatesPanel />
          </Tabs.Panel>
        )}
        {editor && (
          <Tabs.Panel value="chains" pt="md">
            <ChainsPanel />
          </Tabs.Panel>
        )}
      </Tabs>
    </Stack>
  );
}

function MajorList() {
  const { t, i18n } = useTranslation();
  const [filter, setFilter] = useState<MajorFilter>('open');
  const list = useMajorList(filter);
  const rows = list.data ?? [];
  const ls = listStateOf(list, rows.length);
  const [now] = useState(() => Date.now());
  return (
    <Stack gap="sm">
      <SegmentedControl
        value={filter}
        onChange={(v) => setFilter(v as MajorFilter)}
        data={(['open', 'requested', 'closed', 'all'] as const).map((s) => ({ value: s, label: t(`major.filters.${s}`) }))}
        style={{ alignSelf: 'flex-start' }}
      />
      {ls ? (
        <ListState
          state={ls}
          title={ls === 'empty' ? t('major.empty') : undefined}
          error={list.error}
          onRetry={() => void list.refetch()}
          action={ls === 'empty' ? <Text size="sm" c="dimmed">{t('major.emptyHint')}</Text> : undefined}
        />
      ) : (
        <div style={{ overflowX: 'auto' }}>
          <Table highlightOnHover verticalSpacing="sm" miw={760}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('major.columns.incident')}</Table.Th>
                <Table.Th>{t('major.columns.state')}</Table.Th>
                <Table.Th>{t('major.columns.services')}</Table.Th>
                <Table.Th>{t('major.columns.manager')}</Table.Th>
                <Table.Th>{t('major.columns.declared')}</Table.Th>
                <Table.Th>{t('major.columns.duration')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((r) => (
                <Table.Tr key={r.id}>
                  <Table.Td>
                    <Anchor component={Link} to={`/agent/major/${r.id}`} fw={600} size="sm">
                      {r.publicTitle}
                    </Anchor>
                    <Text size="xs" c="dimmed">
                      <span className={classes.mono}>{r.ticket.number ?? '—'}</span>
                      {r.children > 0 ? ` · ${t('major.columns.children', { count: r.children })}` : ''}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <MajorStateBadge state={r.state} />
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm">{r.services.map((s) => s.name).join(', ') || '—'}</Text>
                  </Table.Td>
                  <Table.Td>
                    <Manager id={r.managerId} />
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm">{dateTimeOf(r.declaredAt, i18n.language)}</Text>
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm">{durationText(r.declaredAt, r.restoredAt, now, t)}</Text>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </div>
      )}
    </Stack>
  );
}

function Manager({ id }: { id: string | null }) {
  const name = usePersonName(id);
  return <Text size="sm">{id ? name : '—'}</Text>;
}

// ---------------------------------------------------------------- шаблоны сообщений (MIM-04)

function TemplatesPanel() {
  const { t } = useTranslation();
  const templates = useTemplates(true);
  const [edit, setEdit] = useState<Partial<Template> | null>(null);
  const ls = listStateOf(templates, templates.data?.length ?? 0);
  return (
    <Stack gap="sm">
      <Group justify="space-between">
        <Text size="sm" c="dimmed">
          {t('major.templates.hint')}
        </Text>
        <Button leftSection={<IconPlus size={16} />} onClick={() => setEdit({ audience: 'users', name: '', title: '', body: '', active: true })}>
          {t('major.templates.new')}
        </Button>
      </Group>
      {ls ? (
        <ListState state={ls} error={templates.error} onRetry={() => void templates.refetch()} />
      ) : (
        <Stack gap="xs">
          {templates.data!.map((x) => (
            <Group key={x.id} justify="space-between" className={classes.section} wrap="nowrap">
              <div style={{ minWidth: 0 }}>
                <Group gap={8}>
                  <Text fw={600} size="sm">
                    {x.name}
                  </Text>
                  <Badge variant="light" color="gray" size="sm">
                    {t(`major.audience.${x.audience}`)}
                  </Badge>
                  {!x.active && (
                    <Badge variant="outline" color="gray" size="sm">
                      {t('major.templates.inactive')}
                    </Badge>
                  )}
                </Group>
                <Text size="xs" c="dimmed" truncate>
                  {x.title}
                </Text>
              </div>
              <Button size="xs" variant="default" onClick={() => setEdit(x)}>
                {t('major.actions.edit')}
              </Button>
            </Group>
          ))}
        </Stack>
      )}
      {edit && <TemplateDialog key={edit.id ?? 'new'} value={edit} onClose={() => setEdit(null)} />}
    </Stack>
  );
}

function TemplateDialog({ value, onClose }: { value: Partial<Template>; onClose: () => void }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [v, setV] = useState(value);
  const save = useMutation({
    mutationFn: () => majorApi.saveTemplate({ ...v, name: v.name ?? '', audience: v.audience ?? 'users', title: v.title ?? '', body: v.body ?? '' }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['major', 'templates'] });
      onClose();
    },
  });
  return (
    <Modal opened onClose={onClose} title={t(value.id ? 'major.templates.edit' : 'major.templates.new')} size="lg">
      <Stack gap="sm">
        <TextInput label={t('major.templates.name')} value={v.name ?? ''} onChange={(e) => setV({ ...v, name: e.currentTarget.value })} required maxLength={255} />
        <SegmentedControl
          value={v.audience ?? 'users'}
          onChange={(a) => setV({ ...v, audience: a as Audience })}
          data={(['users', 'management'] as const).map((a) => ({ value: a, label: t(`major.audience.${a}`) }))}
        />
        <TextInput label={t('major.updates.subject')} value={v.title ?? ''} onChange={(e) => setV({ ...v, title: e.currentTarget.value })} required maxLength={255} />
        <Textarea label={t('major.updates.body')} value={v.body ?? ''} onChange={(e) => setV({ ...v, body: e.currentTarget.value })} autosize minRows={5} required />
        <Text size="xs" c="dimmed">
          {t('major.templates.vars')} {VARS.map((v) => `{{${v}}}`).join(', ')}
        </Text>
        <Switch label={t('major.templates.active')} checked={v.active ?? true} onChange={(e) => setV({ ...v, active: e.currentTarget.checked })} />
        {save.error && <ErrorLine error={save.error} />}
        <Group justify="flex-end" gap="xs">
          <Button variant="default" onClick={onClose}>
            {t('actions.cancel')}
          </Button>
          <Button onClick={() => save.mutate()} loading={save.isPending} disabled={!v.name?.trim() || !v.title?.trim() || !v.body?.trim()}>
            {t('major.actions.save')}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}

// ---------------------------------------------------------------- цепочки оповещения (MIM-03)

function ChainsPanel() {
  const { t } = useTranslation();
  const chains = useChains(true);
  const directory = useDirectory().data;
  const [edit, setEdit] = useState<Partial<Chain> | null>(null);
  const ls = listStateOf(chains, chains.data?.length ?? 0);
  const groupName = (id: string | null) => directory?.groups.find((g) => g.id === id)?.name ?? '—';
  return (
    <Stack gap="sm">
      <Group justify="space-between">
        <Text size="sm" c="dimmed">
          {t('major.chains.hint')}
        </Text>
        <Button leftSection={<IconPlus size={16} />} onClick={() => setEdit({ name: '', steps: [{ groupId: null, userId: null, waitMinutes: 10 }], active: true })}>
          {t('major.chains.new')}
        </Button>
      </Group>
      {ls ? (
        <ListState state={ls} title={ls === 'empty' ? t('major.chains.empty') : undefined} error={chains.error} onRetry={() => void chains.refetch()} />
      ) : (
        <Stack gap="xs">
          {chains.data!.map((c) => (
            <Group key={c.id} justify="space-between" className={classes.section} wrap="nowrap">
              <div style={{ minWidth: 0 }}>
                <Group gap={8}>
                  <Text fw={600} size="sm">
                    {c.name}
                  </Text>
                  {!c.active && (
                    <Badge variant="outline" color="gray" size="sm">
                      {t('major.templates.inactive')}
                    </Badge>
                  )}
                </Group>
                <Text size="xs" c="dimmed">
                  {c.steps.map((s, i) => `${i + 1}. ${s.groupId ? groupName(s.groupId) : t('major.chains.person')} (${s.waitMinutes} ${t('major.chains.min')})`).join(' → ')}
                </Text>
              </div>
              <Button size="xs" variant="default" onClick={() => setEdit(c)}>
                {t('major.actions.edit')}
              </Button>
            </Group>
          ))}
        </Stack>
      )}
      {edit && <ChainDialog key={edit.id ?? 'new'} value={edit} onClose={() => setEdit(null)} />}
    </Stack>
  );
}

function ChainDialog({ value, onClose }: { value: Partial<Chain>; onClose: () => void }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const directory = useDirectory().data;
  const [name, setName] = useState(value.name ?? '');
  const [active, setActive] = useState(value.active ?? true);
  const [steps, setSteps] = useState<Step[]>(value.steps ?? []);
  const save = useMutation({
    mutationFn: () => majorApi.saveChain({ id: value.id, version: value.version, name: name.trim(), steps, active }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['major', 'chains'] });
      onClose();
    },
  });
  const setStep = (i: number, s: Partial<Step>) => setSteps(steps.map((x, j) => (j === i ? { ...x, ...s } : x)));
  const valid = name.trim() && steps.length > 0 && steps.every((s) => (s.groupId ? !s.userId : !!s.userId));
  return (
    <Modal opened onClose={onClose} title={t(value.id ? 'major.chains.edit' : 'major.chains.new')} size="lg">
      <Stack gap="sm">
        <TextInput label={t('major.templates.name')} value={name} onChange={(e) => setName(e.currentTarget.value)} required maxLength={255} />
        {steps.map((s, i) => (
          <Stack key={i} gap={6} p="xs" style={{ border: '1px solid var(--ei-border)', borderRadius: 8 }}>
            <Group justify="space-between">
              <Text size="sm" fw={600}>
                {t('major.chains.stepN', { n: i + 1 })}
              </Text>
              <Button size="compact-xs" variant="subtle" color="gray" leftSection={<IconTrash size={12} />} onClick={() => setSteps(steps.filter((_, j) => j !== i))}>
                {t('major.chains.remove')}
              </Button>
            </Group>
            <Select
              label={t('major.paging.group')}
              data={(directory?.groups ?? []).map((g) => ({ value: g.id, label: g.name }))}
              value={s.groupId}
              onChange={(g) => setStep(i, { groupId: g, userId: g ? null : s.userId })}
              searchable
              clearable
            />
            {!s.groupId && <PersonPicker label={t('major.chains.person')} value={s.userId} onChange={(u) => setStep(i, { userId: u })} />}
            <NumberInput
              label={t('major.chains.wait')}
              min={1}
              max={240}
              value={s.waitMinutes}
              onChange={(n) => setStep(i, { waitMinutes: typeof n === 'number' ? n : 10 })}
            />
          </Stack>
        ))}
        {steps.length < 10 && (
          <Button variant="default" size="xs" leftSection={<IconPlus size={14} />} onClick={() => setSteps([...steps, { groupId: null, userId: null, waitMinutes: 10 }])} style={{ alignSelf: 'flex-start' }}>
            {t('major.chains.addStep')}
          </Button>
        )}
        <Text size="xs" c="dimmed">
          {t('major.chains.whoHint')}
        </Text>
        <Switch label={t('major.templates.active')} checked={active} onChange={(e) => setActive(e.currentTarget.checked)} />
        {save.error && <ErrorLine error={save.error} />}
        <Group justify="flex-end" gap="xs">
          <Button variant="default" onClick={onClose}>
            {t('actions.cancel')}
          </Button>
          <Button onClick={() => save.mutate()} loading={save.isPending} disabled={!valid}>
            {t('major.actions.save')}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
