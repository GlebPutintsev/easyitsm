import { useTranslation } from 'react-i18next';
import { ListState } from '@/shared/ui/ListState';

export function NotFound() {
  const { t } = useTranslation();
  return <ListState state="empty" title={t('errors.notFound')} />;
}
