import { Button, Group, Text, Textarea } from '@mantine/core';
import { IconCheck } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import { useRateArticle, type ArticleDetails } from './api';
import { notifyError } from './ui';
import './knowledge.css';

/**
 * «Статья помогла?» (10.12, DESIGN.md): «Да, помогла» и «Нет». «Нет» раскрывает комментарий,
 * «Отправить отзыв» и «Создать заявку» — оценка с комментарием уходит в обоих случаях.
 */
export function ArticleFeedback({ article }: { article: ArticleDetails }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const rate = useRateArticle(article.id);
  const mine = article.feedback.mine;
  // Раскрыт ли комментарий к «Нет». После отправки отзыва сворачивается.
  const [asking, setAsking] = useState(false);
  const [comment, setComment] = useState('');
  const [sent, setSent] = useState(false);

  const send = (helpful: boolean, text?: string, then?: () => void) =>
    rate.mutate(
      { helpful, comment: text },
      {
        onSuccess: () => {
          setAsking(false);
          setSent(true);
          then?.();
        },
        onError: (e) => notifyError(t, e),
      },
    );

  const yes = mine === true && !asking;
  const no = asking || mine === false;

  return (
    <section className="kb-feedback" aria-labelledby="kb-feedback-title">
      <Group justify="space-between" gap="sm" align="center">
        <div id="kb-feedback-title" className="kb-feedback-title">
          {t('kb.feedback.question')}
        </div>
        {article.permissions.viewHistory && (
          <Text size="xs" c="dimmed">
            {t('kb.feedback.stats', { helpful: article.feedback.helpful, notHelpful: article.feedback.notHelpful })}
          </Text>
        )}
      </Group>
      <Group gap={10}>
        <button
          type="button"
          className="kb-vote"
          aria-pressed={yes}
          disabled={rate.isPending}
          onClick={() => send(true)}
        >
          <IconCheck size={16} aria-hidden />
          {t('kb.feedback.yes')}
        </button>
        <button type="button" className="kb-vote" aria-pressed={no} aria-expanded={asking} onClick={() => setAsking(true)}>
          {t('kb.feedback.no')}
        </button>
      </Group>

      {sent && !asking && (
        <Text size="sm" c="var(--ei-ok)" role="status">
          {t('kb.feedback.thanks')}
        </Text>
      )}

      {asking && (
        <>
          <Textarea
            label={t('kb.feedback.whatWrong')}
            placeholder={t('kb.feedback.commentPlaceholder')}
            value={comment}
            onChange={(e) => setComment(e.currentTarget.value)}
            autosize
            minRows={3}
            maxLength={2000}
            data-autofocus
          />
          <Group gap={10}>
            <Button
              loading={rate.isPending}
              onClick={() => send(false, comment.trim() || undefined, () => navigate('/portal/report'))}
            >
              {t('kb.feedback.createTicket')}
            </Button>
            <Button variant="default" loading={rate.isPending} onClick={() => send(false, comment.trim() || undefined)}>
              {t('kb.feedback.send')}
            </Button>
            <Button variant="subtle" color="gray" onClick={() => setAsking(false)}>
              {t('kb.actions.cancel')}
            </Button>
          </Group>
        </>
      )}
    </section>
  );
}
