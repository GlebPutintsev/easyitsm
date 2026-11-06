import { Badge, Button, Group, Stack, Text, TextInput, Textarea } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { problemsApi, useProblemMutation, type KnownError, type KnownErrorInput, type Problem } from './api';
import classes from './problems.module.css';

/** Известная ошибка проблемы (PRB-03): описание, обходное решение, публикация для агентов. */
export function KnownErrorForm({ problem }: { problem: Problem }) {
  const { t } = useTranslation();
  const ke = problem.knownError;
  const [editing, setEditing] = useState(false);
  const [form, setForm] = useState<KnownErrorInput>(() => formOf(problem, ke));
  const save = useProblemMutation((input: KnownErrorInput) => problemsApi.saveKnownError(problem, input));
  const publish = useProblemMutation((on: boolean) => problemsApi.publishKnownError(problem, on));

  if (!ke && !editing) {
    return (
      <section className={classes.section}>
        <Stack gap="sm" align="flex-start">
          <div className={classes.label}>{t('problems.knownError.title')}</div>
          <Text size="sm" c="dimmed">
            {t('problems.knownError.none')}. {t('problems.knownError.hint')}
          </Text>
          {problem.permissions.edit && <Button onClick={() => setEditing(true)}>{t('problems.knownError.create')}</Button>}
        </Stack>
      </section>
    );
  }

  const readOnly = !problem.permissions.edit;
  const submit = () =>
    save.mutate(form, {
      onSuccess: () => {
        setEditing(false);
        notifications.show({ message: t('problems.card.saved'), color: 'green', autoClose: 2000 });
      },
    });

  return (
    <section className={classes.section}>
      <Stack gap="sm">
        <Group justify="space-between" wrap="wrap">
          <div className={classes.label}>{t('problems.knownError.title')}</div>
          {ke && (
            <Group gap={6}>
              <Badge variant="light" color={ke.published ? 'green' : 'gray'}>
                {ke.published ? t('problems.knownError.published') : t('problems.knownError.draft')}
              </Badge>
              {ke.fixed && (
                <Badge variant="outline" color="green">
                  {t('problems.knownError.fixed')}
                </Badge>
              )}
            </Group>
          )}
        </Group>
        <Text size="xs" c="dimmed">
          {t('problems.knownError.hint')}
        </Text>
        <TextInput label={t('problems.knownError.name')} value={form.title} readOnly={readOnly} maxLength={255} required onChange={(e) => setForm({ ...form, title: e.currentTarget.value })} />
        <Textarea
          label={t('problems.knownError.symptoms')}
          description={t('problems.knownError.symptomsHint')}
          value={form.symptoms}
          readOnly={readOnly}
          autosize
          minRows={2}
          maxLength={20000}
          onChange={(e) => setForm({ ...form, symptoms: e.currentTarget.value })}
        />
        <Textarea
          label={t('problems.knownError.workaround')}
          value={form.workaround}
          readOnly={readOnly}
          autosize
          minRows={3}
          maxLength={20000}
          onChange={(e) => setForm({ ...form, workaround: e.currentTarget.value })}
        />
        <ErrorLine error={save.error ?? publish.error} />
        {!readOnly && (
          <Group justify="space-between" wrap="wrap">
            <Group gap={8}>
              {ke && problem.permissions.publishKnownError && (
                <Button variant="default" loading={publish.isPending} onClick={() => publish.mutate(!ke.published)}>
                  {ke.published ? t('problems.knownError.unpublish') : t('problems.knownError.publish')}
                </Button>
              )}
              {ke && !problem.permissions.publishKnownError && !ke.published && (
                <Text size="xs" c="dimmed">
                  {t('problems.knownError.publishHint')}
                </Text>
              )}
            </Group>
            <Button onClick={submit} loading={save.isPending} disabled={!form.title.trim()}>
              {t('problems.card.save')}
            </Button>
          </Group>
        )}
      </Stack>
    </section>
  );
}

function formOf(p: Problem, ke: KnownError | null): KnownErrorInput {
  return {
    title: ke?.title ?? p.title,
    symptoms: ke?.symptoms ?? '',
    workaround: ke?.workaround ?? p.workaround,
    serviceId: ke?.serviceId ?? p.serviceId,
    ciId: ke?.ciId ?? p.ciId,
    categoryId: ke?.categoryId ?? p.categoryId,
  };
}
