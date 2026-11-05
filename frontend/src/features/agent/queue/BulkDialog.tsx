import { Alert, Badge, Button, Group, Modal, Radio, ScrollArea, Select, Stack, Table, Text, TextInput, Textarea } from '@mantine/core';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { IconArrowRight, IconInfoCircle } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ticketsApi } from '../api';
import { useDirectory } from '../api/hooks';
import type { BulkAction, Priority, TicketSummary } from '../api/model';
import { AssignFields } from '../ui/AssignFields';
import { showError, showUndoToast } from '../ui/undoToast';

export type BulkIntent =
  | { kind: 'assign'; groupId?: string | null; assigneeId?: string | null; direct?: boolean }
  | { kind: 'priority'; priority: Priority }
  /**
   * В строках очереди нет списка переходов, поэтому массово предлагаются стандартные статусы по категории.
   * Сервер проверяет переход для каждой заявки и пропускает недоступные.
   */
  | { kind: 'transition'; to: BulkTransition }
  | { kind: 'merge' };

export type BulkTransition = 'in_progress' | 'pending' | 'resolved' | 'closed' | 'cancelled';
export const BULK_TRANSITIONS: BulkTransition[] = ['in_progress', 'pending', 'resolved', 'closed', 'cancelled'];

interface Props {
  intent: BulkIntent | null;
  tickets: TicketSummary[];
  onClose: () => void;
  onApplied: () => void;
}

/**
 * Массовое действие с предпросмотром (TCK-12): параметры, список «было → станет» и пропуски.
 * После применения — тост «Отменить» на 10 секунд.
 */
export function BulkDialog({ intent, tickets, onClose, onApplied }: Props) {
  const { t } = useTranslation();
  return (
    <Modal opened={intent !== null} onClose={onClose} size="lg" title={intent ? t(`workspace.bulk.title.${intent.kind}`, { count: tickets.length }) : ''}>
      {intent && <BulkDialogBody key={JSON.stringify(intent)} intent={intent} tickets={tickets} onClose={onClose} onApplied={onApplied} />}
    </Modal>
  );
}

function BulkDialogBody({ intent, tickets, onClose, onApplied }: Props & { intent: BulkIntent }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const directory = useDirectory().data;
  const [assign, setAssign] = useState({
    groupId: intent.kind === 'assign' ? (intent.groupId ?? null) : null,
    assigneeId: intent.kind === 'assign' ? (intent.assigneeId ?? null) : null,
  });
  const [reason, setReason] = useState('');
  const [resolutionCode, setResolutionCode] = useState<string | null>(null);
  const [resolutionText, setResolutionText] = useState('');
  const [reasonCode, setReasonCode] = useState<string | null>(null);
  const oldest = [...tickets].sort((a, b) => a.createdAt.localeCompare(b.createdAt))[0];
  const [targetId, setTargetId] = useState(oldest?.id ?? '');
  const [busy, setBusy] = useState(false);

  const needsReason = intent.kind === 'transition' && (intent.to === 'pending' || intent.to === 'cancelled');
  const action: BulkAction = (() => {
    switch (intent.kind) {
      case 'assign':
        return { kind: 'assign', groupId: assign.groupId ?? undefined, assigneeId: assign.assigneeId };
      case 'priority':
        return { kind: 'priority', priority: intent.priority, reason };
      case 'transition':
        return intent.to === 'resolved'
          ? { kind: 'transition', to: intent.to, resolutionCode: resolutionCode ?? undefined, resolutionText }
          : needsReason
            ? { kind: 'transition', to: intent.to, reasonCode: reasonCode ?? undefined }
            : { kind: 'transition', to: intent.to };
      case 'merge':
        return { kind: 'merge', targetId };
    }
  })();

  const ids = tickets.map((x) => x.id);
  // Текст причины не влияет на предпросмотр, поэтому не входит в ключ запроса.
  const previewKey = action.kind === 'priority' ? { ...action, reason: '' } : action.kind === 'transition' ? { kind: action.kind, to: action.to } : action;
  const preview = useQuery({
    queryKey: ['tickets', 'bulk-preview', ids, previewKey],
    queryFn: () => ticketsApi.bulkPreview(ids, action),
    staleTime: 0,
  });
  const items = preview.data?.items ?? [];
  const willChange = items.filter((i) => !i.skipReason && i.changes.length > 0).length;

  const invalid =
    (intent.kind === 'priority' && !reason.trim()) ||
    (intent.kind === 'transition' && intent.to === 'resolved' && (!resolutionCode || !resolutionText.trim())) ||
    (needsReason && !reasonCode) ||
    (intent.kind === 'assign' && !assign.groupId && !assign.assigneeId);

  const apply = async () => {
    setBusy(true);
    try {
      const result = await ticketsApi.bulkApply(ids, action, crypto.randomUUID());
      await qc.invalidateQueries({ queryKey: ['tickets'] });
      onApplied();
      onClose();
      showUndoToast({
        message: t('workspace.bulk.applied', { count: result.applied }),
        onUndo: async () => {
          try {
            await ticketsApi.bulkUndo(result.operationId);
            await qc.invalidateQueries({ queryKey: ['tickets'] });
          } catch (e) {
            showError(e);
          }
        },
      });
    } catch (e) {
      showError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Stack gap="md">
      {intent.kind === 'assign' && (
        <AssignFields directory={directory} groupId={assign.groupId} assigneeId={assign.assigneeId} onChange={setAssign} autoFocus />
      )}
      {intent.kind === 'priority' && (
        <TextInput
          label={t('workspace.bulk.priorityReason')}
          description={t('workspace.bulk.priorityReasonHint')}
          value={reason}
          onChange={(e) => setReason(e.currentTarget.value)}
          required
          data-autofocus
        />
      )}
      {intent.kind === 'transition' && intent.to === 'resolved' && (
        <>
          <Select
            label={t('workspace.resolve.code')}
            data={(directory?.resolutionCodes ?? []).map((c) => ({ value: c.id, label: c.name }))}
            value={resolutionCode}
            onChange={setResolutionCode}
            required
            comboboxProps={{ withinPortal: false }}
          />
          <Textarea label={t('workspace.resolve.text')} value={resolutionText} onChange={(e) => setResolutionText(e.currentTarget.value)} autosize minRows={2} required />
        </>
      )}
      {needsReason && intent.kind === 'transition' && (
        <Select
          label={t(intent.to === 'pending' ? 'workspace.pause.reason' : 'workspace.cancel.reason')}
          data={(intent.to === 'pending' ? (directory?.pendingReasons ?? []) : (directory?.cancelReasons ?? [])).map((c) => ({ value: c.id, label: c.name }))}
          value={reasonCode}
          onChange={setReasonCode}
          required
          data-autofocus
          comboboxProps={{ withinPortal: false }}
        />
      )}
      {intent.kind === 'merge' && (
        <Radio.Group label={t('workspace.bulk.mergeTarget')} description={t('workspace.bulk.mergeHint')} value={targetId} onChange={setTargetId}>
          <Stack gap={6} mt={6}>
            {tickets.map((x) => (
              <Radio key={x.id} value={x.id} label={`${x.number} · ${x.title}`} />
            ))}
          </Stack>
        </Radio.Group>
      )}

      <div>
        <Text size="sm" fw={600} mb={6}>
          {t('workspace.bulk.preview')}
        </Text>
        {preview.isPending ? (
          <Text size="sm" c="dimmed">{t('states.loading')}</Text>
        ) : (
          <ScrollArea.Autosize mah={260}>
            <Table verticalSpacing={6} fz="sm">
              <Table.Tbody>
                {items.map((item) => (
                  <Table.Tr key={item.ticket.id} opacity={item.skipReason || item.changes.length === 0 ? 0.55 : 1}>
                    <Table.Td w={110} ff="monospace" c="dimmed">{item.ticket.number}</Table.Td>
                    <Table.Td>
                      {item.skipReason ? (
                        <Badge color="gray" variant="light" style={{ textTransform: 'none' }}>
                          {t(`workspace.bulk.skip.${item.skipReason}`, { defaultValue: item.skipReason })}
                        </Badge>
                      ) : item.changes.length === 0 ? (
                        <Text size="sm" c="dimmed">{t('workspace.bulk.noChange')}</Text>
                      ) : (
                        <Stack gap={2}>
                          {item.changes.map((c) => (
                            <Group key={c.field} gap={6} wrap="nowrap">
                              <Text size="sm" c="dimmed">{t(`workspace.field.${c.field}`)}:</Text>
                              <Text size="sm" td="line-through" c="dimmed">{c.from ?? '—'}</Text>
                              <IconArrowRight size={12} />
                              <Text size="sm" fw={500}>{c.to ?? '—'}</Text>
                            </Group>
                          ))}
                        </Stack>
                      )}
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </ScrollArea.Autosize>
        )}
      </div>

      <Alert variant="light" icon={<IconInfoCircle />} p="xs">
        <Text size="sm">{t('workspace.bulk.undoHint')}</Text>
      </Alert>

      <Group justify="flex-end">
        <Button variant="default" onClick={onClose}>{t('workspace.actions.cancel')}</Button>
        <Button onClick={() => void apply()} loading={busy} disabled={invalid || preview.isPending || willChange === 0}>
          {t('workspace.bulk.apply', { count: willChange })}
        </Button>
      </Group>
    </Stack>
  );
}
