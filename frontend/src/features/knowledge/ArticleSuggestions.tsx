import { Stack, Text } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { IconBulb } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { useArticleSuggestions } from './api';
import './knowledge.css';

/**
 * Подсказки статей по тексту обращения (INC-05, PRT-02): для формы заявки на портале и в рабочем месте.
 * Ничего не показывает, пока подходящих статей нет.
 */
export function ArticleSuggestions({ text, space = 'portal' }: { text: string; space?: 'portal' | 'agent' }) {
  const { t } = useTranslation();
  const [debounced] = useDebouncedValue(text, 400);
  const suggestions = useArticleSuggestions(debounced);
  const items = suggestions.data ?? [];
  if (items.length === 0) return null;

  return (
    <section className="kb-suggest" aria-live="polite">
      <Stack gap={6}>
        <Text size="sm" fw={600}>
          <IconBulb size={16} style={{ verticalAlign: 'text-bottom', color: 'var(--ei-accent-text)' }} aria-hidden />{' '}
          {t('kb.suggestions.title')}
        </Text>
        {items.map((a) => (
          <div key={a.id}>
            <Link to={`/${space}/kb/${a.id}`} target="_blank">
              <Text span size="sm" inherit>
                {a.title}
              </Text>
            </Link>
            {a.snippet && (
              <Text size="xs" c="var(--ei-text-2)" lineClamp={1}>
                {a.snippet}
              </Text>
            )}
          </div>
        ))}
      </Stack>
    </section>
  );
}
