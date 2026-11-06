import { ActionIcon, Button, Grid, Group, NumberInput, Select, SimpleGrid, Stack, Table, Text, Textarea, TextInput } from '@mantine/core';
import { IconPlus, IconTrash } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { type Change, type ImpactRow, useChangeAction } from './api';
import classes from './changes.module.css';
import { Person } from './ChangesPage';
import { CiPicker } from './CiPicker';
import { formatInZone, formatWindow, fromLocalInput, minutesLabel, toLocalInput } from './model';

const CRITICALITY = ['critical', 'high', 'medium', 'low'] as const;
const ZONES = ['Europe/Kaliningrad', 'Europe/Moscow', 'Europe/Samara', 'Asia/Yekaterinburg', 'Asia/Omsk', 'Asia/Novosibirsk', 'Asia/Krasnoyarsk', 'Asia/Irkutsk', 'Asia/Yakutsk', 'Asia/Vladivostok', 'Asia/Magadan', 'Asia/Kamchatka', 'UTC'];

/** «Обзор»: поля формы ЗНИ (CHG-02). Правка — пока изменение не согласовано; после — только чтение. */
export function OverviewTab({ change }: { change: Change }) {
  return change.permissions.edit ? <OverviewForm change={change} /> : <OverviewRead change={change} />;
}

function OverviewForm({ change: c }: { change: Change }) {
  const { t } = useTranslation();
  const save = useChangeAction(c);
  const [goal, setGoal] = useState(c.goal ?? '');
  const [justification, setJustification] = useState(c.justification ?? '');
  const [impact, setImpact] = useState(c.impact ?? '');
  const [risks, setRisks] = useState(c.risks ?? '');
  const [rows, setRows] = useState<ImpactRow[]>(c.impactRows ?? []);
  const [criticality, setCriticality] = useState<string | null>(c.systemCriticality);
  const [start, setStart] = useState(toLocalInput(c.windowStart));
  const [end, setEnd] = useState(toLocalInput(c.windowEnd));
  const [prep, setPrep] = useState(toLocalInput(c.prepStart));
  const [zone, setZone] = useState(c.timeZone);
  const [downtime, setDowntime] = useState<number | string>(c.downtimeMinutes ?? '');
  const [serviceIds, setServiceIds] = useState(c.serviceIds);
  const [ciIds, setCiIds] = useState(c.ciIds);

  const submit = () =>
    save.mutate({
      method: 'PATCH',
      path: '',
      body: {
        goal,
        justification,
        impact,
        risks,
        impactRows: rows.filter((r) => r.service.trim()),
        systemCriticality: criticality ?? '',
        windowStart: fromLocalInput(start),
        windowEnd: fromLocalInput(end),
        timeZone: zone,
        prepStart: fromLocalInput(prep),
        clearPrepStart: !prep,
        downtimeMinutes: downtime === '' ? null : Number(downtime),
        serviceIds,
        ciIds,
      },
    });

  return (
    <Stack gap="md" maw={1040}>
      <Section title={t('changes.sections.purpose')}>
        <Textarea label={t('changes.fields.goal')} withAsterisk autosize minRows={2} value={goal} onChange={(e) => setGoal(e.currentTarget.value)} />
        <Textarea label={t('changes.fields.justification')} withAsterisk autosize minRows={2} value={justification} onChange={(e) => setJustification(e.currentTarget.value)} />
        <Textarea label={t('changes.fields.impact')} withAsterisk={c.type !== 'emergency'} autosize minRows={2} value={impact} onChange={(e) => setImpact(e.currentTarget.value)} />
        <Textarea label={t('changes.fields.risks')} autosize minRows={2} value={risks} onChange={(e) => setRisks(e.currentTarget.value)} />
      </Section>

      <Section title={t('changes.sections.window')}>
        <SimpleGrid cols={{ base: 1, sm: 3 }}>
          <TextInput type="datetime-local" label={t('changes.fields.windowStart')} withAsterisk value={start} onChange={(e) => setStart(e.currentTarget.value)} />
          <TextInput type="datetime-local" label={t('changes.fields.windowEnd')} withAsterisk value={end} onChange={(e) => setEnd(e.currentTarget.value)} />
          <Select label={t('changes.fields.timeZone')} data={ZONES} value={zone} onChange={(v) => setZone(v ?? 'Europe/Moscow')} searchable allowDeselect={false} />
          <TextInput type="datetime-local" label={t('changes.fields.prepStart')} value={prep} onChange={(e) => setPrep(e.currentTarget.value)} />
          <NumberInput label={t('changes.fields.downtime')} withAsterisk min={0} max={100000} suffix={` ${t('changes.duration.min')}`} value={downtime} onChange={setDowntime} />
          <Select
            label={t('changes.fields.criticality')}
            clearable
            value={criticality || null}
            onChange={setCriticality}
            data={CRITICALITY.map((v) => ({ value: v, label: t(`changes.criticality.${v}`) }))}
          />
        </SimpleGrid>
        <Text size="xs" c="dimmed">
          {t('changes.windowHint')}
        </Text>
      </Section>

      <Section title={t('changes.sections.affected')}>
        <CiPicker label={t('changes.fields.services')} services value={serviceIds} onChange={setServiceIds} known={c.cis} />
        <CiPicker label={t('changes.fields.cis')} value={ciIds} onChange={setCiIds} known={c.cis} />
        <Text size="sm" fw={600}>
          {t('changes.fields.impactRows')}
        </Text>
        <div className={classes.tableScroll}>
          <Table miw={640} verticalSpacing={6}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('changes.impactRows.service')}</Table.Th>
                <Table.Th>{t('changes.impactRows.description')}</Table.Th>
                <Table.Th w={160}>{t('changes.impactRows.duration')}</Table.Th>
                <Table.Th w={44} />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((r, i) => (
                <Table.Tr key={i}>
                  {(['service', 'description', 'duration'] as const).map((k) => (
                    <Table.Td key={k}>
                      <TextInput
                        aria-label={t(`changes.impactRows.${k}`)}
                        value={r[k] ?? ''}
                        onChange={(e) => {
                          const v = e.currentTarget.value;
                          setRows((all) => all.map((x, j) => (j === i ? { ...x, [k]: v } : x)));
                        }}
                      />
                    </Table.Td>
                  ))}
                  <Table.Td>
                    <ActionIcon variant="subtle" color="red" aria-label={t('changes.actions.removeRow')} onClick={() => setRows((all) => all.filter((_, j) => j !== i))}>
                      <IconTrash size={16} />
                    </ActionIcon>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </div>
        <Button variant="subtle" leftSection={<IconPlus size={16} />} w="fit-content" onClick={() => setRows((all) => [...all, { service: '', description: '', duration: '' }])}>
          {t('changes.actions.addRow')}
        </Button>
      </Section>

      <ErrorLine error={save.error} />
      <Group justify="flex-end">
        <Button onClick={submit} loading={save.isPending}>
          {t('changes.actions.save')}
        </Button>
      </Group>
      <Meta change={c} />
    </Stack>
  );
}

function OverviewRead({ change: c }: { change: Change }) {
  const { t, i18n } = useTranslation();
  const services = c.cis.filter((x) => c.serviceIds.includes(x.id));
  const cis = c.cis.filter((x) => c.ciIds.includes(x.id));
  return (
    <Grid gap="md">
      <Grid.Col span={{ base: 12, md: 8 }}>
        <Stack gap="md">
          <Section title={t('changes.sections.purpose')}>
            <dl className={classes.field}>
              <Field label={t('changes.fields.goal')} value={c.goal} />
              <Field label={t('changes.fields.justification')} value={c.justification} />
              <Field label={t('changes.fields.impact')} value={c.impact} />
              <Field label={t('changes.fields.risks')} value={c.risks} />
            </dl>
          </Section>
          {c.impactRows.length > 0 && (
            <Section title={t('changes.fields.impactRows')}>
              <Table verticalSpacing={6}>
                <Table.Tbody>
                  {c.impactRows.map((r, i) => (
                    <Table.Tr key={i}>
                      <Table.Td fw={600}>{r.service}</Table.Td>
                      <Table.Td>{r.description}</Table.Td>
                      <Table.Td>{r.duration}</Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </Section>
          )}
        </Stack>
      </Grid.Col>
      <Grid.Col span={{ base: 12, md: 4 }}>
        <Section title={t('changes.sections.window')}>
          <dl className={classes.field}>
            <Field label={t('changes.fields.window')} value={`${formatWindow(c, i18n.language)} (${c.timeZone})`} />
            <Field label={t('changes.fields.prepStart')} value={c.prepStart ? formatInZone(c.prepStart, i18n.language, c.timeZone) : null} />
            <Field label={t('changes.fields.downtime')} value={minutesLabel(c.downtimeMinutes, t)} />
            <Field label={t('changes.fields.criticality')} value={c.systemCriticality ? t(`changes.criticality.${c.systemCriticality}`) : null} />
            <Field label={t('changes.fields.services')} value={services.map((s) => s.name).join(', ') || (c.serviceIds.length ? c.serviceIds.join(', ') : null)} />
            <Field label={t('changes.fields.cis')} value={cis.map((s) => s.name).join(', ') || (c.ciIds.length ? c.ciIds.join(', ') : null)} />
          </dl>
        </Section>
        <Meta change={c} />
      </Grid.Col>
    </Grid>
  );
}

function Meta({ change: c }: { change: Change }) {
  const { t, i18n } = useTranslation();
  return (
    <Section title={t('changes.sections.people')}>
      <dl className={classes.field}>
        <dt>{t('changes.fields.author')}</dt>
        <dd>
          <Person id={c.authorId} />
        </dd>
        <dt>{t('changes.fields.assignee')}</dt>
        <dd>
          <Person id={c.assigneeId} />
        </dd>
        <Field label={t('changes.fields.created')} value={new Date(c.createdAt).toLocaleString(i18n.language)} />
        {c.conflictsAck && <Field label={t('changes.fields.conflictsAck')} value={c.conflictsAck} />}
        {c.cancelReason && <Field label={t('changes.fields.cancelReason')} value={c.cancelReason} />}
        {c.pipeline && (
          <Field
            label={t('changes.fields.pipeline')}
            value={Object.entries(c.pipeline)
              .map(([k, v]) => `${k}: ${v}`)
              .join('\n')}
          />
        )}
      </dl>
    </Section>
  );
}

export function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className={classes.card}>
      <Stack gap="sm">
        <div className={classes.sectionLabel}>{title}</div>
        {children}
      </Stack>
    </div>
  );
}

export function Field({ label, value }: { label: string; value: string | null | undefined }) {
  return (
    <>
      <dt>{label}</dt>
      <dd>{value && value.trim() ? value : '—'}</dd>
    </>
  );
}
