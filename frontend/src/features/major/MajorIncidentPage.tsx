import {
  Anchor,
  Button,
  Checkbox,
  Group,
  Modal,
  MultiSelect,
  SegmentedControl,
  Select,
  Skeleton,
  Stack,
  Switch,
  Text,
  TextInput,
  Textarea,
  Title,
} from '@mantine/core';
import { useInterval } from '@mantine/hooks';
import { IconArrowLeft, IconBellRinging, IconCheck, IconFlame, IconPlus, IconSend } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { useMe } from '@/features/auth/api';
import { useDirectory } from '@/features/agent/api/hooks';
import { ApiError, api } from '@/shared/api/client';
import { usePersonName } from '@/shared/api/people';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { ListState } from '@/shared/ui/ListState';
import { PersonPicker } from '@/shared/ui/PersonPicker';
import {
  ROLES,
  durationText,
  majorApi,
  roleHolder,
  useChains,
  useMajor,
  useMajorAction,
  useTemplates,
  type Audience,
  type CmdbService,
  type MajorIncident,
  type MajorRole,
  type Page,
} from './api';
import classes from './major.module.css';
import { MajorStateBadge, TicketLink, dateTimeOf, fromLocalInput, timeOf, toLocalInput } from './ui';

/** Командный центр крупного инцидента (MIM-02…05, экран 10.9). */
export function MajorIncidentPage() {
  const { id = '' } = useParams();
  const { t } = useTranslation();
  const major = useMajor(id);
  if (major.isPending) {
    return (
      <Stack gap="md" aria-busy="true" aria-label={t('states.loading')}>
        <Skeleton height={20} width={160} />
        <Skeleton height={110} />
        <Skeleton height={320} />
      </Stack>
    );
  }
  if (major.error) {
    const notFound = major.error instanceof ApiError && major.error.status === 404;
    return (
      <ListState
        state={notFound ? 'empty' : 'error'}
        title={notFound ? t('major.card.notFound') : undefined}
        error={major.error}
        onRetry={() => void major.refetch()}
        action={
          <Button component={Link} to="/agent/major" variant="subtle">
            {t('major.card.back')}
          </Button>
        }
      />
    );
  }
  return <CommandCenter m={major.data} />;
}

function CommandCenter({ m }: { m: MajorIncident }) {
  const { t } = useTranslation();
  const manage = m.permissions.manage;
  return (
    <Stack gap="md" maw={1280}>
      <Anchor component={Link} to="/agent/major" size="sm" c="dimmed">
        <Group gap={4} wrap="nowrap">
          <IconArrowLeft size={14} />
          {t('major.card.back')}
        </Group>
      </Anchor>
      <Hero m={m} />
      <div className={classes.grid}>
        <Stack gap="md" style={{ minWidth: 0 }}>
          <Hypothesis key={m.hypothesis ?? ''} m={m} />
          {(manage || m.updates.length > 0) && <Updates m={m} />}
          <Timeline m={m} />
          {(m.state === 'restored' || m.state === 'closed') && <Pir key={m.pir ?? ''} m={m} />}
        </Stack>
        <Stack gap="md" style={{ minWidth: 0 }}>
          <Roles m={m} />
          <Services key={m.services.map((s) => s.id).join()} m={m} />
          <Tasks m={m} />
          <Paging m={m} />
          <Children m={m} />
        </Stack>
      </div>
    </Stack>
  );
}

// ---------------------------------------------------------------- шапка

function Hero({ m }: { m: MajorIncident }) {
  const { t, i18n } = useTranslation();
  const [now, setNow] = useState(() => Date.now());
  useInterval(() => setNow(Date.now()), 30_000, { autoInvoke: true });
  const [dialog, setDialog] = useState<'reject' | 'restore' | 'close' | null>(null);
  const approve = useMajorAction(() => majorApi.approve(m.id));
  const manager = usePersonName(roleHolder(m, 'manager'));
  const overdue = m.nextUpdateAt && Date.parse(m.nextUpdateAt) < now && m.state === 'active';
  return (
    <div className={classes.hero} data-state={m.state}>
      <Group justify="space-between" align="flex-start" wrap="wrap" gap="md">
        <Stack gap={6} style={{ flex: '1 1 420px', minWidth: 0 }}>
          <Group gap={8} wrap="wrap">
            <MajorStateBadge state={m.state} />
            <TicketLink ticket={m.ticket} />
          </Group>
          <Title order={2} fz={{ base: 'lg', sm: 'xl' }} style={{ overflowWrap: 'anywhere' }}>
            {m.publicTitle}
          </Title>
          <Text size="sm" c="dimmed" className={classes.pre}>
            {m.reason}
          </Text>
          <Text size="xs" c="dimmed">
            {t('major.card.meta', { at: dateTimeOf(m.declaredAt, i18n.language), manager: manager || '—' })}
          </Text>
        </Stack>
        <Stack gap={4} align="flex-end">
          <Text size="xs" c="dimmed">
            {t(m.restoredAt ? 'major.card.downtime' : 'major.card.elapsed')}
          </Text>
          <div className={classes.clock}>{durationText(m.declaredAt, m.restoredAt, now, t)}</div>
          {m.nextUpdateAt && m.state !== 'closed' && (
            <Text size="xs" c={overdue ? 'var(--ei-danger)' : 'dimmed'} fw={overdue ? 600 : undefined}>
              {t(overdue ? 'major.card.updateOverdue' : 'major.card.nextUpdate', { time: timeOf(m.nextUpdateAt, i18n.language) })}
            </Text>
          )}
        </Stack>
      </Group>
      <Group gap="xs" mt="md" wrap="wrap">
        {m.permissions.approve && (
          <>
            <Button color="red" leftSection={<IconFlame size={16} />} onClick={() => approve.mutate(undefined)} loading={approve.isPending}>
              {t('major.actions.approve')}
            </Button>
            <Button variant="default" onClick={() => setDialog('reject')}>
              {t('major.actions.reject')}
            </Button>
          </>
        )}
        {m.permissions.manage && m.state === 'active' && (
          <Button color="green" leftSection={<IconCheck size={16} />} onClick={() => setDialog('restore')}>
            {t('major.actions.restore')}
          </Button>
        )}
        {m.permissions.close && (
          <Button variant="default" onClick={() => setDialog('close')}>
            {t('major.actions.close')}
          </Button>
        )}
        <AckButton m={m} />
        {m.problem?.number && (
          <Button variant="subtle" component={Link} to={`/agent/problems/${m.problem.number}`}>
            {t('major.card.problem', { number: m.problem.number })}
          </Button>
        )}
      </Group>
      {approve.error && <ErrorLine error={approve.error} />}
      <ReasonDialog m={m} kind={dialog === 'reject' || dialog === 'restore' ? dialog : null} onClose={() => setDialog(null)} />
      <CloseDialog m={m} opened={dialog === 'close'} onClose={() => setDialog(null)} />
    </div>
  );
}

/** «Подтвердить получение» для того, кого оповестили и кто ещё не подтвердил. */
function AckButton({ m }: { m: MajorIncident }) {
  const { t } = useTranslation();
  const me = useMe().data;
  const page = m.pages.find((p) => p.state !== 'cancelled' && p.targets.some((x) => x.userId === me?.id && !x.ackedAt));
  const ack = useMajorAction((pageId: string) => majorApi.ack(pageId));
  if (!page) return null;
  return (
    <Button color="red" variant="light" leftSection={<IconBellRinging size={16} />} onClick={() => ack.mutate(page.id)} loading={ack.isPending}>
      {t('major.paging.ack')}
    </Button>
  );
}

function ReasonDialog({ m, kind, onClose }: { m: MajorIncident; kind: 'reject' | 'restore' | null; onClose: () => void }) {
  const { t } = useTranslation();
  const [text, setText] = useState('');
  const action = useMajorAction((v: string) => (kind === 'reject' ? majorApi.reject(m.id, v) : majorApi.restore(m.id, v)));
  return (
    <Modal opened={kind !== null} onClose={onClose} title={kind ? t(`major.${kind}.title`) : ''}>
      <Stack gap="sm">
        <Textarea
          label={kind ? t(`major.${kind}.label`) : ''}
          value={text}
          onChange={(e) => setText(e.currentTarget.value)}
          autosize
          minRows={3}
          required={kind === 'reject'}
          data-autofocus
        />
        {action.error && <ErrorLine error={action.error} />}
        <Group justify="flex-end" gap="xs">
          <Button variant="default" onClick={onClose}>
            {t('actions.cancel')}
          </Button>
          <Button
            color={kind === 'restore' ? 'green' : undefined}
            loading={action.isPending}
            disabled={kind === 'reject' && !text.trim()}
            onClick={() => action.mutate(text.trim(), { onSuccess: () => { setText(''); onClose(); } })}
          >
            {kind ? t(`major.${kind}.submit`) : ''}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}

function CloseDialog({ m, opened, onClose }: { m: MajorIncident; opened: boolean; onClose: () => void }) {
  const { t } = useTranslation();
  const [createProblem, setCreateProblem] = useState(m.problem === null);
  const [problemTitle, setProblemTitle] = useState('');
  const close = useMajorAction(() => majorApi.close(m.id, createProblem, problemTitle.trim()));
  return (
    <Modal opened={opened} onClose={onClose} title={t('major.close.title')}>
      <Stack gap="sm">
        <Text size="sm">{t('major.close.hint')}</Text>
        {m.problem === null && (
          <Switch label={t('major.close.createProblem')} checked={createProblem} onChange={(e) => setCreateProblem(e.currentTarget.checked)} />
        )}
        {createProblem && m.problem === null && (
          <TextInput
            label={t('major.close.problemTitle')}
            placeholder={t('major.close.problemTitlePlaceholder', { title: m.publicTitle })}
            value={problemTitle}
            onChange={(e) => setProblemTitle(e.currentTarget.value)}
            maxLength={255}
          />
        )}
        {close.error && <ErrorLine error={close.error} />}
        <Group justify="flex-end" gap="xs">
          <Button variant="default" onClick={onClose}>
            {t('actions.cancel')}
          </Button>
          <Button loading={close.isPending} onClick={() => close.mutate(undefined, { onSuccess: onClose })}>
            {t('major.close.submit')}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}

// ---------------------------------------------------------------- гипотеза, сообщения, хронология, разбор

function Section({ title, children, extra }: { title: string; children: React.ReactNode; extra?: React.ReactNode }) {
  return (
    <section className={classes.section} aria-label={title}>
      <Group justify="space-between" align="flex-start" mb="sm" wrap="nowrap">
        <div className={classes.label}>{title}</div>
        {extra}
      </Group>
      {children}
    </section>
  );
}

function Hypothesis({ m }: { m: MajorIncident }) {
  const { t } = useTranslation();
  const [value, setValue] = useState(m.hypothesis ?? '');
  const save = useMajorAction((v: string) => majorApi.patch(m, { hypothesis: v }));
  const dirty = value.trim() !== (m.hypothesis ?? '');
  if (!m.permissions.manage) {
    return (
      <Section title={t('major.hypothesis.title')}>
        <Text size="sm" className={classes.pre} c={m.hypothesis ? undefined : 'dimmed'}>
          {m.hypothesis || t('major.hypothesis.none')}
        </Text>
      </Section>
    );
  }
  return (
    <Section title={t('major.hypothesis.title')}>
      <Stack gap="xs">
        <Textarea
          aria-label={t('major.hypothesis.title')}
          placeholder={t('major.hypothesis.placeholder')}
          value={value}
          onChange={(e) => setValue(e.currentTarget.value)}
          autosize
          minRows={2}
          maxLength={5000}
        />
        {save.error && <ErrorLine error={save.error} />}
        {dirty && (
          <Group justify="flex-end">
            <Button size="xs" onClick={() => save.mutate(value.trim())} loading={save.isPending}>
              {t('major.actions.save')}
            </Button>
          </Group>
        )}
      </Stack>
    </Section>
  );
}

function Updates({ m }: { m: MajorIncident }) {
  const { t, i18n } = useTranslation();
  const [open, setOpen] = useState(false);
  return (
    <Section
      title={t('major.updates.title')}
      extra={
        m.permissions.manage &&
        m.state !== 'requested' && (
          <Button size="xs" variant="default" leftSection={<IconSend size={14} />} onClick={() => setOpen((v) => !v)}>
            {t('major.updates.new')}
          </Button>
        )
      }
    >
      <Stack gap="sm">
        {open && <PublishForm m={m} onDone={() => setOpen(false)} />}
        {m.updates.length === 0 && !open && (
          <Text size="sm" c="dimmed">
            {t('major.updates.none')}
          </Text>
        )}
        {m.updates.map((u) => (
          <div key={u.id} className={classes.update} data-audience={u.audience}>
            <Group justify="space-between" gap="xs" wrap="wrap">
              <Text size="sm" fw={600}>
                {u.title}
              </Text>
              <Text size="xs" c="dimmed">
                {t(`major.audience.${u.audience}`)} · {dateTimeOf(u.publishedAt, i18n.language)}
                {u.statusPage ? ` · ${t('major.updates.onStatusPage')}` : ''}
              </Text>
            </Group>
            <Text size="sm" mt={4} className={classes.pre}>
              {u.body}
            </Text>
          </div>
        ))}
      </Stack>
    </Section>
  );
}

function PublishForm({ m, onDone }: { m: MajorIncident; onDone: () => void }) {
  const { t } = useTranslation();
  const templates = useTemplates();
  const [audience, setAudience] = useState<Audience>('users');
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [statusPage, setStatusPage] = useState(true);
  const [next, setNext] = useState(() => toLocalInput(new Date(Date.now() + 30 * 60_000).toISOString()));
  const [templateId, setTemplateId] = useState<string | null>(null);
  const [renderError, setRenderError] = useState<unknown>(null);
  const publish = useMajorAction(() =>
    majorApi.publish(m.id, { audience, title: title.trim(), body: body.trim(), statusPage: audience === 'users' && statusPage, nextUpdateAt: fromLocalInput(next) }),
  );
  const applyTemplate = async (id: string | null) => {
    setTemplateId(id);
    setRenderError(null);
    if (!id) return;
    try {
      const r = await majorApi.render(m.id, id);
      setAudience(r.audience);
      setTitle(r.title);
      setBody(r.body);
    } catch (e) {
      setRenderError(e);
    }
  };
  return (
    <Stack gap="xs" p="sm" style={{ border: '1px solid var(--ei-border)', borderRadius: 8 }}>
      <Group gap="sm" wrap="wrap" align="flex-end">
        <Select
          label={t('major.updates.template')}
          placeholder={t('major.updates.templatePlaceholder')}
          data={(templates.data ?? []).map((x) => ({ value: x.id, label: x.name }))}
          value={templateId}
          onChange={(v) => void applyTemplate(v)}
          clearable
          style={{ flex: '1 1 240px' }}
        />
        <SegmentedControl
          value={audience}
          onChange={(v) => setAudience(v as Audience)}
          data={(['users', 'management'] as const).map((a) => ({ value: a, label: t(`major.audience.${a}`) }))}
        />
      </Group>
      {renderError != null && <ErrorLine error={renderError} />}
      <TextInput label={t('major.updates.subject')} value={title} onChange={(e) => setTitle(e.currentTarget.value)} maxLength={255} required />
      <Textarea label={t('major.updates.body')} value={body} onChange={(e) => setBody(e.currentTarget.value)} autosize minRows={4} required />
      <Group gap="md" wrap="wrap" align="flex-end">
        <TextInput type="datetime-local" label={t('major.updates.next')} value={next} onChange={(e) => setNext(e.currentTarget.value)} />
        {audience === 'users' && (
          <Switch label={t('major.updates.statusPage')} checked={statusPage} onChange={(e) => setStatusPage(e.currentTarget.checked)} mb={6} />
        )}
      </Group>
      <Text size="xs" c="dimmed">
        {t(audience === 'users' ? 'major.updates.usersHint' : 'major.updates.managementHint')}
      </Text>
      {publish.error && <ErrorLine error={publish.error} />}
      <Group justify="flex-end" gap="xs">
        <Button variant="default" size="xs" onClick={onDone}>
          {t('actions.cancel')}
        </Button>
        <Button
          size="xs"
          leftSection={<IconSend size={14} />}
          disabled={!title.trim() || !body.trim()}
          loading={publish.isPending}
          onClick={() => publish.mutate(undefined, { onSuccess: onDone })}
        >
          {t('major.updates.publish')}
        </Button>
      </Group>
    </Stack>
  );
}

function Timeline({ m }: { m: MajorIncident }) {
  const { t, i18n } = useTranslation();
  const [text, setText] = useState('');
  const add = useMajorAction((v: string) => majorApi.event(m.id, v));
  return (
    <Section title={t('major.timeline.title')}>
      {m.permissions.manage && (
        <Group gap="xs" mb="sm" wrap="nowrap" align="flex-start">
          <TextInput
            aria-label={t('major.timeline.add')}
            placeholder={t('major.timeline.placeholder')}
            value={text}
            onChange={(e) => setText(e.currentTarget.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && text.trim()) add.mutate(text.trim(), { onSuccess: () => setText('') });
            }}
            style={{ flex: 1 }}
          />
          <Button variant="default" onClick={() => add.mutate(text.trim(), { onSuccess: () => setText('') })} disabled={!text.trim()} loading={add.isPending}>
            {t('major.timeline.add')}
          </Button>
        </Group>
      )}
      {add.error && <ErrorLine error={add.error} />}
      <div className={classes.timeline}>
        {[...m.timeline].reverse().map((e) => (
          <div key={e.id} className={classes.event} data-kind={e.kind}>
            <div className={classes.eventTime} title={dateTimeOf(e.at, i18n.language)}>
              {timeOf(e.at, i18n.language)}
            </div>
            <div className={classes.eventDot} aria-hidden="true" />
            <EventText text={e.text} actorId={e.actorId} />
          </div>
        ))}
      </div>
    </Section>
  );
}

function EventText({ text, actorId }: { text: string; actorId: string | null }) {
  const name = usePersonName(actorId);
  return (
    <div style={{ minWidth: 0 }}>
      <Text size="sm" className={classes.pre}>
        {text}
      </Text>
      {actorId && (
        <Text size="xs" c="dimmed">
          {name}
        </Text>
      )}
    </div>
  );
}

function Pir({ m }: { m: MajorIncident }) {
  const { t } = useTranslation();
  const [value, setValue] = useState(m.pir ?? '');
  const [draftError, setDraftError] = useState<unknown>(null);
  const save = useMajorAction((v: string) => majorApi.savePir(m, v));
  const loadDraft = async () => {
    setDraftError(null);
    try {
      setValue((await majorApi.pirDraft(m.id)).pir);
    } catch (e) {
      setDraftError(e);
    }
  };
  return (
    <Section
      title={t('major.pir.title')}
      extra={
        m.permissions.manage || m.permissions.close ? (
          <Button size="xs" variant="default" onClick={() => void loadDraft()}>
            {t('major.pir.draft')}
          </Button>
        ) : undefined
      }
    >
      <Stack gap="xs">
        <Text size="xs" c="dimmed">
          {t('major.pir.hint')}
        </Text>
        <Textarea
          aria-label={t('major.pir.title')}
          value={value}
          onChange={(e) => setValue(e.currentTarget.value)}
          autosize
          minRows={8}
          maxRows={30}
          readOnly={!(m.permissions.manage || m.permissions.close)}
          styles={{ input: { fontFamily: 'var(--mantine-font-family-monospace)', fontSize: 13 } }}
        />
        {draftError != null && <ErrorLine error={draftError} />}
        {save.error && <ErrorLine error={save.error} />}
        {value !== (m.pir ?? '') && (
          <Group justify="flex-end">
            <Button size="xs" onClick={() => save.mutate(value)} loading={save.isPending}>
              {t('major.actions.save')}
            </Button>
          </Group>
        )}
      </Stack>
    </Section>
  );
}

// ---------------------------------------------------------------- правая колонка

function Roles({ m }: { m: MajorIncident }) {
  const { t } = useTranslation();
  const assign = useMajorAction((v: { role: MajorRole; userId: string | null }) => majorApi.role(m.id, v.role, v.userId));
  return (
    <Section title={t('major.roles.title')}>
      <Stack gap="xs">
        {ROLES.map((role) =>
          m.permissions.manage ? (
            <PersonPicker
              key={role}
              label={t(`major.roles.${role}`)}
              value={roleHolder(m, role)}
              onChange={(userId) => {
                if (userId !== roleHolder(m, role) && (userId || role !== 'manager')) assign.mutate({ role, userId });
              }}
            />
          ) : (
            <RoleLine key={role} role={role} userId={roleHolder(m, role)} />
          ),
        )}
        {assign.error && <ErrorLine error={assign.error} />}
      </Stack>
    </Section>
  );
}

function RoleLine({ role, userId }: { role: MajorRole; userId: string | null }) {
  const { t } = useTranslation();
  const name = usePersonName(userId);
  return (
    <Group justify="space-between" gap="xs" wrap="nowrap">
      <Text size="sm" c="dimmed">
        {t(`major.roles.${role}`)}
      </Text>
      <Text size="sm" fw={500} truncate>
        {userId ? name : '—'}
      </Text>
    </Group>
  );
}

function Services({ m }: { m: MajorIncident }) {
  const { t } = useTranslation();
  const directory = useDirectory().data;
  const [value, setValue] = useState(m.services.map((s) => s.id));
  const save = useMajorAction((ids: string[]) => majorApi.patch(m, { serviceIds: ids }));
  const known = new Map([...(directory?.services ?? []).map((s) => [s.id, s.name] as const), ...m.services.map((s) => [s.id, s.name] as const)]);
  const dirty = [...value].sort().join() !== m.services.map((s) => s.id).sort().join();
  return (
    <Section title={t('major.fields.services')}>
      {m.permissions.manage ? (
        <Stack gap="xs">
          <MultiSelect
            aria-label={t('major.fields.services')}
            data={[...known].map(([id, name]) => ({ value: id, label: name }))}
            value={value}
            onChange={setValue}
            searchable
            placeholder={t('major.fields.servicesPlaceholder')}
          />
          {save.error && <ErrorLine error={save.error} />}
          {dirty && (
            <Group justify="flex-end">
              <Button size="xs" onClick={() => save.mutate(value)} loading={save.isPending}>
                {t('major.actions.save')}
              </Button>
            </Group>
          )}
        </Stack>
      ) : (
        <Text size="sm">{m.services.map((s) => s.name).join(', ') || '—'}</Text>
      )}
      <CmdbServices items={m.cmdbServices ?? []} />
    </Section>
  );
}

/** Подсказка из CMDB: услуги, зависящие от КЕ основной и дочерних заявок. */
function CmdbServices({ items }: { items: CmdbService[] }) {
  const { t } = useTranslation();
  if (items.length === 0) return null;
  return (
    <Stack gap={4} mt="sm">
      <Text size="xs" c="dimmed">
        {t('major.fields.cmdbServices')}
      </Text>
      {items.map((s) => (
        <Group key={s.id} gap={6} wrap="nowrap">
          <Anchor component={Link} to={`/agent/cmdb/${s.id}`} size="sm" truncate>
            {s.name}
          </Anchor>
          <Text size="xs" c="dimmed">
            {s.depth === 0 ? t('major.fields.cmdbDirect') : t('major.fields.cmdbDepth', { count: s.depth })}
          </Text>
        </Group>
      ))}
    </Stack>
  );
}

function Tasks({ m }: { m: MajorIncident }) {
  const { t } = useTranslation();
  const me = useMe().data;
  const [title, setTitle] = useState('');
  const [assignee, setAssignee] = useState<string | null>(null);
  const add = useMajorAction(() => majorApi.task(m.id, title.trim(), assignee));
  const toggle = useMajorAction((v: { id: string; done: boolean }) => majorApi.taskDone(m.id, v.id, v.done));
  return (
    <Section title={t('major.tasks.title')}>
      <Stack gap={6}>
        {m.tasks.length === 0 && (
          <Text size="sm" c="dimmed">
            {t('major.tasks.none')}
          </Text>
        )}
        {m.tasks.map((task) => (
          <Group key={task.id} gap="xs" wrap="nowrap" align="flex-start">
            <Checkbox
              checked={task.done}
              disabled={!(m.permissions.manage || task.assigneeId === me?.id)}
              onChange={(e) => toggle.mutate({ id: task.id, done: e.currentTarget.checked })}
              aria-label={task.title}
              mt={2}
            />
            <div style={{ minWidth: 0 }}>
              <Text size="sm" td={task.done ? 'line-through' : undefined} c={task.done ? 'dimmed' : undefined}>
                {task.title}
              </Text>
              {task.assigneeId && <Assignee id={task.assigneeId} />}
            </div>
          </Group>
        ))}
        {toggle.error && <ErrorLine error={toggle.error} />}
        {m.permissions.manage && (
          <Stack gap={6} mt="xs">
            <TextInput placeholder={t('major.tasks.placeholder')} aria-label={t('major.tasks.add')} value={title} onChange={(e) => setTitle(e.currentTarget.value)} maxLength={500} />
            <PersonPicker label={t('major.tasks.assignee')} value={assignee} onChange={setAssignee} />
            {add.error && <ErrorLine error={add.error} />}
            <Button
              size="xs"
              variant="default"
              leftSection={<IconPlus size={14} />}
              disabled={!title.trim()}
              loading={add.isPending}
              onClick={() => add.mutate(undefined, { onSuccess: () => { setTitle(''); setAssignee(null); } })}
            >
              {t('major.tasks.add')}
            </Button>
          </Stack>
        )}
      </Stack>
    </Section>
  );
}

function Assignee({ id }: { id: string }) {
  const name = usePersonName(id);
  return (
    <Text size="xs" c="dimmed">
      {name}
    </Text>
  );
}

function Paging({ m }: { m: MajorIncident }) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const canStart = m.permissions.manage && (m.state === 'active' || m.state === 'restored');
  return (
    <Section
      title={t('major.paging.title')}
      extra={
        canStart && (
          <Button size="xs" variant="default" leftSection={<IconBellRinging size={14} />} onClick={() => setOpen(true)}>
            {t('major.paging.start')}
          </Button>
        )
      }
    >
      <Stack gap="sm">
        {m.pages.length === 0 && (
          <Text size="sm" c="dimmed">
            {t('major.paging.none')}
          </Text>
        )}
        {m.pages.map((p) => (
          <PageCard key={p.id} m={m} page={p} />
        ))}
      </Stack>
      <StartPageDialog m={m} opened={open} onClose={() => setOpen(false)} />
    </Section>
  );
}

function PageCard({ m, page }: { m: MajorIncident; page: Page }) {
  const { t, i18n } = useTranslation();
  const cancel = useMajorAction(() => majorApi.cancelPage(m.id, page.id));
  return (
    <Stack gap={4} p="xs" style={{ border: '1px solid var(--ei-border)', borderRadius: 8 }}>
      <Group justify="space-between" gap="xs" wrap="nowrap">
        <Text size="sm" fw={600} truncate>
          {page.name}
        </Text>
        <Text size="xs" c={page.state === 'acknowledged' ? 'var(--ei-ok)' : page.state === 'exhausted' ? 'var(--ei-danger)' : 'dimmed'} fw={600}>
          {t(`major.paging.state.${page.state}`)}
        </Text>
      </Group>
      <Text size="xs" c="dimmed">
        {t('major.paging.step', { step: page.step + 1, steps: page.steps })} · {t(`major.paging.channel.${page.channel}`)}
        {page.state === 'paging' && page.nextStepAt ? ` · ${t('major.paging.nextAt', { time: timeOf(page.nextStepAt, i18n.language) })}` : ''}
      </Text>
      {page.targets.map((x) => (
        <TargetLine key={`${x.step}-${x.userId}`} userId={x.userId} step={x.step} acked={x.ackedAt} />
      ))}
      {page.state === 'paging' && m.permissions.manage && (
        <Button size="compact-xs" variant="subtle" color="gray" onClick={() => cancel.mutate(undefined)} loading={cancel.isPending} style={{ alignSelf: 'flex-start' }}>
          {t('major.paging.cancel')}
        </Button>
      )}
    </Stack>
  );
}

function TargetLine({ userId, step, acked }: { userId: string; step: number; acked: string | null }) {
  const { t, i18n } = useTranslation();
  const name = usePersonName(userId);
  return (
    <div className={classes.target}>
      <Text size="xs" truncate>
        {step + 1}. {name}
      </Text>
      <Text size="xs" c={acked ? 'var(--ei-ok)' : 'dimmed'}>
        {acked ? t('major.paging.acked', { time: timeOf(acked, i18n.language) }) : t('major.paging.waiting')}
      </Text>
    </div>
  );
}

function StartPageDialog({ m, opened, onClose }: { m: MajorIncident; opened: boolean; onClose: () => void }) {
  const { t } = useTranslation();
  const chains = useChains(false, opened);
  const directory = useDirectory().data;
  const [chainId, setChainId] = useState<string | null>(null);
  const [groupId, setGroupId] = useState<string | null>(null);
  const [channel, setChannel] = useState<'center' | 'email'>('center');
  const start = useMajorAction(() =>
    majorApi.page(m.id, chainId ? { chainId, channel } : { steps: [{ groupId, userId: null, waitMinutes: 10 }], channel }),
  );
  return (
    <Modal opened={opened} onClose={onClose} title={t('major.paging.start')}>
      <Stack gap="sm">
        <Select
          label={t('major.paging.chain')}
          placeholder={t('major.paging.chainPlaceholder')}
          data={(chains.data ?? []).map((c) => ({ value: c.id, label: c.name }))}
          value={chainId}
          onChange={setChainId}
          clearable
        />
        {!chainId && (
          <Select
            label={t('major.paging.group')}
            description={t('major.paging.groupHint')}
            data={(directory?.groups ?? []).map((g) => ({ value: g.id, label: g.name }))}
            value={groupId}
            onChange={setGroupId}
            searchable
          />
        )}
        <SegmentedControl
          value={channel}
          onChange={(v) => setChannel(v as 'center' | 'email')}
          data={(['center', 'email'] as const).map((c) => ({ value: c, label: t(`major.paging.channel.${c}`) }))}
        />
        <Text size="xs" c="dimmed">
          {t('major.paging.hint')}
        </Text>
        {start.error && <ErrorLine error={start.error} />}
        <Group justify="flex-end" gap="xs">
          <Button variant="default" onClick={onClose}>
            {t('actions.cancel')}
          </Button>
          <Button
            color="red"
            leftSection={<IconBellRinging size={16} />}
            disabled={!chainId && !groupId}
            loading={start.isPending}
            onClick={() => start.mutate(undefined, { onSuccess: onClose })}
          >
            {t('major.paging.send')}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}

function Children({ m }: { m: MajorIncident }) {
  const { t } = useTranslation();
  const [number, setNumber] = useState('');
  const [error, setError] = useState<unknown>(null);
  const link = useMajorAction((ids: string[]) => majorApi.children(m.id, ids));
  const submit = async () => {
    setError(null);
    try {
      const ticket = await api.get<{ id: string }>(`/tickets/${encodeURIComponent(number.trim().toUpperCase())}`);
      link.mutate([ticket.id], { onSuccess: () => setNumber('') });
    } catch (e) {
      setError(e);
    }
  };
  return (
    <Section title={t('major.children.title', { count: m.children.length })}>
      <Stack gap={6}>
        {m.children.length === 0 && (
          <Text size="sm" c="dimmed">
            {t('major.children.none')}
          </Text>
        )}
        {m.children.map((c) => (
          <TicketLink key={c.id} ticket={c} />
        ))}
        {m.permissions.manage && m.state !== 'closed' && (
          <Group gap="xs" wrap="nowrap" mt="xs">
            <TextInput
              placeholder="INC-000123"
              aria-label={t('major.children.add')}
              value={number}
              onChange={(e) => setNumber(e.currentTarget.value)}
              style={{ flex: 1 }}
            />
            <Button variant="default" onClick={() => void submit()} disabled={!number.trim()} loading={link.isPending}>
              {t('major.children.add')}
            </Button>
          </Group>
        )}
        {(error != null || link.error) && <ErrorLine error={error ?? link.error} />}
        <Text size="xs" c="dimmed">
          {t('major.children.hint')}
        </Text>
      </Stack>
    </Section>
  );
}
