import { Badge, Card, Group, Stack, Text, Timeline, Title } from '@mantine/core';
import { IconCheck, IconClock, IconHourglass, IconMinus, IconX } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import type { Route, RouteStep } from './api';
import { useUserName } from './users';

const STEP_COLOR: Record<RouteStep['state'], string> = {
  pending: 'blue',
  waiting: 'gray',
  approved: 'teal',
  rejected: 'red',
  skipped: 'gray',
  cancelled: 'gray',
};

/** Маршрут согласования заявки: шаги, режим, кто и как решил (10.5, 10.8). */
export function ApprovalRoute({ route }: { route: Route }) {
  const { t } = useTranslation();
  return (
    <Card withBorder padding="md">
      <Stack gap="sm">
        <Group justify="space-between">
          <Title order={4}>{t('approvals.route')}</Title>
          <Badge color={route.state === 'approved' ? 'teal' : route.state === 'rejected' ? 'red' : route.state === 'pending' ? 'violet' : 'gray'}>
            {t(`approvals.routeState.${route.state}`)}
          </Badge>
        </Group>
        <Timeline active={route.steps.findIndex((s) => s.state === 'pending' || s.state === 'waiting') - 1} bulletSize={24}>
          {route.steps.map((s) => (
            <Timeline.Item
              key={s.index}
              color={STEP_COLOR[s.state]}
              bullet={s.state === 'approved' ? <IconCheck size={14} /> : s.state === 'rejected' ? <IconX size={14} /> : s.state === 'pending' ? <IconHourglass size={14} /> : <IconClock size={14} />}
              title={
                <Group gap="xs">
                  <Text fw={600} size="sm">
                    {s.name}
                  </Text>
                  <Text size="xs" c="dimmed">
                    {t(`approvals.modes.${s.mode}`, { count: s.required })}
                  </Text>
                </Group>
              }
            >
              <Stack gap={2} mt={4}>
                {s.approvals.map((a) => (
                  <ApproverLine key={a.id} approverId={a.approverId} decision={a.decision} comment={a.comment} />
                ))}
              </Stack>
            </Timeline.Item>
          ))}
        </Timeline>
      </Stack>
    </Card>
  );
}

function ApproverLine({ approverId, decision, comment }: { approverId: string; decision: string; comment: string | null }) {
  const { t } = useTranslation();
  const name = useUserName(approverId);
  const Icon = decision === 'approved' ? IconCheck : decision === 'rejected' ? IconX : decision === 'pending' ? IconHourglass : IconMinus;
  return (
    <Group gap={6} wrap="nowrap" align="flex-start">
      <Icon size={14} style={{ marginTop: 3, flexShrink: 0 }} />
      <Text size="sm">
        {name} · {t(`approvals.decisions.${decision}`)}
        {comment ? ` — «${comment}»` : ''}
      </Text>
    </Group>
  );
}
