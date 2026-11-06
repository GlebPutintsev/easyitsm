import { Button, type ButtonProps } from '@mantine/core';
import { IconBook2 } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';

/**
 * KB-05: кнопка «Создать статью» для карточки решённой или закрытой заявки. Открывает форму
 * «Статья из заявки»: тема, описание и решение подставляются из заявки, затем открывается редактор.
 */
export function CreateArticleFromTicketButton({ ticketNumber, ...props }: { ticketNumber: string } & ButtonProps) {
  const { t } = useTranslation();
  return (
    <Button
      component={Link}
      to={`/agent/kb/new?fromTicket=${encodeURIComponent(ticketNumber)}`}
      variant="default"
      leftSection={<IconBook2 size={16} />}
      {...props}
    >
      {t('kb.fromTicket.button')}
    </Button>
  );
}
