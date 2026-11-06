import { Button, Group, Stack, Text, TextInput, Textarea, Title } from '@mantine/core';
import { IconTicket } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router';
import { ApiError } from '@/shared/api/client';
import { ListState } from '@/shared/ui/ListState';
import { StatusBadge as TicketStatusBadge } from '@/shared/ui/TicketBadges';
import { useTicket, type TicketDetails } from '@/features/tickets/api';
import { newIdempotencyKey, useCreateFromTicket } from './api';
import { CategoryField } from './CategoryField';
import { Notice, notifyError } from './ui';
import './knowledge.css';

/** Заявка решена или закрыта: только из такой можно сделать статью (KB-05). */
export const canMakeArticle = (category: string) => category === 'resolved' || category === 'closed';

/**
 * «Статья из заявки» (KB-05): черновик по шаблону «Решение проблемы». Тема, описание и решение
 * подставляются из заявки, агент правит их и добавляет причину. Статья видна только агентам, пока
 * редактор знаний её не опубликует. Сервер ещё раз проверяет, что заявка видна автору и решена.
 */
export function ArticleFromTicket({ ticketRef }: { ticketRef: string }) {
  const { t } = useTranslation();
  const ticket = useTicket(ticketRef);

  // Карточка могла попасть в кэш до решения: ждём свежих данных, а не показываем «не решена».
  if (ticket.isPending || (ticket.isFetching && ticket.data && !canMakeArticle(ticket.data.status.category))) {
    return <ListState state="loading" rows={6} />;
  }
  if (ticket.error) {
    const notFound = ticket.error instanceof ApiError && ticket.error.status === 404;
    return notFound ? (
      <ListState state="empty" title={t('kb.fromTicket.notFound')} />
    ) : (
      <ListState state="error" error={ticket.error} onRetry={() => void ticket.refetch()} />
    );
  }
  return <FromTicketForm key={ticket.data.id} ticket={ticket.data} />;
}

function FromTicketForm({ ticket }: { ticket: TicketDetails }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const create = useCreateFromTicket();
  const [title, setTitle] = useState(ticket.title);
  const [symptoms, setSymptoms] = useState(ticket.description ?? '');
  const [cause, setCause] = useState('');
  const [solution, setSolution] = useState(ticket.resolutionText ?? '');
  const [categoryId, setCategoryId] = useState<string | null>(null);
  // Один ключ на попытку: повторное нажатие после сбоя сети не создаст вторую статью.
  const [key, setKey] = useState(newIdempotencyKey);
  const ready = canMakeArticle(ticket.status.category);

  const submit = () =>
    create.mutate(
      {
        input: {
          ticketId: ticket.id,
          title: title.trim(),
          symptoms: symptoms.trim() || undefined,
          cause: cause.trim() || undefined,
          solution: solution.trim(),
          categoryId: categoryId ?? undefined,
        },
        key,
      },
      {
        onSuccess: (a) => navigate(`/agent/kb/${a.id}/edit`, { replace: true }),
        onError: (e) => {
          notifyError(t, e);
          setKey(newIdempotencyKey());
        },
      },
    );

  return (
    <Stack maw={760} mx="auto" gap="lg" py="md">
      <Title order={1}>{t('kb.fromTicket.title')}</Title>

      <div className="kb-source">
        <IconTicket size={20} aria-hidden style={{ color: 'var(--ei-text-3)', flex: 'none' }} />
        <Stack gap={2} style={{ minWidth: 0, flex: 1 }}>
          <Group gap={8}>
            <Link to={`/agent/tickets/${encodeURIComponent(ticket.number)}`} className="ei-mono" style={{ color: 'var(--ei-accent-text)' }}>
              {ticket.number}
            </Link>
            <TicketStatusBadge category={ticket.status.category} label={ticket.status.name} />
          </Group>
          <Text size="sm" lineClamp={1}>
            {ticket.title}
          </Text>
        </Stack>
      </div>

      {!ready && (
        <Notice tone="warn" title={t('kb.fromTicket.notResolvedTitle')}>
          {t('kb.fromTicket.notResolved')}
        </Notice>
      )}

      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (ready && title.trim() && solution.trim()) submit();
        }}
      >
        <Stack gap="md">
          <TextInput
            label={t('kb.fields.title')}
            value={title}
            onChange={(e) => setTitle(e.currentTarget.value)}
            maxLength={255}
            required
            data-autofocus
          />
          <Textarea
            label={t('kb.fromTicket.symptoms')}
            description={t('kb.fromTicket.symptomsHint')}
            value={symptoms}
            onChange={(e) => setSymptoms(e.currentTarget.value)}
            autosize
            minRows={3}
            maxRows={12}
          />
          <Textarea
            label={t('kb.fromTicket.cause')}
            description={t('kb.fromTicket.causeHint')}
            value={cause}
            onChange={(e) => setCause(e.currentTarget.value)}
            autosize
            minRows={2}
            maxRows={10}
          />
          <Textarea
            label={t('kb.fromTicket.solution')}
            description={t('kb.fromTicket.solutionHint')}
            value={solution}
            onChange={(e) => setSolution(e.currentTarget.value)}
            autosize
            minRows={3}
            maxRows={12}
            required
            error={ready && !solution.trim() ? t('kb.fromTicket.needSolution') : undefined}
          />
          <CategoryField value={categoryId} onChange={setCategoryId} />
          <Text size="sm" c="dimmed">
            {t('kb.fromTicket.audienceNote')}
          </Text>
          <Group justify="flex-end">
            <Button variant="default" onClick={() => navigate(-1)}>
              {t('kb.actions.cancel')}
            </Button>
            <Button type="submit" disabled={!ready || !title.trim() || !solution.trim()} loading={create.isPending}>
              {t('kb.new.create')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Stack>
  );
}
