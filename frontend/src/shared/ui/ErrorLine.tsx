import { Alert, Code, Text } from '@mantine/core';
import { IconAlertCircle } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@/shared/api/client';

/** Ошибка действия одной строкой: текст сервера и код трассировки для обращения в поддержку (UI-06). */
export function ErrorLine({ error }: { error: unknown }) {
  const { t } = useTranslation();
  if (!error) return null;
  const e = error instanceof ApiError ? error : null;
  return (
    <Alert color="red" icon={<IconAlertCircle />} role="alert" p="xs">
      <Text size="sm">{e?.message ?? t('states.errorText')}</Text>
      {e?.traceId && (
        <Text size="xs" c="dimmed">
          {t('states.traceId')}: <Code>{e.traceId}</Code>
        </Text>
      )}
    </Alert>
  );
}
