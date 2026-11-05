import { Chip, Group, Stack, TextInput, Title } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { IconSearch } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router';
import { useCategories } from './api';
import { ArticleList } from './ArticleList';

/** База знаний на портале: поиск по опубликованным статьям, видимым пользователю, и категории. */
export function KnowledgePortal() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const q = params.get('q') ?? '';
  const category = params.get('category') ?? '';
  const [debounced] = useDebouncedValue(q, 300);
  const categories = useCategories();

  const set = (key: string, value: string) =>
    setParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        if (value) next.set(key, value);
        else next.delete(key);
        return next;
      },
      { replace: true },
    );

  return (
    <Stack maw={880} mx="auto" gap="lg" py="md">
      <Title order={2}>{t('kb.title')}</Title>
      <TextInput
        size="lg"
        radius="xl"
        value={q}
        onChange={(e) => set('q', e.currentTarget.value)}
        placeholder={t('kb.portal.searchPlaceholder')}
        aria-label={t('kb.portal.searchPlaceholder')}
        leftSection={<IconSearch size={20} />}
      />
      {(categories.data?.length ?? 0) > 0 && (
        <Chip.Group value={category} onChange={(v) => set('category', v as string)}>
          <Group gap="xs">
            <Chip value="">{t('kb.portal.allCategories')}</Chip>
            {categories.data!.map((c) => (
              <Chip key={c.id} value={c.id}>
                {c.name}
              </Chip>
            ))}
          </Group>
        </Chip.Group>
      )}
      <ArticleList
        filter={{ view: 'portal', q: debounced || undefined, categoryId: category || undefined }}
        linkTo={(id) => `/portal/kb/${id}`}
        filtered={!!debounced || !!category}
        onResetFilters={() => setParams({}, { replace: true })}
        emptyTitle={t('kb.portal.empty')}
      />
    </Stack>
  );
}
