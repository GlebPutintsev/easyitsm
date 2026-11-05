import { Badge, Button, Card, Group, SimpleGrid, Stack, Text, TextInput, Title } from '@mantine/core';
import { IconAlertTriangle, IconSearch, IconShoppingCart } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import { useActiveAnnouncements } from './announcements';

const kindColor = { info: 'blue', maintenance: 'yellow', outage: 'red' } as const;

/**
 * Главная портала (10.4). Сейчас — поиск, две главные кнопки и объявления.
 * Популярные услуги, мои заявки и блок «Требует вашего ответа» добавит тред портала.
 */
export function PortalHome() {
  const { t, i18n } = useTranslation();
  const announcements = useActiveAnnouncements();
  const items = announcements.data?.items ?? [];
  const state = listStateOf(announcements, items.length);
  const dateFormat = new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeStyle: 'short' });

  return (
    <Stack maw={880} mx="auto" gap="xl" py="md">
      <TextInput
        size="lg"
        radius="xl"
        placeholder={t('portal.searchPlaceholder')}
        leftSection={<IconSearch size={20} />}
        aria-label={t('portal.searchPlaceholder')}
      />
      <SimpleGrid cols={{ base: 1, xs: 2 }}>
        <Button size="lg" variant="light" leftSection={<IconAlertTriangle />}>
          {t('portal.reportProblem')}
        </Button>
        <Button size="lg" variant="light" leftSection={<IconShoppingCart />}>
          {t('portal.orderService')}
        </Button>
      </SimpleGrid>

      <Stack gap="sm">
        <Title order={3}>{t('portal.announcements')}</Title>
        {state ? (
          <ListState
            state={state}
            title={state === 'empty' ? t('portal.noAnnouncements') : undefined}
            error={announcements.error}
            onRetry={() => void announcements.refetch()}
            rows={2}
          />
        ) : (
          items.map((a) => (
            <Card key={a.id} withBorder padding="md">
              <Group justify="space-between" mb={4}>
                <Text fw={600}>{a.title}</Text>
                <Badge color={kindColor[a.kind]} variant="light">
                  {t(`portal.kind.${a.kind}`)}
                </Badge>
              </Group>
              <Text size="sm" c="dimmed">
                {dateFormat.format(new Date(a.startsAt))}
                {a.endsAt ? ` — ${dateFormat.format(new Date(a.endsAt))}` : ''}
              </Text>
              {a.body && (
                <Text size="sm" mt="xs">
                  {a.body}
                </Text>
              )}
            </Card>
          ))
        )}
      </Stack>
    </Stack>
  );
}
