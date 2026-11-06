import { Anchor, Avatar, Badge, Button, Divider, Group, Paper, Select, Stack, Text, Tooltip } from '@mantine/core';
import { IconMail, IconPhone, IconStar } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { TicketAssetsPanel } from '@/features/assets/TicketAssetsPanel';
import { priorityOf } from '../api/domain';
import { useQueryClient } from '@tanstack/react-query';
import { ticketsApi } from '../api';
import { ticketKeys, useDirectory, useRequesterTickets, useUpdateTicket } from '../api/hooks';
import type { Level, Ticket, TicketPatch } from '../api/model';
import { PriorityBadge, SlaBadge, StatusBadge } from '../ui/badges';
import { useNow } from '../ui/useNow';
import { showError, showUndoToast } from '../ui/undoToast';

const LEVELS: Level[] = ['high', 'medium', 'low'];

/**
 * Правая панель карточки (10.7): поля меняются прямо на месте с автосохранением и отменой,
 * таймеры SLA, заявитель и его последние заявки, связи и наблюдатели.
 */
export function TicketSidePanel({ ticket }: { ticket: Ticket }) {
  const { t } = useTranslation();
  const now = useNow();
  const directory = useDirectory().data;
  const update = useUpdateTicket(ticket);
  const qc = useQueryClient();
  const recent = useRequesterTickets(ticket);
  const readOnly = !ticket.permissions.edit;

  /** Изменение поля с тостом «Отменить»: отмена — обратный PATCH с новой версией записи. */
  const change = (patch: TicketPatch, undo: TicketPatch, label: string) =>
    update.mutate(patch, {
      onSuccess: (updated) =>
        showUndoToast({
          message: t('workspace.side.changed', { field: label }),
          onUndo: async () => {
            try {
              const reverted = await ticketsApi.update(updated.id, undo, updated.version);
              qc.setQueryData(ticketKeys.detail(reverted.number), reverted);
              void qc.invalidateQueries({ queryKey: ticketKeys.activity(reverted.id) });
              void qc.invalidateQueries({ queryKey: ['tickets', 'list'] });
            } catch (e) {
              showError(e);
            }
          },
        }),
      onError: showError,
    });

  const agentsInGroup = (directory?.agents ?? []).filter((a) => !ticket.group || a.groupIds.includes(ticket.group.id));
  const field = (label: string, node: React.ReactNode) => (
    <div>
      <Text size="xs" c="dimmed" fw={500} mb={2}>
        {label}
      </Text>
      {node}
    </div>
  );
  const matrixPriority = priorityOf(ticket.impact, ticket.urgency);

  return (
    <Stack gap="md">
      <Paper withBorder p="sm" radius="md">
        <Stack gap="sm">
          <Group justify="space-between">
            <StatusBadge status={ticket.status} />
            <Group gap={6}>
              <PriorityBadge priority={ticket.priority} />
              {ticket.priorityManual && (
                <Tooltip label={t('workspace.side.priorityManual', { matrix: matrixPriority })}>
                  <Badge size="xs" variant="outline" color="gray">
                    {t('workspace.side.manual')}
                  </Badge>
                </Tooltip>
              )}
            </Group>
          </Group>
          {ticket.slaTimers.length > 0 && (
            <Stack gap={4}>
              {ticket.slaTimers.map((timer) => (
                <SlaBadge key={timer.kind} timer={timer} now={now} startAt={ticket.createdAt} withKind />
              ))}
            </Stack>
          )}
          {ticket.status.category === 'pending' && ticket.pendingUntil && (
            <Text size="xs" c="dimmed">
              {t('workspace.side.pendingUntil', {
                reason: directory?.pendingReasons.find((r) => r.id === ticket.pendingReason)?.name ?? ticket.pendingReason ?? '',
                date: new Date(ticket.pendingUntil).toLocaleString(),
              })}
            </Text>
          )}
          <Divider />
          <Group grow gap="xs">
            {field(
              t('workspace.field.impact'),
              <Select
                size="xs"
                disabled={readOnly}
                value={ticket.impact}
                data={LEVELS.map((l) => ({ value: l, label: t(`workspace.value.${l}`) }))}
                allowDeselect={false}
                onChange={(v) => v && v !== ticket.impact && change({ impact: v as Level }, { impact: ticket.impact }, t('workspace.field.impact'))}
                aria-label={t('workspace.field.impact')}
              />,
            )}
            {field(
              t('workspace.field.urgency'),
              <Select
                size="xs"
                disabled={readOnly}
                value={ticket.urgency}
                data={LEVELS.map((l) => ({ value: l, label: t(`workspace.value.${l}`) }))}
                allowDeselect={false}
                onChange={(v) => v && v !== ticket.urgency && change({ urgency: v as Level }, { urgency: ticket.urgency }, t('workspace.field.urgency'))}
                aria-label={t('workspace.field.urgency')}
              />,
            )}
          </Group>
          {field(
            t('workspace.field.group'),
            <Select
              size="xs"
              disabled={readOnly || !ticket.permissions.assign}
              searchable
              value={ticket.group?.id ?? null}
              data={(directory?.groups ?? []).map((g) => ({ value: g.id, label: g.name }))}
              allowDeselect={false}
              onChange={(v) => v && v !== ticket.group?.id && change({ groupId: v }, { groupId: ticket.group?.id ?? null }, t('workspace.field.group'))}
              aria-label={t('workspace.field.group')}
            />,
          )}
          {field(
            t('workspace.field.assignee'),
            <Group gap={6} wrap="nowrap" align="center">
              <Select
                size="xs"
                style={{ flex: 1 }}
                disabled={readOnly || !ticket.permissions.assign}
                searchable
                clearable
                placeholder={t('workspace.unassigned')}
                value={ticket.assignee?.id ?? null}
                data={agentsInGroup.map((a) => ({ value: a.id, label: a.name }))}
                onChange={(v) => v !== (ticket.assignee?.id ?? null) && change({ assigneeId: v }, { assigneeId: ticket.assignee?.id ?? null }, t('workspace.field.assignee'))}
                aria-label={t('workspace.field.assignee')}
              />
              {directory && ticket.assignee?.id !== directory.me.id && ticket.permissions.assign && (
                <Button size="compact-xs" variant="subtle" onClick={() => change({ assigneeId: directory.me.id }, { assigneeId: ticket.assignee?.id ?? null }, t('workspace.field.assignee'))}>
                  {t('workspace.side.assignMe')}
                </Button>
              )}
            </Group>,
          )}
          {field(
            t('workspace.field.service'),
            <Select
              size="xs"
              disabled={readOnly}
              searchable
              clearable
              value={ticket.service?.id ?? null}
              data={(directory?.services ?? []).map((s) => ({ value: s.id, label: s.name }))}
              onChange={(v) => v !== (ticket.service?.id ?? null) && change({ serviceId: v }, { serviceId: ticket.service?.id ?? null }, t('workspace.field.service'))}
              aria-label={t('workspace.field.service')}
            />,
          )}
          {field(
            t('workspace.field.category'),
            <Select
              size="xs"
              disabled={readOnly}
              searchable
              clearable
              value={ticket.category?.id ?? null}
              data={(directory?.categories ?? []).map((c) => ({ value: c.id, label: c.name }))}
              onChange={(v) => v !== (ticket.category?.id ?? null) && change({ categoryId: v }, { categoryId: ticket.category?.id ?? null }, t('workspace.field.category'))}
              aria-label={t('workspace.field.category')}
            />,
          )}
        </Stack>
      </Paper>

      <Paper withBorder p="sm" radius="md">
        <Text size="xs" c="dimmed" fw={500} mb={6}>
          {t('workspace.side.requester')}
        </Text>
        <Group gap="sm" wrap="nowrap" mb={6}>
          <Avatar name={ticket.requester.name} color="initials" radius="xl" />
          <div style={{ minWidth: 0 }}>
            <Group gap={4} wrap="nowrap">
              <Text size="sm" fw={600} lineClamp={1}>
                {ticket.requester.name}
              </Text>
              {ticket.requesterInfo.vip && (
                <Tooltip label={t('workspace.side.vip')}>
                  <IconStar size={14} color="var(--ei-warn)" fill="var(--ei-warn)" aria-label={t('workspace.side.vip')} />
                </Tooltip>
              )}
            </Group>
            {ticket.requesterInfo.department && (
              <Text size="xs" c="dimmed">
                {ticket.requesterInfo.department}
              </Text>
            )}
          </div>
        </Group>
        <Stack gap={2}>
          {ticket.requester.email && (
            <Anchor size="xs" href={`mailto:${ticket.requester.email}`}>
              <Group gap={4} wrap="nowrap">
                <IconMail size={12} />
                {ticket.requester.email}
              </Group>
            </Anchor>
          )}
          {ticket.requesterInfo.phone && (
            <Anchor size="xs" href={`tel:${ticket.requesterInfo.phone.replace(/[^\d+]/g, '')}`}>
              <Group gap={4} wrap="nowrap">
                <IconPhone size={12} />
                {ticket.requesterInfo.phone}
              </Group>
            </Anchor>
          )}
        </Stack>
        {(recent.data?.length ?? 0) > 0 && (
          <>
            <Text size="xs" c="dimmed" fw={500} mt="sm" mb={4}>
              {t('workspace.side.recent')}
            </Text>
            <Stack gap={4}>
              {recent.data?.map((r) => (
                <Group key={r.id} gap={6} wrap="nowrap">
                  <Anchor component={Link} to={`/agent/tickets/${r.number}`} size="xs" ff="monospace">
                    {r.number}
                  </Anchor>
                  <Text size="xs" lineClamp={1} style={{ flex: 1 }}>
                    {r.title}
                  </Text>
                  <StatusBadge status={r.status} />
                </Group>
              ))}
            </Stack>
          </>
        )}
      </Paper>

      <TicketAssetsPanel ticketId={ticket.id} requesterId={ticket.requester.id} />

      {(ticket.links.length > 0 || ticket.watchers.length > 0) && (
        <Paper withBorder p="sm" radius="md">
          {ticket.links.length > 0 && (
            <>
              <Text size="xs" c="dimmed" fw={500} mb={4}>
                {t('workspace.side.links')}
              </Text>
              <Stack gap={4} mb="sm">
                {ticket.links.map((l) => (
                  <Group key={l.id} gap={6} wrap="nowrap">
                    <Text size="xs" c="dimmed" w={110}>
                      {t(`workspace.link.${l.kind}`)}
                    </Text>
                    <Anchor component={Link} to={`/agent/tickets/${l.ticket.number}`} size="xs" ff="monospace">
                      {l.ticket.number}
                    </Anchor>
                    <Text size="xs" lineClamp={1}>
                      {l.ticket.title}
                    </Text>
                  </Group>
                ))}
              </Stack>
            </>
          )}
          {ticket.watchers.length > 0 && (
            <>
              <Text size="xs" c="dimmed" fw={500} mb={4}>
                {t('workspace.side.watchers')}
              </Text>
              <Avatar.Group>
                {ticket.watchers.map((w) => (
                  <Tooltip key={w.id} label={w.name}>
                    <Avatar name={w.name} color="initials" radius="xl" size="sm" />
                  </Tooltip>
                ))}
              </Avatar.Group>
            </>
          )}
        </Paper>
      )}
    </Stack>
  );
}
