import { Button, Group, Modal, Select, Stack, Text, TextInput, Textarea } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import { useDirectory } from '@/features/agent/api/hooks';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { useCreateProblem, type NewProblemInput } from './api';

export interface ProblemDraft {
  title: string;
  description: string;
  incidentIds: string[];
  source: NewProblemInput['source'];
  /** Номер инцидента, из которого создаётся проблема: для заголовка окна. */
  fromNumber?: string;
  groupId?: string | null;
}

/**
 * Новая проблема (10.7 «Создать проблему»): тема и описание копируются из инцидента или собираются из отчёта
 * повторов, инциденты связываются сразу. После создания открывается карточка проблемы.
 */
export function CreateProblemDialog({ opened, onClose, draft }: { opened: boolean; onClose: () => void; draft: ProblemDraft }) {
  return (
    <Modal opened={opened} onClose={onClose} size="lg" title={null} withCloseButton={false}>
      {opened && <CreateProblemForm draft={draft} onClose={onClose} />}
    </Modal>
  );
}

function CreateProblemForm({ draft, onClose }: { draft: ProblemDraft; onClose: () => void }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const directory = useDirectory().data;
  const create = useCreateProblem();
  const [title, setTitle] = useState(draft.title);
  const [description, setDescription] = useState(draft.description);
  const [groupId, setGroupId] = useState<string | null>(draft.groupId ?? null);

  const submit = () =>
    create.mutate(
      { title: title.trim(), description, incidentIds: draft.incidentIds, source: draft.source, groupId },
      {
        onSuccess: (p) => {
          notifications.show({ message: t('problems.create.created', { number: p.number }), color: 'green' });
          onClose();
          void navigate(`/agent/problems/${p.number}`);
        },
      },
    );

  return (
    <Stack gap="md">
      <Text fw={700} size="lg">
        {draft.fromNumber ? t('problems.create.fromTicket', { number: draft.fromNumber }) : t('problems.create.title')}
      </Text>
      <TextInput label={t('problems.create.name')} value={title} onChange={(e) => setTitle(e.currentTarget.value)} maxLength={255} required data-autofocus />
      <Textarea label={t('problems.create.description')} value={description} onChange={(e) => setDescription(e.currentTarget.value)} autosize minRows={3} maxRows={10} />
      <Select
        label={t('problems.create.group')}
        data={(directory?.groups ?? []).map((g) => ({ value: g.id, label: g.name }))}
        value={groupId}
        onChange={setGroupId}
        clearable
        searchable
      />
      {draft.incidentIds.length > 0 && (
        <Text size="sm" c="dimmed">
          {t('problems.create.incidents', { count: draft.incidentIds.length })}
        </Text>
      )}
      <ErrorLine error={create.error} />
      <Group justify="flex-end">
        <Button variant="default" onClick={onClose}>
          {t('actions.cancel')}
        </Button>
        <Button onClick={submit} loading={create.isPending} disabled={!title.trim()}>
          {t('problems.create.submit')}
        </Button>
      </Group>
    </Stack>
  );
}
