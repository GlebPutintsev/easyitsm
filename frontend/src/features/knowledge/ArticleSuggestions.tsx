import { Anchor, Paper, Stack, Text } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { IconBulb } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { useArticleSuggestions } from './api';

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
    <Paper withBorder radius="md" p="sm" bg="var(--mantine-color-yellow-light)">
      <Stack gap={6}>
        <Text size="sm" fw={500}>
          <IconBulb size={16} style={{ verticalAlign: 'text-bottom' }} /> {t('kb.suggestions.title')}
        </Text>
        {items.map((a) => (
          <div key={a.id}>
            <Anchor component={Link} to={`/${space}/kb/${a.id}`} target="_blank" size="sm" fw={500}>
              {a.title}
            </Anchor>
            {a.snippet && (
              <Text size="xs" c="dimmed" lineClamp={1}>
                {a.snippet}
              </Text>
            )}
          </div>
        ))}
      </Stack>
    </Paper>
  );
}
