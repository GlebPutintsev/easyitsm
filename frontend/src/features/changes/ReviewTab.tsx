import { Anchor, Button, Group, SegmentedControl, Stack, Text, Textarea } from '@mantine/core';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { type Change, useChangeAction, useRelatedIncidents } from './api';
import classes from './changes.module.css';
import { Field, Section } from './OverviewTab';

/** Вопросы «Отчёта о выполнении работ»: да/нет или текст. Ключи — как в API (REVIEW_KEYS). */
const QUESTIONS: { key: string; kind: 'yesno' | 'text' }[] = [
  { key: 'goalAchieved', kind: 'yesno' },
  { key: 'enoughInfo', kind: 'yesno' },
  { key: 'causedIncidents', kind: 'yesno' },
  { key: 'incidentNumbers', kind: 'text' },
  { key: 'incidentCauses', kind: 'text' },
  { key: 'consequences', kind: 'text' },
  { key: 'correctiveActions', kind: 'text' },
  { key: 'onTime', kind: 'yesno' },
  { key: 'impactAsPlanned', kind: 'yesno' },
  { key: 'docsUpdated', kind: 'yesno' },
  { key: 'rollbackWorked', kind: 'yesno' },
  { key: 'comment', kind: 'text' },
];

/** «Разбор» после внедрения (CHG-11): связанные инциденты за 72 часа и анкета; закрывает менеджер изменений. */
export function ReviewTab({ change: c }: { change: Change }) {
  const { t, i18n } = useTranslation();
  const related = useRelatedIncidents(c.id, true);
  const incidents = related.data ?? [];
  return (
    <Stack gap="md" maw={960}>
      <Section title={t('changes.review.outcome')}>
        <dl className={classes.field}>
          <Field label={t('changes.fields.outcome')} value={c.outcome ? t(`changes.outcome.${c.outcome}`) : null} />
          <Field
            label={t('changes.review.actual')}
            value={
              c.actualStart
                ? `${new Date(c.actualStart).toLocaleString(i18n.language)} – ${c.actualEnd ? new Date(c.actualEnd).toLocaleString(i18n.language) : '…'}`
                : null
            }
          />
          <Field label={t('changes.fields.conclusion')} value={c.conclusion} />
        </dl>
      </Section>
      <Section title={t('changes.review.related')}>
        {incidents.length === 0 ? (
          <Text size="sm" c="dimmed">
            {related.isPending ? t('states.loading') : t('changes.review.noRelated')}
          </Text>
        ) : (
          incidents.map((i) => (
            <Group key={i.id} gap="xs">
              <Anchor component={Link} to={`/agent/tickets/${i.number}`} className={classes.number}>
                {i.number}
              </Anchor>
              <Text size="sm">{i.title}</Text>
              <Text size="xs" c="dimmed">
                {new Date(i.createdAt).toLocaleString(i18n.language, { dateStyle: 'short', timeStyle: 'short' })}
              </Text>
            </Group>
          ))
        )}
      </Section>
      {c.permissions.close ? (
        <ReviewForm key={related.isPending ? "loading" : "ready"} change={c} relatedNumbers={incidents.map((i) => i.number)} />
      ) : (
        <ReviewRead change={c} />
      )}
    </Stack>
  );
}

function ReviewForm({ change: c, relatedNumbers }: { change: Change; relatedNumbers: string[] }) {
  const { t } = useTranslation();
  const close = useChangeAction(c);
  const [answers, setAnswers] = useState<Record<string, string>>(() => {
    const init: Record<string, string> = {};
    for (const [k, v] of Object.entries(c.review ?? {})) init[k] = String(v ?? '');
    if (!init.causedIncidents && relatedNumbers.length) init.causedIncidents = 'yes';
    if (!init.incidentNumbers && relatedNumbers.length) init.incidentNumbers = relatedNumbers.join(', ');
    return init;
  });
  const [conclusion, setConclusion] = useState(c.conclusion ?? '');
  const set = (k: string, v: string) => setAnswers((a) => ({ ...a, [k]: v }));
  return (
    <Section title={t('changes.review.report')}>
      {QUESTIONS.map((q) =>
        q.kind === 'yesno' ? (
          <Group key={q.key} justify="space-between" wrap="wrap" gap="xs">
            <Text size="sm" style={{ flex: 1, minWidth: 240 }}>
              {t(`changes.review.q.${q.key}`)}
            </Text>
            <SegmentedControl
              size="xs"
              value={answers[q.key] ?? ''}
              onChange={(v) => set(q.key, v)}
              data={[
                { value: 'yes', label: t('changes.review.yes') },
                { value: 'no', label: t('changes.review.no') },
                { value: '', label: '—' },
              ]}
            />
          </Group>
        ) : (
          <Textarea key={q.key} label={t(`changes.review.q.${q.key}`)} autosize minRows={1} value={answers[q.key] ?? ''} onChange={(e) => set(q.key, e.currentTarget.value)} />
        ),
      )}
      <Textarea label={t('changes.review.conclusion')} withAsterisk autosize minRows={2} value={conclusion} onChange={(e) => setConclusion(e.currentTarget.value)} />
      <ErrorLine error={close.error} />
      <Group justify="flex-end">
        <Button
          disabled={!conclusion.trim()}
          loading={close.isPending}
          onClick={() =>
            close.mutate({
              path: '/close',
              body: { answers: Object.fromEntries(Object.entries(answers).filter(([, v]) => v !== '')), conclusion: conclusion.trim() },
            })
          }
        >
          {t('changes.actions.close')}
        </Button>
      </Group>
    </Section>
  );
}

function ReviewRead({ change: c }: { change: Change }) {
  const { t } = useTranslation();
  const review = c.review ?? {};
  if (Object.keys(review).length === 0) return null;
  const show = (v: unknown) => (v === 'yes' ? t('changes.review.yes') : v === 'no' ? t('changes.review.no') : v == null ? null : String(v));
  return (
    <Section title={t('changes.review.report')}>
      <dl className={classes.field}>
        {QUESTIONS.filter((q) => review[q.key] != null).map((q) => (
          <Field key={q.key} label={t(`changes.review.q.${q.key}`)} value={show(review[q.key])} />
        ))}
      </dl>
    </Section>
  );
}
