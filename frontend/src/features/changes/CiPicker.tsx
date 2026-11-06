import { MultiSelect } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { type CiRef, useCiSearch } from './api';

interface Props {
  label: string;
  description?: string;
  value: string[];
  onChange: (ids: string[]) => void;
  /** Только услуги (бизнес- и технические) или любые КЕ */
  services?: boolean;
  /** Уже известные имена (из карточки изменения), чтобы выбранное показывалось по имени */
  known?: CiRef[];
  error?: string;
  disabled?: boolean;
}

/** Выбор КЕ или услуг из CMDB с поиском на сервере (CHG-06). */
export function CiPicker({ label, description, value, onChange, services = false, known = [], error, disabled }: Props) {
  const { t } = useTranslation();
  const [search, setSearch] = useState('');
  const [debounced] = useDebouncedValue(search, 250);
  const found = useCiSearch(debounced.trim(), services ? 'business_service,technical_service' : undefined);
  const [names, setNames] = useState<Record<string, string>>({});
  const options = new Map<string, string>();
  for (const k of known) options.set(k.id, k.name);
  for (const [id, name] of Object.entries(names)) options.set(id, name);
  for (const c of found.data ?? []) options.set(c.id, `${c.name} · ${c.className}`);
  for (const id of value) if (!options.has(id)) options.set(id, id);

  return (
    <MultiSelect
      label={label}
      description={description}
      error={error}
      disabled={disabled}
      searchable
      clearable
      searchValue={search}
      onSearchChange={setSearch}
      filter={({ options: o }) => o}
      nothingFoundMessage={found.isFetching ? t('states.loading') : t('changes.ci.nothing')}
      placeholder={value.length ? undefined : t(services ? 'changes.ci.searchServices' : 'changes.ci.search')}
      data={[...options].map(([v, l]) => ({ value: v, label: l }))}
      value={value}
      onChange={(ids) => {
        const next = { ...names };
        for (const id of ids) {
          const label = options.get(id);
          if (label) next[id] = label;
        }
        setNames(next);
        onChange(ids);
      }}
    />
  );
}
