import { Group, Stack, Text, Timeline } from '@mantine/core';
import { IconCheck, IconClock, IconHourglass, IconMinus, IconX } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import type { Route, RouteStep } from './api';
import { usePersonName } from '@/shared/api/people';

const STEP_COLOR: Record<RouteStep['state'], string> = {
  pending: 'var(--ei-wait)',
  waiting: 'var(--ei-border-strong)',
  approved: 'var(--ei-ok)',
  rejected: 'var(--ei-danger)',
  skipped: 'var(--ei-border-strong)',
  cancelled: 'var(--ei-border-strong)',
};

const ROUTE_TONE: Record<string, string | undefined> = { approved: 'ok', rejected: 'danger', pending: 'wait' };

/** Маршрут согласования заявки: шаги, режим, кто и как решил (10.5, 10.8). */
export function ApprovalRoute({ route }: { route: Route }) {
  const { t } = useTranslation();
  return (
    <section className="ep-card" style={{ padding: 16 }}>
      <Stack gap="sm">
        <Group justify="space-between">
          <h2 className="ep-h2" style={{ fontSize: 15, lineHeight: '22px' }}>{t('approvals.route')}</h2>
          <span className="ep-chip" data-tone={ROUTE_TONE[route.state]}>
            {t(`approvals.routeState.${route.state}`)}
          </span>
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
    </section>
  );
}

function ApproverLine({ approverId, decision, comment }: { approverId: string; decision: string; comment: string | null }) {
  const { t } = useTranslation();
  const name = usePersonName(approverId);
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
