import { Button, Card, Group, SimpleGrid, Skeleton, Stack, Text, Title } from '@mantine/core';
import { useQueries } from '@tanstack/react-query';
import { IconAlertTriangle, IconInbox, IconPlayerTrackNext, IconUser, IconUsersGroup } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { ticketsApi } from './api';
import { useViews } from './api/hooks';

const TILES = [
  { id: 'mine', icon: IconUser, color: 'blue' },
  { id: 'unassigned', icon: IconInbox, color: 'teal' },
  { id: 'overdue', icon: IconAlertTriangle, color: 'red' },
  { id: 'group', icon: IconUsersGroup, color: 'grape' },
] as const;

/** Главная рабочего места: сколько работы в каждом встроенном представлении, переход в очередь в один клик. */
export function AgentHome() {
  const { t } = useTranslation();
  const { views, ready } = useViews();
  const counts = useQueries({
    queries: TILES.map((tile) => {
      const view = views.find((v) => v.id === tile.id);
      return {
        queryKey: ['tickets', 'home-count', tile.id, view?.filter],
        queryFn: ({ signal }: { signal: AbortSignal }) =>
          ticketsApi.list({ filter: view?.filter ?? {}, sort: view?.sort ?? { field: 'sla', dir: 'asc' }, limit: 1 }, signal),
        enabled: ready && Boolean(view),
        staleTime: 30_000,
      };
    }),
  });

  return (
    <Stack gap="lg">
      <Group justify="space-between">
        <Title order={2}>{t('agent.title')}</Title>
        <Button component={Link} to="/agent/queues/mine" leftSection={<IconPlayerTrackNext size={16} />} variant="light">
          {t('workspace.home.openQueue')}
        </Button>
      </Group>
      <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }}>
        {TILES.map((tile, i) => {
          const q = counts[i];
          return (
            <Card key={tile.id} component={Link} to={`/agent/queues/${tile.id}`} withBorder padding="lg" className="ew-home-tile">
              <Group justify="space-between" align="flex-start">
                <Text size="sm" c="dimmed" fw={500}>
                  {t(`workspace.views.builtin.${tile.id}`)}
                </Text>
                <tile.icon size={20} color={`var(--mantine-color-${tile.color}-6)`} />
              </Group>
              {q?.isSuccess ? (
                <Text fz={32} fw={700} mt="xs" c={tile.id === 'overdue' && q.data.total > 0 ? 'red' : undefined}>
                  {q.data.total < 0 ? '—' : q.data.total}
                </Text>
              ) : (
                <Skeleton height={36} width={60} mt="xs" />
              )}
            </Card>
          );
        })}
      </SimpleGrid>
      <Text size="sm" c="dimmed">
        {t('workspace.home.hint')}
      </Text>
    </Stack>
  );
}
