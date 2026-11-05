import { Alert, Button, Center, Code, Skeleton, Stack, Text } from '@mantine/core';
import { IconAlertCircle, IconFilterOff, IconInbox, IconLock } from '@tabler/icons-react';
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@/shared/api/client';

export type ListStateKind = 'loading' | 'empty' | 'error' | 'forbidden' | 'noResults';

interface Props {
  state: ListStateKind;
  title?: string;
  error?: unknown;
  onRetry?: () => void;
  onResetFilters?: () => void;
  action?: ReactNode;
  /** Количество строк скелетона */
  rows?: number;
}

/**
 * Обязательные состояния любого списка (UI-02): загрузка, пусто, ошибка, нет доступа,
 * нет результатов по фильтру. Скелетоны вместо спиннеров (UI-10).
 */
export function ListState({ state, title, error, onRetry, onResetFilters, action, rows = 5 }: Props) {
  const { t } = useTranslation();

  if (state === 'loading') {
    return (
      <Stack gap="xs" aria-busy="true" aria-label={t('states.loading')}>
        {Array.from({ length: rows }, (_, i) => (
          <Skeleton key={i} height={36} radius="sm" />
        ))}
      </Stack>
    );
  }

  if (state === 'error') {
    const traceId = error instanceof ApiError ? error.traceId : undefined;
    return (
      <Alert color="red" icon={<IconAlertCircle />} title={title ?? t('states.errorTitle')} role="alert">
        <Stack gap="xs">
          <Text size="sm">{error instanceof ApiError ? error.message : t('states.errorText')}</Text>
          {traceId && (
            <Text size="xs" c="dimmed">
              {t('states.traceId')}: <Code>{traceId}</Code>
            </Text>
          )}
          {onRetry && (
            <Button size="xs" variant="light" color="red" onClick={onRetry} w="fit-content">
              {t('actions.retry')}
            </Button>
          )}
        </Stack>
      </Alert>
    );
  }

  const config = {
    empty: { icon: IconInbox, text: title ?? t('states.empty') },
    forbidden: { icon: IconLock, text: title ?? t('states.forbidden') },
    noResults: { icon: IconFilterOff, text: title ?? t('states.noResults') },
  }[state];

  return (
    <Center py="xl">
      <Stack align="center" gap="xs">
        <config.icon size={40} stroke={1.4} color="var(--mantine-color-dimmed)" />
        <Text c="dimmed">{config.text}</Text>
        {state === 'noResults' && onResetFilters && (
          <Button size="xs" variant="subtle" onClick={onResetFilters}>
            {t('actions.resetFilters')}
          </Button>
        )}
        {action}
      </Stack>
    </Center>
  );
}

/** Выбирает состояние списка по результату запроса. null — показывать данные. */
export function listStateOf(query: { isPending: boolean; error: unknown }, itemCount: number, filtered = false): ListStateKind | null {
  if (query.isPending) return 'loading';
  if (query.error instanceof ApiError && query.error.status === 403) return 'forbidden';
  if (query.error) return 'error';
  if (itemCount === 0) return filtered ? 'noResults' : 'empty';
  return null;
}
