import { Button, type ButtonProps } from '@mantine/core';
import { IconBook2 } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import { newIdempotencyKey, useCreateFromTicket, type CreateFromTicketInput } from './api';
import { notifyError } from './ui';

/**
 * KB-05: кнопка «Создать статью» для карточки решённой заявки. Создаёт черновик по шаблону
 * «Решение проблемы» (видимость — агенты) и открывает редактор. Карточку заявки делает рабочее место агента.
 */
export function CreateArticleFromTicketButton({ ticket, ...props }: { ticket: CreateFromTicketInput } & ButtonProps) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const create = useCreateFromTicket();
  const [key, setKey] = useState(newIdempotencyKey);

  return (
    <Button
      variant="light"
      leftSection={<IconBook2 size={16} />}
      loading={create.isPending}
      disabled={!ticket.solution?.trim()}
      title={!ticket.solution?.trim() ? t('kb.fromTicket.needSolution') : undefined}
      onClick={() =>
        create.mutate(
          { input: ticket, key },
          {
            onSuccess: (a) => navigate(`/agent/kb/${a.id}/edit`),
            onError: (e) => {
              notifyError(t, e);
              setKey(newIdempotencyKey());
            },
          },
        )
      }
      {...props}
    >
      {t('kb.fromTicket.button')}
    </Button>
  );
}
