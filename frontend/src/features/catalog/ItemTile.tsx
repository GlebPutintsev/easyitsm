import { ActionIcon, Badge, Button, Card, Group, Stack, Text, Tooltip } from '@mantine/core';
import { IconClock, IconStar, IconStarFilled } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { type ItemCard, useToggleFavorite } from './api';
import { ServiceIcon } from './icons';

/** Карточка услуги (10.4): название, срок и цена, если заданы; избранное; «Заказать». */
export function ItemTile({ item, compact }: { item: ItemCard; compact?: boolean }) {
  const { t, i18n } = useTranslation();
  const favorite = useToggleFavorite();
  const to = `/portal/catalog/${item.id}`;
  const price =
    item.cost != null
      ? new Intl.NumberFormat(i18n.language, { style: 'currency', currency: item.currency ?? 'RUB', maximumFractionDigits: 0 }).format(item.cost)
      : null;

  return (
    <Card withBorder padding={compact ? 'sm' : 'md'} radius="md" h="100%">
      <Stack gap="xs" h="100%" justify="space-between">
        <Stack gap={6}>
          <Group justify="space-between" wrap="nowrap" align="flex-start">
            <Group gap="sm" wrap="nowrap" align="flex-start">
              <ServiceIcon name={item.icon} kind={item.kind} />
              <Text fw={600} component={Link} to={to} c="var(--mantine-color-text)" td="none" lineClamp={2}>
                {item.name}
              </Text>
            </Group>
            <Tooltip label={item.favorite ? t('catalog.unfavorite') : t('catalog.favorite')}>
              <ActionIcon
                variant="subtle"
                color="yellow"
                aria-label={item.favorite ? t('catalog.unfavorite') : t('catalog.favorite')}
                aria-pressed={item.favorite}
                onClick={() => favorite.mutate({ id: item.id, on: !item.favorite })}
                loading={favorite.isPending}
              >
                {item.favorite ? <IconStarFilled size={18} /> : <IconStar size={18} />}
              </ActionIcon>
            </Tooltip>
          </Group>
          {!compact && item.summary && (
            <Text size="sm" c="dimmed" lineClamp={3}>
              {item.summary}
            </Text>
          )}
        </Stack>
        <Group justify="space-between" gap="xs">
          <Group gap={6}>
            {item.leadTimeHours != null && (
              <Badge variant="light" color="gray" leftSection={<IconClock size={12} />}>
                {leadTime(t, item.leadTimeHours)}
              </Badge>
            )}
            {price && (
              <Badge variant="light" color="teal">
                {price}
              </Badge>
            )}
            {!compact && item.team && (
              <Badge variant="outline" color="gray">
                {item.team}
              </Badge>
            )}
          </Group>
          {!compact && (
            <Button size="xs" variant="light" component={Link} to={to}>
              {item.kind === 'incident' ? t('catalog.report') : t('catalog.order')}
            </Button>
          )}
        </Group>
      </Stack>
    </Card>
  );
}

type TFn = (key: string, options?: Record<string, unknown>) => string;

export function leadTime(t: TFn, hours: number): string {
  if (hours < 24) return t('catalog.leadHours', { count: hours });
  const days = Math.round(hours / 24);
  return t('catalog.leadDays', { count: days });
}
