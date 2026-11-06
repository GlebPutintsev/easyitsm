import { Button, Grid, Group, Radio, Select, Stack, Text, Textarea } from '@mantine/core';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { type Change, type RiskLevel, useChangeAction, useConflicts, useImpact, useRiskQuestions } from './api';
import { RiskBadge } from './ChangeBadges';
import { ConflictList } from './dialogs';
import { Section } from './OverviewTab';

/** «Риск и влияние» (CHG-04, CHG-05, CHG-06): анкета, услуги по связям CMDB и пересечения в календаре. */
export function RiskTab({ change: c }: { change: Change }) {
  return (
    <Grid gap="md">
      <Grid.Col span={{ base: 12, md: 7 }}>
        <Questionnaire change={c} />
      </Grid.Col>
      <Grid.Col span={{ base: 12, md: 5 }}>
        <Stack gap="md">
          <ImpactPanel change={c} />
          <ConflictsPanel change={c} />
        </Stack>
      </Grid.Col>
    </Grid>
  );
}

function Questionnaire({ change: c }: { change: Change }) {
  const { t, i18n } = useTranslation();
  const questions = useRiskQuestions();
  const save = useChangeAction(c);
  const [answers, setAnswers] = useState<Record<string, string>>(c.risk.answers ?? {});
  const [manual, setManual] = useState<RiskLevel | null>(c.risk.manual ? c.risk.level : null);
  const [reason, setReason] = useState(c.risk.reason ?? '');
  const editable = c.permissions.assess;
  const complete = (questions.data ?? []).every((q) => answers[q.code]);

  return (
    <Section title={t('changes.risk.title')}>
      <Group gap="sm">
        <RiskBadge level={c.risk.level} />
        {c.risk.score != null && (
          <Text size="sm" c="dimmed">
            {t('changes.risk.score', { score: c.risk.score })}
          </Text>
        )}
        {c.risk.manual && c.risk.computedLevel && (
          <Text size="sm" c="dimmed">
            {t('changes.risk.computed', { level: t(`changes.risk.level.${c.risk.computedLevel}`) })}
          </Text>
        )}
        {c.risk.assessedAt && (
          <Text size="xs" c="dimmed">
            {new Date(c.risk.assessedAt).toLocaleString(i18n.language, { dateStyle: 'short', timeStyle: 'short' })}
          </Text>
        )}
      </Group>
      {c.risk.manual && c.risk.reason && (
        <Text size="sm">
          {t('changes.risk.manualReason')}: {c.risk.reason}
        </Text>
      )}
      {!editable && c.state === 'assessment' && (
        <Text size="sm" c="dimmed">
          {t('changes.risk.onlyManager')}
        </Text>
      )}
      {(questions.data ?? []).map((q) => (
        <Radio.Group key={q.code} label={q.text} value={answers[q.code] ?? null} onChange={(v) => setAnswers((a) => ({ ...a, [q.code]: v }))} readOnly={!editable}>
          <Stack gap={4} mt={6}>
            {q.options.map((o) => (
              <Radio key={o.code} value={o.code} label={o.label} disabled={!editable} />
            ))}
          </Stack>
        </Radio.Group>
      ))}
      {editable && (
        <>
          <Group grow align="flex-start">
            <Select
              label={t('changes.risk.manualLevel')}
              description={t('changes.risk.manualHint')}
              clearable
              value={manual}
              onChange={(v) => setManual(v as RiskLevel | null)}
              data={(['low', 'medium', 'high'] as const).map((v) => ({ value: v, label: t(`changes.risk.level.${v}`) }))}
            />
            {manual && <Textarea label={t('changes.risk.manualReason')} withAsterisk autosize minRows={2} value={reason} onChange={(e) => setReason(e.currentTarget.value)} />}
          </Group>
          <ErrorLine error={save.error} />
          <Group justify="flex-end">
            <Button
              disabled={!complete || (!!manual && !reason.trim())}
              loading={save.isPending}
              onClick={() => save.mutate({ method: 'PUT', path: '/risk', body: { answers, level: manual ?? undefined, reason: manual ? reason.trim() : undefined } })}
            >
              {t('changes.risk.save')}
            </Button>
          </Group>
        </>
      )}
    </Section>
  );
}

function ImpactPanel({ change: c }: { change: Change }) {
  const { t } = useTranslation();
  const impact = useImpact(c.id, c.ciIds.length > 0);
  return (
    <Section title={t('changes.impact.title')}>
      {c.ciIds.length === 0 ? (
        <Text size="sm" c="dimmed">
          {t('changes.impact.noCis')}
        </Text>
      ) : impact.isPending ? (
        <Text size="sm" c="dimmed">
          {t('states.loading')}
        </Text>
      ) : impact.data && impact.data.services.length > 0 ? (
        <Stack gap={6}>
          {impact.data.services.map((s) => (
            <Group key={s.serviceId} gap="xs" justify="space-between" wrap="nowrap">
              <Text size="sm" fw={600}>
                {s.name}
              </Text>
              <Text size="xs" c="dimmed">
                {t('changes.impact.depth', { n: s.depth })}
                {impact.data.declaredServiceIds.includes(s.serviceId) ? '' : ` · ${t('changes.impact.notDeclared')}`}
              </Text>
            </Group>
          ))}
        </Stack>
      ) : (
        <Text size="sm" c="dimmed">
          {t('changes.impact.none')}
        </Text>
      )}
      <ErrorLine error={impact.error} />
    </Section>
  );
}

function ConflictsPanel({ change: c }: { change: Change }) {
  const { t } = useTranslation();
  const conflicts = useConflicts(c.windowStart ? c.id : undefined);
  return (
    <Section title={t('changes.conflicts.title')}>
      {!c.windowStart ? (
        <Text size="sm" c="dimmed">
          {t('changes.conflicts.noWindow')}
        </Text>
      ) : conflicts.data && conflicts.data.length > 0 ? (
        <ConflictList conflicts={conflicts.data} />
      ) : (
        <Text size="sm" c="dimmed">
          {conflicts.isPending ? t('states.loading') : t('changes.conflicts.none')}
        </Text>
      )}
      {c.conflictsAck && (
        <Text size="sm">
          {t('changes.fields.conflictsAck')}: {c.conflictsAck}
        </Text>
      )}
    </Section>
  );
}
