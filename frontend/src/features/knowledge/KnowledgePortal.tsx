import { Title } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router';
import { useCategories } from './api';
import { ArticleList } from './ArticleList';
import { KbNav } from './KbNav';
import './knowledge.css';

/**
 * База знаний на портале: разделы и поиск слева (как на странице статьи), справа опубликованные статьи,
 * видимые пользователю.
 */
export function KnowledgePortal() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const q = params.get('q') ?? '';
  const category = params.get('category') ?? '';
  const [debounced] = useDebouncedValue(q, 300);
  const categories = useCategories();
  const categoryName = categories.data?.find((c) => c.id === category)?.name;

  const setQ = (value: string) =>
    setParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        if (value) next.set('q', value);
        else next.delete('q');
        return next;
      },
      { replace: true },
    );

  return (
    <div className="kb-shell">
      <div className="kb-page" data-variant="list">
        <KbNav
          base="/portal/kb"
          categoryId={category}
          q={q}
          onSearch={setQ}
        />
        <div className="kb-main">
          <Title order={1} mb="md">
            {categoryName ?? t('kb.title')}
          </Title>
          <ArticleList
            filter={{ view: 'portal', q: debounced || undefined, categoryId: category || undefined }}
            linkTo={(id) => `/portal/kb/${id}`}
            filtered={!!debounced || !!category}
            onResetFilters={() => setParams({}, { replace: true })}
            emptyTitle={t('kb.portal.empty')}
          />
        </div>
      </div>
    </div>
  );
}
