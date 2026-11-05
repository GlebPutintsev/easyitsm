import { Anchor, Button, Group, Stack, Text, Textarea } from '@mantine/core';
import { IconThumbDown, IconThumbUp } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { useRateArticle, type ArticleDetails } from './api';
import { notifyError } from './ui';

/**
 * «Помогло / Не помогло» (10.12). Второй ответ предлагает комментарий и создание обращения.
 */
export function ArticleFeedback({ article }: { article: ArticleDetails }) {
  const { t } = useTranslation();
  const rate = useRateArticle(article.id);
  const [asking, setAsking] = useState(false);
  const [comment, setComment] = useState('');
  const mine = article.feedback.mine;

  const send = (helpful: boolean, text?: string) =>
    rate.mutate(
      { helpful, comment: text },
      {
        onSuccess: () => setAsking(false),
        onError: (e) => notifyError(t, e),
      },
    );

  if (asking) {
    return (
      <Stack gap="sm">
        <Text fw={500}>{t('kb.feedback.whatWrong')}</Text>
        <Textarea value={comment} onChange={(e) => setComment(e.currentTarget.value)} autosize minRows={2} maxLength={2000} placeholder={t('kb.feedback.commentPlaceholder')} />
        <Group gap="sm">
          <Button size="sm" onClick={() => send(false, comment.trim() || undefined)} loading={rate.isPending}>
            {t('kb.feedback.send')}
          </Button>
          <Button size="sm" variant="default" onClick={() => setAsking(false)}>
            {t('kb.actions.cancel')}
          </Button>
          <Anchor component={Link} to="/portal" size="sm">
            {t('kb.feedback.createTicket')}
          </Anchor>
        </Group>
      </Stack>
    );
  }

  return (
    <Group justify="space-between" gap="sm">
      <Group gap="sm">
        <Text fw={500}>{mine == null ? t('kb.feedback.question') : t('kb.feedback.thanks')}</Text>
        <Button
          size="xs"
          variant={mine === true ? 'filled' : 'light'}
          color="green"
          leftSection={<IconThumbUp size={16} />}
          onClick={() => send(true)}
          loading={rate.isPending && rate.variables?.helpful === true}
        >
          {t('kb.feedback.helpful')}
        </Button>
        <Button
          size="xs"
          variant={mine === false ? 'filled' : 'light'}
          color="red"
          leftSection={<IconThumbDown size={16} />}
          onClick={() => setAsking(true)}
        >
          {t('kb.feedback.notHelpful')}
        </Button>
      </Group>
      {article.permissions.viewHistory && (
        <Text size="xs" c="dimmed">
          {t('kb.feedback.stats', { helpful: article.feedback.helpful, notHelpful: article.feedback.notHelpful })}
        </Text>
      )}
    </Group>
  );
}
