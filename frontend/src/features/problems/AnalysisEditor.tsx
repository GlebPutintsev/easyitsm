import { ActionIcon, Button, Group, Stack, Text, TextInput, Textarea } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconPlus, IconX } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { ISHIKAWA, problemsApi, useProblemMutation, type Analysis, type IshikawaCategory, type Problem } from './api';
import classes from './problems.module.css';
import { fromLocalInput, toLocalInput } from './ui';

/**
 * Анализ причин (PRB-02): хронология, «5 почему», диаграмма Исикавы и заключение. Всё сохраняется одной кнопкой,
 * версия карточки идёт в If-Match.
 */
export function AnalysisEditor({ problem }: { problem: Problem }) {
  const { t } = useTranslation();
  const readOnly = !problem.permissions.edit;
  const [a, setA] = useState<Analysis>(() => initial(problem));
  const save = useProblemMutation((analysis: Analysis) => problemsApi.saveAnalysis(problem, { analysis }));
  const set = (patch: Partial<Analysis>) => setA((prev) => ({ ...prev, ...patch }));

  const submit = () =>
    save.mutate(
      {
        ...a,
        timeline: a.timeline.filter((e) => e.text.trim()),
        whys: a.whys.filter((w) => w.question.trim() || w.answer.trim()),
        ishikawa: Object.fromEntries(
          Object.entries(a.ishikawa)
            .map(([k, v]) => [k, (v ?? []).filter((c) => c.trim())] as const)
            .filter(([, v]) => v.length > 0),
        ),
      },
      { onSuccess: () => notifications.show({ message: t('problems.card.saved'), color: 'green', autoClose: 2000 }) },
    );

  return (
    <Stack gap="md">
      <section className={classes.section}>
        <Stack gap="sm">
          <div>
            <div className={classes.label}>{t('problems.analysis.timeline')}</div>
            <Text size="xs" c="dimmed" mt={6}>
              {t('problems.analysis.timelineHint')}
            </Text>
          </div>
          {a.timeline.length > 0 && (
            <div className={classes.timeline}>
              {a.timeline.map((e, i) => (
                <div key={i} className={classes.event}>
                  <TextInput
                    type="datetime-local"
                    size="xs"
                    aria-label={t('problems.analysis.eventAt')}
                    value={toLocalInput(e.at)}
                    readOnly={readOnly}
                    onChange={(ev) => set({ timeline: replace(a.timeline, i, { ...e, at: fromLocalInput(ev.currentTarget.value) }) })}
                    visibleFrom="sm"
                  />
                  <TextInput
                    size="xs"
                    aria-label={t('problems.analysis.eventText')}
                    value={e.text}
                    readOnly={readOnly}
                    maxLength={2000}
                    onChange={(ev) => set({ timeline: replace(a.timeline, i, { ...e, text: ev.currentTarget.value }) })}
                  />
                  {!readOnly && <RemoveButton onClick={() => set({ timeline: a.timeline.filter((_, j) => j !== i) })} />}
                </div>
              ))}
            </div>
          )}
          {!readOnly && (
            <Group>
              <Button
                size="xs"
                variant="subtle"
                leftSection={<IconPlus size={14} />}
                onClick={() => set({ timeline: [...a.timeline, { at: new Date().toISOString(), text: '' }] })}
              >
                {t('problems.analysis.addEvent')}
              </Button>
            </Group>
          )}
        </Stack>
      </section>

      <section className={classes.section}>
        <Stack gap="sm">
          <div>
            <div className={classes.label}>{t('problems.analysis.whys')}</div>
            <Text size="xs" c="dimmed" mt={6}>
              {t('problems.analysis.whysHint')}
            </Text>
          </div>
          <div className={classes.whys}>
            {a.whys.map((w, i) => (
              <div key={i} className={`${classes.why} ${i === a.whys.length - 1 ? classes.whyLast : ''}`}>
                <span className={classes.whyNo} aria-hidden="true">
                  {i + 1}
                </span>
                <Stack gap={6}>
                  <TextInput
                    size="sm"
                    aria-label={`${t('problems.analysis.why')} ${i + 1}`}
                    placeholder={t('problems.analysis.why')}
                    value={w.question}
                    readOnly={readOnly}
                    maxLength={1000}
                    onChange={(ev) => set({ whys: replace(a.whys, i, { ...w, question: ev.currentTarget.value }) })}
                  />
                  <Textarea
                    size="sm"
                    autosize
                    minRows={1}
                    aria-label={`${t('problems.analysis.because')} ${i + 1}`}
                    placeholder={t('problems.analysis.because')}
                    value={w.answer}
                    readOnly={readOnly}
                    maxLength={2000}
                    onChange={(ev) => set({ whys: replace(a.whys, i, { ...w, answer: ev.currentTarget.value }) })}
                  />
                </Stack>
                {!readOnly && a.whys.length > 1 && <RemoveButton onClick={() => set({ whys: a.whys.filter((_, j) => j !== i) })} />}
              </div>
            ))}
          </div>
          {!readOnly && a.whys.length < 10 && (
            <Group>
              <Button
                size="xs"
                variant="subtle"
                leftSection={<IconPlus size={14} />}
                onClick={() => {
                  const last = a.whys[a.whys.length - 1];
                  const next = last?.answer.trim() ? `${t('problems.analysis.why')} ${last.answer.trim()}` : '';
                  set({ whys: [...a.whys, { question: next, answer: '' }] });
                }}
              >
                {t('problems.analysis.addWhy')}
              </Button>
            </Group>
          )}
        </Stack>
      </section>

      <section className={classes.section}>
        <Stack gap="sm">
          <div>
            <div className={classes.label}>{t('problems.analysis.ishikawa')}</div>
            <Text size="xs" c="dimmed" mt={6}>
              {t('problems.analysis.ishikawaHint')}
            </Text>
          </div>
          <div className={classes.fish} role="group" aria-label={t('problems.analysis.ishikawa')}>
            {ISHIKAWA.map((cat, i) => (
              <Bone
                key={cat}
                category={cat}
                position={i < 3 ? 'top' : 'bottom'}
                style={{ gridColumn: (i % 3) + 1, gridRow: i < 3 ? 1 : 3 }}
                causes={a.ishikawa[cat] ?? []}
                readOnly={readOnly}
                onChange={(causes) => set({ ishikawa: { ...a.ishikawa, [cat]: causes } })}
              />
            ))}
            <div className={classes.spine} aria-hidden="true" />
            <div className={classes.head}>
              <Text size="xs" c="dimmed" fw={500} mb={2}>
                {t('problems.analysis.head')}
              </Text>
              {problem.title}
            </div>
          </div>
        </Stack>
      </section>

      <section className={classes.section}>
        <Stack gap="sm">
          <div className={classes.label}>{t('problems.analysis.conclusion')}</div>
          <Textarea
            aria-label={t('problems.analysis.conclusion')}
            placeholder={t('problems.analysis.conclusionHint')}
            autosize
            minRows={3}
            maxLength={20000}
            readOnly={readOnly}
            value={a.conclusion}
            onChange={(ev) => set({ conclusion: ev.currentTarget.value })}
          />
        </Stack>
      </section>

      <ErrorLine error={save.error} />
      {!readOnly && (
        <Group justify="flex-end">
          <Button onClick={submit} loading={save.isPending}>
            {t('problems.card.save')}
          </Button>
        </Group>
      )}
    </Stack>
  );
}

function Bone({
  category,
  causes,
  onChange,
  readOnly,
  position,
  style,
}: {
  category: IshikawaCategory;
  causes: string[];
  onChange: (causes: string[]) => void;
  readOnly: boolean;
  position: 'top' | 'bottom';
  style: React.CSSProperties;
}) {
  const { t } = useTranslation();
  const name = t(`problems.analysis.categories.${category}`);
  return (
    <div className={`${classes.bone} ${position === 'top' ? classes.boneTop : classes.boneBottom}`} style={style}>
      <Text size="xs" fw={700} c="var(--ei-text-2)">
        {name}
      </Text>
      {causes.map((c, i) => (
        <Group key={i} gap={4} wrap="nowrap">
          <TextInput
            size="xs"
            style={{ flex: 1 }}
            aria-label={`${name}: ${i + 1}`}
            value={c}
            readOnly={readOnly}
            maxLength={500}
            onChange={(ev) => onChange(replace(causes, i, ev.currentTarget.value))}
          />
          {!readOnly && <RemoveButton onClick={() => onChange(causes.filter((_, j) => j !== i))} />}
        </Group>
      ))}
      {!readOnly && causes.length < 20 && (
        <Button size="compact-xs" variant="subtle" leftSection={<IconPlus size={12} />} onClick={() => onChange([...causes, ''])} aria-label={`${t('problems.analysis.addCause')}: ${name}`}>
          {t('problems.analysis.addCause')}
        </Button>
      )}
      {readOnly && causes.length === 0 && (
        <Text size="xs" c="dimmed">
          —
        </Text>
      )}
    </div>
  );
}

function RemoveButton({ onClick }: { onClick: () => void }) {
  const { t } = useTranslation();
  return (
    <ActionIcon variant="subtle" color="gray" size="sm" onClick={onClick} aria-label={t('problems.analysis.remove')}>
      <IconX size={14} />
    </ActionIcon>
  );
}

function replace<T>(list: T[], index: number, value: T): T[] {
  return list.map((x, i) => (i === index ? value : x));
}

/** Пустой анализ начинается с первого «почему» о теме проблемы. */
function initial(p: Problem): Analysis {
  const a = p.analysis;
  return {
    timeline: a.timeline ?? [],
    whys: a.whys?.length ? a.whys : [{ question: '', answer: '' }],
    ishikawa: a.ishikawa ?? {},
    conclusion: a.conclusion ?? '',
  };
}
