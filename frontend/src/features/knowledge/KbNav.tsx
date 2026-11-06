import { TextInput } from '@mantine/core';
import { IconSearch } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router';
import { useCategories } from './api';
import './knowledge.css';

interface Props {
  /** Корень раздела: /portal/kb или /agent/kb */
  base: string;
  /** Выбранный раздел; пустая строка — «Все темы» */
  categoryId: string;
  /** Поиск на странице списка: значение и изменение. На странице статьи поиск уводит в список. */
  q?: string;
  onSearch?: (q: string) => void;
  searchPlaceholder?: string;
}

/**
 * Левая колонка базы знаний (DESIGN.md): поиск по статьям и разделы. На средней ширине складывается
 * в строку над статьёй, на узкой — над списком.
 */
export function KbNav({ base, categoryId, q, onSearch, searchPlaceholder }: Props) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const categories = useCategories();
  const [draft, setDraft] = useState('');
  const placeholder = searchPlaceholder ?? t('kb.nav.search');
  const href = (id: string) => (id ? `${base}?category=${encodeURIComponent(id)}` : base);

  return (
    <nav className="kb-nav" aria-label={t('kb.nav.label')}>
      <form
        className="kb-nav-search"
        role="search"
        onSubmit={(e) => {
          e.preventDefault();
          if (!onSearch && draft.trim()) navigate(`${base}?q=${encodeURIComponent(draft.trim())}`);
        }}
      >
        <TextInput
          value={onSearch ? (q ?? '') : draft}
          onChange={(e) => (onSearch ? onSearch(e.currentTarget.value) : setDraft(e.currentTarget.value))}
          placeholder={placeholder}
          aria-label={placeholder}
          leftSection={<IconSearch size={16} aria-hidden />}
          type="search"
        />
      </form>
      <div className="kb-nav-list">
        <Link className="kb-cat" to={href('')} aria-current={categoryId === '' && onSearch ? 'true' : undefined}>
          {t('kb.portal.allCategories')}
        </Link>
        {(categories.data ?? []).map((c) => (
          <Link key={c.id} className="kb-cat" to={href(c.id)} aria-current={c.id === categoryId ? 'true' : undefined}>
            {c.name}
          </Link>
        ))}
      </div>
    </nav>
  );
}
