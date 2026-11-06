import { Anchor, Badge, Group, Stack, Text } from '@mantine/core';
import { IconBulb } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { useKnownErrors, type KnownError } from './api';
import classes from './problems.module.css';

/**
 * Подсказка агенту в карточке инцидента (PRB-03, INC-05): опубликованные известные ошибки по КЕ, услуге,
 * категории и теме. Ничего не нашлось или нет доступа — блок не показывается.
 */
export function KnownErrorsHint({ ticketId }: { ticketId: string }) {
  const { t } = useTranslation();
  const list = useKnownErrors({ ticketId }, !!ticketId);
  const items = (list.data ?? []).slice(0, 3);
  if (items.length === 0) return null;
  return (
    <Stack gap={8} mb="md" aria-label={t('problems.hint.title')} role="region">
      <Group gap={6}>
        <IconBulb size={16} color="var(--ei-warn)" aria-hidden="true" />
        <Text size="xs" fw={700} c="var(--ei-text-2)" tt="uppercase" style={{ letterSpacing: '0.08em' }}>
          {t('problems.hint.title')}
        </Text>
      </Group>
      {items.map((k) => (
        <KnownErrorCard key={k.id} knownError={k} compact />
      ))}
    </Stack>
  );
}

export function KnownErrorCard({ knownError: k, compact, showStatus }: { knownError: KnownError; compact?: boolean; showStatus?: boolean }) {
  const { t } = useTranslation();
  return (
    <div className={`${classes.knownError} ${k.fixed ? classes.knownErrorFixed : ''}`}>
      <Group justify="space-between" gap={6} wrap="nowrap" align="flex-start">
        <Text size="sm" fw={600} style={{ overflowWrap: 'anywhere' }}>
          {k.title}
        </Text>
        <Group gap={4} wrap="nowrap">
          {k.fixed && (
            <Badge size="xs" variant="outline" color="green">
              {t('problems.knownError.fixed')}
            </Badge>
          )}
          {showStatus && !k.published && (
            <Badge size="xs" variant="light" color="gray">
              {t('problems.knownError.draft')}
            </Badge>
          )}
        </Group>
      </Group>
      {!compact && k.symptoms && (
        <Text size="sm" c="dimmed" mt={4} className={classes.preWrap}>
          {k.symptoms}
        </Text>
      )}
      {k.workaround && (
        <div className={classes.workaround}>
          <Text span size="xs" fw={700}>
            {t('problems.hint.workaround')}:{' '}
          </Text>
          {k.workaround}
        </div>
      )}
      {k.problemNumber && (
        <Anchor component={Link} to={`/agent/problems/${k.problemNumber}`} size="xs" mt={6} display="inline-block">
          {t('problems.knownError.problem', { number: k.problemNumber })}
        </Anchor>
      )}
    </div>
  );
}
