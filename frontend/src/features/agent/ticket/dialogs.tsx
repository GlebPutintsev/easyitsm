import { Alert, Button, Checkbox, Group, Modal, SegmentedControl, Select, Stack, TextInput, Textarea } from '@mantine/core';
import { IconInfoCircle } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import { useDirectory, useEscalate, useTransition, useUpdateTicket } from '../api/hooks';
import type { AvailableTransition, Ticket } from '../api/model';
import { AssignFields } from '../ui/AssignFields';
import { showError } from '../ui/undoToast';

/** Локальное время для <input type="datetime-local"> через сутки, по умолчанию для «Ожидания». */
function tomorrowLocal(): string {
  const d = new Date(Date.now() + 24 * 3600_000);
  d.setMinutes(0, 0, 0);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:00`;
}

/**
 * Диалог перехода по обязательным полям из контракта (requiredFields): причина, срок возврата,
 * код и описание решения, комментарий. Так один диалог покрывает «Решить», «Приостановить»,
 * «Возобновить» и «Отменить» для любого настроенного цикла (10.7).
 */
export function TransitionDialog({
  ticket,
  transition,
  onClose,
  onDone,
}: {
  ticket: Ticket;
  transition: AvailableTransition | null;
  onClose: () => void;
  onDone?: (ticket: Ticket) => void;
}) {
  const { t } = useTranslation();
  return (
    <Modal opened={transition !== null} onClose={onClose} title={transition ? `${transition.name} · ${ticket.number}` : ''} size="md">
      {transition && <TransitionForm key={transition.to} ticket={ticket} transition={transition} onClose={onClose} onDone={onDone} t={t} />}
    </Modal>
  );
}

function TransitionForm({
  ticket,
  transition,
  onClose,
  onDone,
  t,
}: {
  ticket: Ticket;
  transition: AvailableTransition;
  onClose: () => void;
  onDone?: (ticket: Ticket) => void;
  t: ReturnType<typeof useTranslation>['t'];
}) {
  const directory = useDirectory().data;
  const navigate = useNavigate();
  const mutation = useTransition(ticket);
  const need = new Set(transition.requiredFields);
  const isResolve = transition.category === 'resolved';
  const isPending = transition.category === 'pending';
  const [reasonCode, setReasonCode] = useState<string | null>(null);
  const [until, setUntil] = useState(tomorrowLocal());
  const [resolutionCode, setResolutionCode] = useState<string | null>(null);
  const [resolutionText, setResolutionText] = useState('');
  const [comment, setComment] = useState('');
  const [createArticle, setCreateArticle] = useState(false);

  const reasons = isPending ? directory?.pendingReasons : directory?.cancelReasons;
  const invalid =
    (need.has('reason_code') && !reasonCode) ||
    (need.has('resolution') && (!resolutionCode || !resolutionText.trim())) ||
    (need.has('comment') && !comment.trim()) ||
    (need.has('pending_until') && !until);

  const submit = () =>
    mutation.mutate(
      {
        to: transition.to,
        reasonCode: reasonCode ?? undefined,
        pendingUntil: need.has('pending_until') || isPending ? new Date(until).toISOString() : undefined,
        resolutionCode: resolutionCode ?? undefined,
        resolutionText: resolutionText.trim() || undefined,
        comment: comment.trim() || undefined,
      },
      {
        onSuccess: (updated) => {
          onClose();
          onDone?.(updated);
          // Статья из заявки (KB-04): редактор базы знаний получает номер заявки и текст решения.
          if (createArticle) navigate(`/agent/kb/new?fromTicket=${encodeURIComponent(updated.number)}`);
        },
        onError: showError,
      },
    );

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        if (!invalid) submit();
      }}
    >
      <Stack>
        {(need.has('reason_code') || isPending) && (
          <Select
            label={t(isPending ? 'workspace.pause.reason' : 'workspace.cancel.reason')}
            data={(reasons ?? []).map((r) => ({ value: r.id, label: r.name }))}
            value={reasonCode}
            onChange={setReasonCode}
            required={need.has('reason_code')}
            data-autofocus
            comboboxProps={{ withinPortal: false }}
          />
        )}
        {(need.has('pending_until') || isPending) && (
          <TextInput
            type="datetime-local"
            label={t('workspace.pause.until')}
            description={t('workspace.pause.untilHint')}
            value={until}
            onChange={(e) => setUntil(e.currentTarget.value)}
            required={need.has('pending_until')}
          />
        )}
        {(need.has('resolution') || isResolve) && (
          <>
            <Select
              label={t('workspace.resolve.code')}
              data={(directory?.resolutionCodes ?? []).map((r) => ({ value: r.id, label: r.name }))}
              value={resolutionCode}
              onChange={setResolutionCode}
              required={need.has('resolution')}
              data-autofocus
              comboboxProps={{ withinPortal: false }}
            />
            <Textarea
              label={t('workspace.resolve.text')}
              description={t('workspace.resolve.textHint')}
              value={resolutionText}
              onChange={(e) => setResolutionText(e.currentTarget.value)}
              autosize
              minRows={3}
              required={need.has('resolution')}
            />
            <Checkbox
              label={t('workspace.resolve.createArticle')}
              description={t('workspace.resolve.createArticleHint')}
              checked={createArticle}
              onChange={(e) => setCreateArticle(e.currentTarget.checked)}
            />
          </>
        )}
        {(need.has('comment') || !isResolve) && (
          <Textarea
            label={t('workspace.transition.comment')}
            description={t('workspace.transition.commentHint')}
            value={comment}
            onChange={(e) => setComment(e.currentTarget.value)}
            autosize
            minRows={2}
            required={need.has('comment')}
            data-autofocus={need.size === 0 || undefined}
          />
        )}
        {isResolve && (
          <Alert variant="light" icon={<IconInfoCircle />} p="xs">
            {t('workspace.resolve.notice')}
          </Alert>
        )}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            {t('workspace.actions.cancel')}
          </Button>
          <Button type="submit" loading={mutation.isPending} disabled={invalid} color={transition.category === 'cancelled' ? 'red' : undefined}>
            {transition.name}
          </Button>
        </Group>
      </Stack>
    </form>
  );
}

/** «Назначить»: группа, исполнитель с загрузкой (10.7). */
export function AssignDialog({ ticket, opened, onClose }: { ticket: Ticket; opened: boolean; onClose: () => void }) {
  const { t } = useTranslation();
  return (
    <Modal opened={opened} onClose={onClose} title={`${t('workspace.actions.assign')} · ${ticket.number}`}>
      {opened && <AssignForm ticket={ticket} onClose={onClose} />}
    </Modal>
  );
}

function AssignForm({ ticket, onClose }: { ticket: Ticket; onClose: () => void }) {
  const { t } = useTranslation();
  const directory = useDirectory().data;
  const update = useUpdateTicket(ticket);
  const [value, setValue] = useState({ groupId: ticket.group?.id ?? null, assigneeId: ticket.assignee?.id ?? null });
  return (
    <Stack>
      <AssignFields directory={directory} groupId={value.groupId} assigneeId={value.assigneeId} onChange={setValue} autoFocus />
      <Group justify="flex-end">
        <Button variant="default" onClick={onClose}>
          {t('workspace.actions.cancel')}
        </Button>
        <Button
          loading={update.isPending}
          disabled={!value.groupId}
          onClick={() => update.mutate({ groupId: value.groupId, assigneeId: value.assigneeId }, { onSuccess: onClose, onError: showError })}
        >
          {t('workspace.actions.assign')}
        </Button>
      </Group>
    </Stack>
  );
}

/** «Эскалировать»: функциональная — в другую группу, иерархическая — руководителю (INC-04). */
export function EscalateDialog({ ticket, opened, onClose }: { ticket: Ticket; opened: boolean; onClose: () => void }) {
  const { t } = useTranslation();
  return (
    <Modal opened={opened} onClose={onClose} title={`${t('workspace.actions.escalate')} · ${ticket.number}`}>
      {opened && <EscalateForm ticket={ticket} onClose={onClose} />}
    </Modal>
  );
}

function EscalateForm({ ticket, onClose }: { ticket: Ticket; onClose: () => void }) {
  const { t } = useTranslation();
  const directory = useDirectory().data;
  const escalate = useEscalate(ticket);
  const [kind, setKind] = useState<'functional' | 'hierarchical'>('functional');
  const [groupId, setGroupId] = useState<string | null>(null);
  const [reason, setReason] = useState('');
  const invalid = !reason.trim() || (kind === 'functional' && !groupId);
  return (
    <Stack>
      <SegmentedControl
        value={kind}
        onChange={(v) => setKind(v as typeof kind)}
        data={[
          { value: 'functional', label: t('workspace.escalate.functional') },
          { value: 'hierarchical', label: t('workspace.escalate.hierarchical') },
        ]}
      />
      {kind === 'functional' && (
        <Select
          label={t('workspace.field.group')}
          data={(directory?.groups ?? []).filter((g) => g.id !== ticket.group?.id).map((g) => ({ value: g.id, label: g.name }))}
          value={groupId}
          onChange={setGroupId}
          searchable
          required
          comboboxProps={{ withinPortal: false }}
        />
      )}
      <Textarea label={t('workspace.escalate.reason')} value={reason} onChange={(e) => setReason(e.currentTarget.value)} autosize minRows={2} required data-autofocus />
      <Group justify="flex-end">
        <Button variant="default" onClick={onClose}>
          {t('workspace.actions.cancel')}
        </Button>
        <Button
          color="orange"
          loading={escalate.isPending}
          disabled={invalid}
          onClick={() =>
            escalate.mutate({ kind, groupId: groupId ?? undefined, reason: reason.trim() }, { onSuccess: onClose, onError: showError })
          }
        >
          {t('workspace.actions.escalate')}
        </Button>
      </Group>
    </Stack>
  );
}
