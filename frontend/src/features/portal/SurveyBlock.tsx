import { Button, Card, Group, Rating, Stack, Text, Textarea } from '@mantine/core';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@/shared/api/client';
import { ErrorLine } from './TicketPage';
import { useSubmitSurvey, useSurvey } from './tickets';

/** Опрос удовлетворённости после закрытия (PRT-06): оценка 1–5 и комментарий, один раз. */
export function SurveyBlock({ ticketId }: { ticketId: string }) {
  const { t } = useTranslation();
  const existing = useSurvey(ticketId, true);
  const submit = useSubmitSurvey(ticketId);
  const [score, setScore] = useState(0);
  const [comment, setComment] = useState('');

  if (existing.isPending) return null;
  const notRated = existing.error instanceof ApiError && existing.error.status === 404;
  if (existing.data) {
    return (
      <Card withBorder padding="md">
        <Group gap="sm">
          <Text size="sm">{t('survey.thanks')}</Text>
          <Rating value={existing.data.score} readOnly aria-label={t('survey.yourScore', { score: existing.data.score })} />
        </Group>
      </Card>
    );
  }
  if (!notRated) return null;

  return (
    <Card withBorder padding="md">
      <Stack gap="sm">
        <Text fw={600}>{t('survey.question')}</Text>
        <Rating value={score} onChange={setScore} size="lg" aria-label={t('survey.score')} />
        {score > 0 && (
          <Textarea
            label={score <= 3 ? t('survey.whatWrong') : t('survey.comment')}
            autosize
            minRows={2}
            maxLength={2000}
            value={comment}
            onChange={(e) => setComment(e.currentTarget.value)}
          />
        )}
        <ErrorLine error={submit.error} />
        <Group>
          <Button disabled={!score} loading={submit.isPending} onClick={() => submit.mutate({ score, comment: comment.trim() || undefined })}>
            {t('survey.send')}
          </Button>
        </Group>
      </Stack>
    </Card>
  );
}
