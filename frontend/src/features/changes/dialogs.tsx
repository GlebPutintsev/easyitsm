import { Alert, Anchor, Button, Group, Modal, Radio, Stack, Text, Textarea, TextInput } from '@mantine/core';
import { IconAlertTriangle } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { ApiError } from '@/shared/api/client';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import type { Change, Conflict, Outcome } from './api';
import { fromLocalInput, toLocalInput } from './model';

interface ReasonProps {
  opened: boolean;
  title: string;
  label: string;
  confirm: string;
  danger?: boolean;
  required?: boolean;
  pending: boolean;
  error: unknown;
  onClose: () => void;
  onConfirm: (reason: string) => void;
}

/** Действие с обязательной причиной: отмена, возврат в черновик, начало возврата. */
export function ReasonDialog({ opened, title, label, confirm, danger, required = true, pending, error, onClose, onConfirm }: ReasonProps) {
  const { t } = useTranslation();
  const [reason, setReason] = useState('');
  const [touched, setTouched] = useState(false);
  const missing = required && !reason.trim();
  return (
    <Modal opened={opened} onClose={onClose} title={title} centered>
      <Stack gap="sm">
        <Textarea
          label={label}
          withAsterisk={required}
          autosize
          minRows={3}
          value={reason}
          onChange={(e) => setReason(e.currentTarget.value)}
          error={touched && missing ? t('changes.dialogs.reasonRequired') : undefined}
          data-autofocus
        />
        <ErrorLine error={error} />
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            {t('actions.cancel')}
          </Button>
          <Button
            color={danger ? 'red' : undefined}
            loading={pending}
            onClick={() => {
              setTouched(true);
              if (!missing) onConfirm(reason.trim());
            }}
          >
            {confirm}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}

/** Список пересечений из ответа 409 change_conflicts или из GET /conflicts (AT-06). */
export function ConflictList({ conflicts }: { conflicts: Conflict[] }) {
  const { t, i18n } = useTranslation();
  return (
    <Stack gap={4}>
      {conflicts.map((c, i) => (
        <Text size="sm" key={`${c.kind}-${c.changeId ?? c.freezeId}-${i}`}>
          {c.kind === 'freeze' ? (
            <>
              {t('changes.conflicts.freeze')}: <b>{c.title}</b>
            </>
          ) : (
            <>
              {t(`changes.conflicts.${c.kind}`)}:{' '}
              <Anchor component={Link} to={`/agent/changes/${c.changeId}`}>
                {c.number}
              </Anchor>{' '}
              {c.title}
            </>
          )}{' '}
          <Text span c="dimmed" size="xs">
            {new Date(c.from).toLocaleString(i18n.language, { dateStyle: 'short', timeStyle: 'short' })} –{' '}
            {new Date(c.to).toLocaleString(i18n.language, { dateStyle: 'short', timeStyle: 'short' })}
          </Text>
        </Text>
      ))}
    </Stack>
  );
}

export function conflictsOf(error: unknown): Conflict[] | null {
  if (error instanceof ApiError && error.code === 'change_conflicts' && Array.isArray(error.problem.conflicts)) {
    return error.problem.conflicts as Conflict[];
  }
  return null;
}

interface AckProps {
  opened: boolean;
  conflicts: Conflict[];
  pending: boolean;
  error: unknown;
  onClose: () => void;
  onConfirm: (ack: string) => void;
}

/** Пересечение с другими изменениями: продолжить можно только с причиной, она уходит в заявку. */
export function ConflictAckDialog({ opened, conflicts, pending, error, onClose, onConfirm }: AckProps) {
  const { t } = useTranslation();
  const [ack, setAck] = useState('');
  return (
    <Modal opened={opened} onClose={onClose} title={t('changes.conflicts.title')} centered size="lg">
      <Stack gap="sm">
        <Alert color="orange" icon={<IconAlertTriangle />}>
          {t('changes.conflicts.hint')}
        </Alert>
        <ConflictList conflicts={conflicts} />
        <Textarea label={t('changes.conflicts.ack')} withAsterisk autosize minRows={2} value={ack} onChange={(e) => setAck(e.currentTarget.value)} />
        <ErrorLine error={conflictsOf(error) ? null : error} />
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            {t('actions.cancel')}
          </Button>
          <Button disabled={!ack.trim()} loading={pending} onClick={() => onConfirm(ack.trim())}>
            {t('changes.conflicts.proceed')}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}

interface RescheduleProps {
  change: Change;
  opened: boolean;
  pending: boolean;
  error: unknown;
  onClose: () => void;
  onConfirm: (body: { windowStart: string; windowEnd: string; reason: string; acknowledgeConflicts?: string }) => void;
}

/** Перенос окна (CHG-05): у согласованного обычного изменения начинается новый раунд согласования. */
export function RescheduleDialog({ change, opened, pending, error, onClose, onConfirm }: RescheduleProps) {
  const { t } = useTranslation();
  const [start, setStart] = useState(toLocalInput(change.windowStart));
  const [end, setEnd] = useState(toLocalInput(change.windowEnd));
  const [reason, setReason] = useState('');
  const [ack, setAck] = useState('');
  const conflicts = conflictsOf(error);
  const startIso = fromLocalInput(start);
  const endIso = fromLocalInput(end);
  const valid = !!startIso && !!endIso && endIso > startIso && !!reason.trim() && (!conflicts || !!ack.trim());
  return (
    <Modal opened={opened} onClose={onClose} title={t('changes.actions.reschedule')} centered size="lg">
      <Stack gap="sm">
        {change.type === 'normal' && change.approvedAt && (
          <Alert color="blue">{t('changes.dialogs.rescheduleReapproval')}</Alert>
        )}
        <Group grow>
          <TextInput type="datetime-local" label={t('changes.fields.windowStart')} value={start} onChange={(e) => setStart(e.currentTarget.value)} />
          <TextInput type="datetime-local" label={t('changes.fields.windowEnd')} value={end} onChange={(e) => setEnd(e.currentTarget.value)} />
        </Group>
        <Textarea label={t('changes.dialogs.reason')} withAsterisk autosize minRows={2} value={reason} onChange={(e) => setReason(e.currentTarget.value)} />
        {conflicts && (
          <>
            <Alert color="orange" icon={<IconAlertTriangle />} title={t('changes.conflicts.title')}>
              <ConflictList conflicts={conflicts} />
            </Alert>
            <Textarea label={t('changes.conflicts.ack')} withAsterisk autosize minRows={2} value={ack} onChange={(e) => setAck(e.currentTarget.value)} />
          </>
        )}
        <ErrorLine error={conflicts ? null : error} />
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            {t('actions.cancel')}
          </Button>
          <Button
            disabled={!valid}
            loading={pending}
            onClick={() =>
              onConfirm({ windowStart: startIso!, windowEnd: endIso!, reason: reason.trim(), acknowledgeConflicts: ack.trim() || undefined })
            }
          >
            {t('changes.actions.reschedule')}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}

interface CompleteProps {
  opened: boolean;
  rolledBack: boolean;
  pending: boolean;
  error: unknown;
  onClose: () => void;
  onConfirm: (body: { outcome: Outcome; conclusion: string }) => void;
}

/** Итог внедрения: успешно, частично или с возвратом. */
export function CompleteDialog({ opened, rolledBack, pending, error, onClose, onConfirm }: CompleteProps) {
  const { t } = useTranslation();
  const [outcome, setOutcome] = useState<Outcome>(rolledBack ? 'rolled_back' : 'successful');
  const [conclusion, setConclusion] = useState('');
  return (
    <Modal opened={opened} onClose={onClose} title={t('changes.actions.complete')} centered>
      <Stack gap="sm">
        <Radio.Group value={outcome} onChange={(v) => setOutcome(v as Outcome)} label={t('changes.fields.outcome')}>
          <Stack gap={6} mt={6}>
            {(['successful', 'partial', 'rolled_back'] as const).map((o) => (
              <Radio key={o} value={o} label={t(`changes.outcome.${o}`)} />
            ))}
          </Stack>
        </Radio.Group>
        <Textarea label={t('changes.fields.conclusion')} autosize minRows={3} value={conclusion} onChange={(e) => setConclusion(e.currentTarget.value)} />
        <ErrorLine error={error} />
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            {t('actions.cancel')}
          </Button>
          <Button loading={pending} onClick={() => onConfirm({ outcome, conclusion: conclusion.trim() })}>
            {t('changes.actions.complete')}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
