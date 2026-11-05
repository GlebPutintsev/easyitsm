import { Autocomplete, Text } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { UUID_RE, shortId, useUserSearch } from './users';

interface Props {
  label: string;
  description?: string;
  value: string | null;
  onChange: (id: string | null) => void;
  error?: string;
  placeholder?: string;
}

/**
 * Выбор сотрудника: поиск по справочнику пользователей или вставка идентификатора.
 * Поиск заработает вместе с модулем identity, ввод идентификатора работает всегда.
 */
export function UserPicker({ label, description, value, onChange, error, placeholder }: Props) {
  const { t } = useTranslation();
  const [text, setText] = useState(value ?? '');
  const [debounced] = useDebouncedValue(text, 250);
  const search = useUserSearch(debounced);
  const users = search.data ?? [];

  return (
    <Autocomplete
      label={label}
      description={description}
      placeholder={placeholder ?? t('catalog.users.placeholder')}
      value={text}
      error={error ?? (text && !UUID_RE.test(text) && !users.length && debounced === text ? t('catalog.users.notFound') : undefined)}
      data={users.map((u) => ({ value: u.id, label: `${u.displayName}${u.email ? ` · ${u.email}` : ''}` }))}
      onChange={(v) => {
        setText(v);
        onChange(UUID_RE.test(v.trim()) ? v.trim() : null);
      }}
      onOptionSubmit={(id) => {
        setText(id);
        onChange(id);
      }}
      rightSection={value ? <Text size="xs" c="dimmed">{shortId(value)}</Text> : null}
      rightSectionWidth={value ? 80 : undefined}
    />
  );
}
