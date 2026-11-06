import { Alert, Anchor, Badge, Button, Group, List, Select, Stack, Stepper, Text, TextInput, Title } from '@mantine/core';
import { IconCheck, IconCircleCheck, IconCircleX, IconPlus } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { ListState } from '@/shared/ui/ListState';
import { type CheckReport, SETUP_STEPS, type SetupState, type SetupStep, useSetup, useSetupMutations } from './api';
import {
  useCatalogCategoryCount,
  useGroupMutations,
  useGroups,
  useInstallSlaDefaults,
  useSlaPolicies,
} from './moduleApi';
import classes from './ui/admin.module.css';

export const TIMEZONES = [
  'Europe/Kaliningrad', 'Europe/Moscow', 'Europe/Samara', 'Asia/Yekaterinburg', 'Asia/Omsk', 'Asia/Novosibirsk',
  'Asia/Krasnoyarsk', 'Asia/Irkutsk', 'Asia/Yakutsk', 'Asia/Vladivostok', 'Asia/Magadan', 'Asia/Kamchatka',
  'Europe/Minsk', 'Asia/Almaty', 'Asia/Tashkent', 'UTC',
];

/** Мастер первичной настройки (ADM-01, 10.16): Далее, Назад, Пропустить, проверки и демоданные. */
export function SetupWizard() {
  const { t } = useTranslation();
  const setup = useSetup();
  const m = useSetupMutations();
  const navigate = useNavigate();
  const [active, setActive] = useState<number | null>(null);

  if (setup.isPending) return <ListState state="loading" />;
  if (setup.error || !setup.data) return <ListState state="error" error={setup.error} onRetry={() => void setup.refetch()} />;
  const state = setup.data;
  const current = active ?? Math.max(0, state.nextStep ? SETUP_STEPS.indexOf(state.nextStep) : SETUP_STEPS.length);
  const step = SETUP_STEPS[current];

  async function mark(s: SetupStep, status: 'done' | 'skipped') {
    await m.step.mutateAsync({ step: s, status });
    setActive(current + 1);
  }

  async function finish() {
    await m.complete.mutateAsync();
    navigate('/admin');
  }

  return (
    <Stack gap="lg" maw={960}>
      <Stack gap={4}>
        <Title order={2}>{t('admin.setup.title')}</Title>
        <Text className={classes.muted}>{t('admin.setup.lead')}</Text>
      </Stack>
      <Stepper active={current} onStepClick={setActive} size="sm" allowNextStepsSelect={false} wrap>
        {SETUP_STEPS.map((s) => (
          <Stepper.Step key={s} label={t(`admin.setup.steps.${s}.label`)}
            description={state.steps[s] === 'skipped' ? t('admin.setup.skipped') : undefined}
            completedIcon={state.steps[s] === 'skipped' ? <Text size="xs">—</Text> : <IconCheck size={16} />}
            allowStepSelect={s === 'organization' || state.steps.organization === 'done'} />
        ))}
      </Stepper>

      {step ? (
        <Stack gap="md" className={classes.panel}>
          <Stack gap={4}>
            <Title order={3}>{t(`admin.setup.steps.${step}.label`)}</Title>
            <Text size="sm" className={classes.muted}>
              {t(`admin.setup.steps.${step}.text`)}
            </Text>
          </Stack>
          {step === 'organization' && <OrganizationStep key={state.version} state={state} onDone={() => setActive(current + 1)} />}
          {step === 'login' && <CheckStep kind="sso" />}
          {step === 'mail' && <CheckStep kind="mail" />}
          {step === 'groups' && <GroupsStep />}
          {step === 'services' && <ServicesStep />}
          {step === 'sla' && <SlaStep timezone={state.organization.timezone} onDone={() => void mark('sla', 'done')} />}
          {step === 'demo' && <DemoStep state={state} />}
          <ErrorLine error={m.step.error} />
          <Group justify="space-between">
            <Button variant="default" disabled={current === 0} onClick={() => setActive(current - 1)}>
              {t('admin.setup.back')}
            </Button>
            {step !== 'organization' && (
              <Group gap="xs">
                <Button variant="subtle" onClick={() => void mark(step, 'skipped')} loading={m.step.isPending}>
                  {t('admin.setup.skip')}
                </Button>
                <Button onClick={() => void mark(step, 'done')} loading={m.step.isPending}>
                  {t('admin.setup.next')}
                </Button>
              </Group>
            )}
          </Group>
        </Stack>
      ) : (
        <Stack gap="md" className={classes.panel}>
          <Title order={3}>{state.completedAt ? t('admin.setup.completedTitle') : t('admin.setup.finishTitle')}</Title>
          <List spacing={4} size="sm">
            {SETUP_STEPS.map((s) => (
              <List.Item key={s}>
                {t(`admin.setup.steps.${s}.label`)} — {t(`admin.setup.state.${state.steps[s]}`)}
              </List.Item>
            ))}
          </List>
          <ErrorLine error={m.complete.error} />
          <Group>
            <Button variant="default" onClick={() => setActive(SETUP_STEPS.length - 1)}>
              {t('admin.setup.back')}
            </Button>
            <Button onClick={() => void finish()} loading={m.complete.isPending}>
              {state.completedAt ? t('admin.setup.toAdmin') : t('admin.setup.finish')}
            </Button>
          </Group>
        </Stack>
      )}
    </Stack>
  );
}

function OrganizationStep({ state, onDone }: { state: SetupState; onDone: () => void }) {
  const { t } = useTranslation();
  const m = useSetupMutations();
  const [org, setOrg] = useState(state.organization);
  const dirty = JSON.stringify(org) !== JSON.stringify(state.organization);

  async function save() {
    if (dirty || state.steps.organization !== 'done') {
      await m.organization.mutateAsync({ org, version: state.version });
    }
    onDone();
  }

  return (
    <Stack gap="sm" maw={480}>
      <TextInput label={t('admin.setup.org.name')} required value={org.name}
        onChange={(e) => setOrg({ ...org, name: e.currentTarget.value })} />
      <Select label={t('admin.setup.org.timezone')} data={TIMEZONES} searchable value={org.timezone}
        onChange={(v) => v && setOrg({ ...org, timezone: v })} />
      <Select label={t('admin.setup.org.locale')} value={org.locale} allowDeselect={false}
        data={[{ value: 'ru', label: 'Русский' }, { value: 'en', label: 'English' }]}
        onChange={(v) => v && setOrg({ ...org, locale: v })} />
      <ErrorLine error={m.organization.error} />
      <Group gap="xs">
        <Button onClick={() => void save()} loading={m.organization.isPending} disabled={!org.name.trim()}>
          {t('admin.setup.saveNext')}
        </Button>
        <Button variant="default" disabled={!dirty} onClick={() => setOrg(state.organization)}>
          {t('admin.cancel')}
        </Button>
      </Group>
    </Stack>
  );
}

function CheckResult({ report }: { report: CheckReport }) {
  const { t } = useTranslation();
  return (
    <Alert color={report.ok ? 'green' : report.configured ? 'red' : 'yellow'} title={
      report.ok ? t('admin.setup.check.ok') : report.configured ? t('admin.setup.check.failed') : t('admin.setup.check.notConfigured')}>
      <Stack gap={4}>
        {report.results.map((r) => (
          <Group key={r.target + r.code} gap={6} wrap="nowrap">
            {r.ok ? <IconCircleCheck size={16} aria-hidden /> : <IconCircleX size={16} aria-hidden />}
            <Text size="sm">
              {r.target && <span className={classes.mono}>{r.target}</span>} {r.detail}
              {r.latencyMs > 0 && <span className={classes.muted}> · {t('admin.setup.check.latency', { ms: r.latencyMs })}</span>}
            </Text>
          </Group>
        ))}
      </Stack>
    </Alert>
  );
}

function CheckStep({ kind }: { kind: 'mail' | 'sso' }) {
  const { t } = useTranslation();
  const m = useSetupMutations();
  const check = kind === 'mail' ? m.checkMail : m.checkSso;
  return (
    <Stack gap="sm">
      <Text size="sm">{t(`admin.setup.check.${kind}Hint`)}</Text>
      <Group>
        <Button variant="default" onClick={() => check.mutate()} loading={check.isPending}>
          {t(`admin.setup.check.${kind}`)}
        </Button>
        {kind === 'sso' && (
          <Anchor component={Link} to="/profile/security" size="sm">
            {t('admin.setup.check.mfaLink')}
          </Anchor>
        )}
      </Group>
      {check.data && <CheckResult report={check.data} />}
      <ErrorLine error={check.error} />
    </Stack>
  );
}

function GroupsStep() {
  const { t } = useTranslation();
  const groups = useGroups();
  const save = useGroupMutations().save;
  const [name, setName] = useState('');
  const rows = groups.data?.items ?? [];
  return (
    <Stack gap="sm">
      <Group gap={6}>
        {rows.map((g) => (
          <Badge key={g.id} variant="light" size="lg">
            {g.name}
          </Badge>
        ))}
        {!rows.length && !groups.isPending && <Text size="sm" className={classes.muted}>{t('admin.setup.noGroups')}</Text>}
      </Group>
      <Group gap="xs" align="flex-end">
        <TextInput label={t('admin.groups.newName')} value={name} onChange={(e) => setName(e.currentTarget.value)} />
        <Button variant="default" leftSection={<IconPlus size={16} />} disabled={!name.trim()} loading={save.isPending}
          onClick={() => save.mutate({ name: name.trim(), description: '', type: 'support' }, { onSuccess: () => setName('') })}>
          {t('admin.groups.create')}
        </Button>
        <Anchor component={Link} to="/admin/groups" size="sm">
          {t('admin.setup.openGroups')}
        </Anchor>
      </Group>
      <ErrorLine error={save.error} />
    </Stack>
  );
}

function ServicesStep() {
  const { t } = useTranslation();
  const cats = useCatalogCategoryCount();
  const n = cats.data?.items.length ?? 0;
  return (
    <Group gap="sm">
      <Text size="sm">{t('admin.setup.servicesCount', { count: n })}</Text>
      <Button variant="default" component={Link} to="/admin/catalog">
        {t('admin.setup.openCatalog')}
      </Button>
    </Group>
  );
}

function SlaStep({ timezone, onDone }: { timezone: string; onDone: () => void }) {
  const { t } = useTranslation();
  const policies = useSlaPolicies();
  const install = useInstallSlaDefaults();
  const rows = policies.data?.items ?? [];
  return (
    <Stack gap="sm">
      <Text size="sm">{t('admin.setup.slaDefaults')}</Text>
      {rows.length > 0 && (
        <Group gap={6}>
          {rows.map((p) => (
            <Badge key={p.id} variant="light">
              {p.name}
            </Badge>
          ))}
        </Group>
      )}
      <Group>
        <Button variant="default" loading={install.isPending}
          onClick={() => install.mutate(timezone, { onSuccess: onDone })}>
          {t('admin.setup.installSla', { tz: timezone })}
        </Button>
      </Group>
      <ErrorLine error={install.error} />
    </Stack>
  );
}

function DemoStep({ state }: { state: SetupState }) {
  const { t } = useTranslation();
  const demo = useSetupMutations().demo;
  return (
    <Stack gap="sm">
      {state.demoLoadedAt && !demo.data ? (
        <Alert color="green">{t('admin.setup.demoLoaded')}</Alert>
      ) : !demo.data && (
        <Group>
          <Button variant="default" onClick={() => demo.mutate()} loading={demo.isPending}>
            {t('admin.setup.loadDemo')}
          </Button>
        </Group>
      )}
      {demo.data && (
        <Alert color="green" title={t('admin.setup.demoDone')}>
          {t('admin.setup.demoSummary', {
            groups: demo.data.groups,
            users: demo.data.users,
            departments: demo.data.departments,
            tickets: demo.data.tickets.join(', '),
          })}
        </Alert>
      )}
      <ErrorLine error={demo.error} />
    </Stack>
  );
}
