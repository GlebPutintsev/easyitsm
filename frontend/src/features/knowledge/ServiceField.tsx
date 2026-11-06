import { Select } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { api, type CursorPage } from '@/shared/api/client';

interface ServiceOption {
  id: string;
  name: string;
}

/**
 * Услуга статьи: в MVP — позиция каталога, как «Услуга» в заявке. Над статьёй показывается баннер
 * активного сбоя или работ, если объявление затрагивает эту услугу.
 */
export function ServiceField({ value, onChange }: { value: string | null; onChange: (id: string | null) => void }) {
  const { t } = useTranslation();
  const items = useQuery({
    queryKey: ['knowledge', 'service-options'],
    queryFn: ({ signal }) => api.get<CursorPage<ServiceOption>>('/catalog/items', { query: { limit: 200 }, signal }),
    select: (page) => page.items,
    staleTime: 5 * 60_000,
  });
  return (
    <Select
      label={t('kb.fields.service')}
      placeholder={t('kb.fields.noService')}
      description={t('kb.fields.serviceHint')}
      value={value}
      onChange={onChange}
      data={(items.data ?? []).map((s) => ({ value: s.id, label: s.name }))}
      clearable
      searchable
    />
  );
}
