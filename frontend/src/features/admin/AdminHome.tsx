import { Title } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { ListState } from '@/shared/ui/ListState';

export function AdminHome() {
  const { t } = useTranslation();
  return (
    <>
      <Title order={2} mb="md">
        {t('admin.title')}
      </Title>
      <ListState state="empty" title={t('admin.placeholder')} />
    </>
  );
}
