import { Alert, Anchor, Badge, Button, Card, Group, SimpleGrid, Stack, Table, Text, Title } from '@mantine/core';
import { IconAlertTriangle, IconChecklist, IconMessageCircleQuestion, IconShoppingCart } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import { useApprovalCount, usePopularItems } from '@/features/catalog/api';
import { ItemTile } from '@/features/catalog/ItemTile';
import { useActiveAnnouncements } from './announcements';
import { PortalSearch } from './PortalSearch';
import { StatusBadge } from './StatusBadge';
import { useRecentTickets } from './tickets';

const kindColor = { info: 'blue', maintenance: 'yellow', outage: 'red' } as const;

/**
 * Главная портала (10.4, PRT-01): поиск, две главные кнопки, «Требует вашего ответа», популярные услуги,
 * мои заявки и объявления.
 */
export function PortalHome() {
  const { t } = useTranslation();
  return (
    <Stack maw={960} mx="auto" gap="xl" py="md">
      <PortalSearch />
      <SimpleGrid cols={{ base: 1, xs: 2 }}>
        <Button size="lg" variant="light" color="orange" leftSection={<IconAlertTriangle />} component={Link} to="/portal/report">
          {t('portal.reportProblem')}
        </Button>
        <Button size="lg" variant="light" leftSection={<IconShoppingCart />} component={Link} to="/portal/catalog">
          {t('portal.orderService')}
        </Button>
      </SimpleGrid>
      <NeedsYou />
      <Popular />
      <RecentTickets />
      <Announcements />
    </Stack>
  );
}

/** «Требует вашего ответа»: показывается, только если есть заявки в ожидании или согласования. */
function NeedsYou() {
  const { t } = useTranslation();
  const waiting = useRecentTickets('waiting', 5);
  const approvals = useApprovalCount();
  const items = waiting.data ?? [];
  const n = approvals.data ?? 0;
  if (!items.length && !n) return null;
  return (
    <Alert variant="light" color="yellow" icon={<IconMessageCircleQuestion />} title={t('portal.needsYou')}>
      <Stack gap={6}>
        {items.map((x) => (
          <Group key={x.id} gap="xs" wrap="nowrap">
            <Anchor component={Link} to={`/portal/tickets/${x.id}`} size="sm" lineClamp={1}>
              {x.number} · {x.title}
            </Anchor>
            <StatusBadge status={x.status} />
          </Group>
        ))}
        {n > 0 && (
          <Anchor component={Link} to="/portal/approvals" size="sm">
            <Group gap={6}>
              <IconChecklist size={16} />
              {t('portal.approvalsWaiting', { count: n })}
            </Group>
          </Anchor>
        )}
      </Stack>
    </Alert>
  );
}

function Popular() {
  const { t } = useTranslation();
  const popular = usePopularItems();
  const items = popular.data ?? [];
  if (!popular.isPending && !items.length) return null;
  return (
    <Stack gap="sm">
      <Group justify="space-between">
        <Title order={3}>{t('portal.popular')}</Title>
        <Anchor component={Link} to="/portal/catalog" size="sm">
          {t('portal.allServices')}
        </Anchor>
      </Group>
      {popular.isPending || popular.error ? (
        <ListState state={popular.error ? 'error' : 'loading'} error={popular.error} rows={2} onRetry={() => void popular.refetch()} />
      ) : (
        <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }} spacing="sm">
          {items.map((i) => (
            <ItemTile key={i.id} item={i} compact />
          ))}
        </SimpleGrid>
      )}
    </Stack>
  );
}

function RecentTickets() {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const recent = useRecentTickets(undefined, 5);
  const items = recent.data ?? [];
  const state = listStateOf(recent, items.length);
  const dt = new Intl.DateTimeFormat(i18n.language, { dateStyle: 'short', timeStyle: 'short' });
  return (
    <Stack gap="sm">
      <Group justify="space-between">
        <Title order={3}>{t('nav.myTickets')}</Title>
        <Anchor component={Link} to="/portal/tickets" size="sm">
          {t('portal.allTickets')}
        </Anchor>
      </Group>
      {state ? (
        <ListState state={state} title={state === 'empty' ? t('portal.noTickets') : undefined} error={recent.error} onRetry={() => void recent.refetch()} rows={3} />
      ) : (
        <Card withBorder padding={0}>
          <Table highlightOnHover verticalSpacing="sm">
            <Table.Tbody>
              {items.map((x) => (
                <Table.Tr key={x.id} style={{ cursor: 'pointer' }} onClick={() => navigate(`/portal/tickets/${x.id}`)}>
                  <Table.Td w={120}>
                    <Text ff="monospace" size="sm" c="dimmed">
                      {x.number}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm" lineClamp={1}>
                      {x.title}
                    </Text>
                  </Table.Td>
                  <Table.Td w={150}>
                    <StatusBadge status={x.status} />
                  </Table.Td>
                  <Table.Td w={140} visibleFrom="sm">
                    <Text size="xs" c="dimmed">
                      {dt.format(new Date(x.updatedAt))}
                    </Text>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Card>
      )}
    </Stack>
  );
}

function Announcements() {
  const { t, i18n } = useTranslation();
  const announcements = useActiveAnnouncements();
  const items = announcements.data?.items ?? [];
  const state = listStateOf(announcements, items.length);
  const dateFormat = new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeStyle: 'short' });
  return (
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
  );
}
