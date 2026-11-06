import { Select } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { UUID_RE, usePeopleSearch, usePerson } from '@/shared/api/people';

interface Props {
  label: string;
  description?: string;
  value: string | null;
  onChange: (id: string | null) => void;
  error?: string;
  placeholder?: string;
  /** Не предлагать этих людей (например, себя при делегировании) */
  exclude?: string[];
}

/**
 * Выбор сотрудника по имени или почте из справочника /people. Поиск на сервере, поэтому
 * список не фильтруется в браузере. Вставленный идентификатор тоже принимается.
 */
export function PersonPicker({ label, description, value, onChange, error, placeholder, exclude = [] }: Props) {
  const { t } = useTranslation();
  const [search, setSearch] = useState('');
  const [debounced] = useDebouncedValue(search, 250);
  const found = usePeopleSearch(debounced);
  const selected = usePerson(value);

  const options = (found.data ?? [])
    .filter((p) => !exclude.includes(p.id))
    .map((p) => ({ value: p.id, label: p.email ? `${p.displayName} · ${p.email}` : p.displayName }));
  // Выбранный человек всегда в списке, иначе Select не покажет его имя.
  if (value && !options.some((o) => o.value === value)) {
    options.unshift({ value, label: selected.data?.displayName ?? value });
  }
  const pasted = search.trim();
  if (UUID_RE.test(pasted) && !options.some((o) => o.value === pasted)) {
    options.push({ value: pasted, label: pasted });
  }
  const searching = debounced.trim().length >= 2;

  return (
    <Select
      label={label}
      description={description}
      placeholder={placeholder ?? t('people.placeholder')}
      searchable
      clearable
      data={options}
      value={value}
      onChange={(id) => onChange(id)}
      searchValue={search}
      onSearchChange={setSearch}
      filter={({ options: all }) => all}
      nothingFoundMessage={searching && !found.isFetching ? t('people.notFound') : t('people.typeToSearch')}
      error={error}
    />
  );
}
