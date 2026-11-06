import { Select } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { type CursorPage, api } from '@/shared/api/client';
import type { CiInfo } from './api';

interface CiOption {
  id: string;
  name: string;
  className: string | null;
}

/** Выбор КЕ из CMDB по имени (AST-05). Текущая КЕ актива видна в поле до начала поиска. */
export function CiPicker({ value, current, onChange }: { value: string; current: CiInfo | null; onChange: (id: string) => void }) {
  const { t } = useTranslation();
  const label = (ci: { name: string | null; className: string | null; id: string }) => {
    const name = ci.name ?? t('assets.card.ciUnknown', { id: ci.id.slice(0, 8) });
    return ci.className ? `${name} · ${ci.className}` : name;
  };
  const [search, setSearch] = useState(() => (current && current.id === value ? label(current) : ''));
  const [q] = useDebouncedValue(search, 250);
  const found = useQuery({
    queryKey: ['cmdb', 'cis', 'picker', q],
    queryFn: ({ signal }) => api.get<CursorPage<CiOption>>('/cmdb/cis', { query: { q, limit: 20 }, signal }).then((p) => p.items),
    enabled: q.trim().length >= 2,
  });
  const options = (found.data ?? []).map((c) => ({ value: c.id, label: label(c) }));
  if (current && current.id === value && !options.some((o) => o.value === current.id)) {
    options.unshift({ value: current.id, label: label(current) });
  }
  return (
    <Select
      label={t('assets.field.ci')}
      description={t('assets.field.ciHint')}
      placeholder={t('assets.field.ciPlaceholder')}
      searchable
      clearable
      value={value || null}
      onChange={(id) => onChange(id ?? '')}
      searchValue={search}
      onSearchChange={setSearch}
      filter={({ options: all }) => all}
      nothingFoundMessage={q.trim().length >= 2 && found.isSuccess ? t('assets.field.ciNotFound') : undefined}
      data={options}
    />
  );
}
