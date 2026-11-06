import { Anchor, Badge, Button, Divider, Group, Modal, SegmentedControl, Select, Stack, Switch, Table, Text, TextInput } from '@mantine/core';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { PersonPicker } from '@/shared/ui/PersonPicker';
import {
  type Impact,
  type Level,
  type Severity,
  SEVERITIES,
  type SourceInput,
  type SourceKind,
  type SourceView,
  type WithToken,
  useActiveAlerts,
  useCreateSource,
  useDeleteSource,
  useGroups,
  useResolutionCodes,
  useRotateToken,
  useUpdateSource,
} from './api';
import { SetupGuide } from './SetupGuide';

const DEFAULT_MAP: Record<Severity, Impact> = {
  critical: { impact: 'high', urgency: 'high' },
  high: { impact: 'high', urgency: 'medium' },
  warning: { impact: 'medium', urgency: 'medium' },
  info: { impact: 'low', urgency: 'low' },
};
const LEVELS: Level[] = ['high', 'medium', 'low'];

interface Props {
  current: SourceView | null;
  canEdit: boolean;
  onClose: () => void;
  /** Создан источник или перевыпущен токен: показать токен один раз. */
  onToken: (issued: WithToken) => void;
}

/** Создание и изменение источника мониторинга, перевыпуск токена, активные алерты. */
export function SourceModal({ current, canEdit, onClose, onToken }: Props) {
  const { t } = useTranslation();
  const s = current?.source;
  const [form, setForm] = useState({
    name: s?.name ?? '',
    kind: (s?.kind ?? 'zabbix') as SourceKind,
    requesterId: s?.requesterId ?? null,
    groupId: s?.groupId ?? null,
    minSeverity: (s?.minSeverity ?? 'warning') as Severity,
    severityMap: s?.severityMap ?? DEFAULT_MAP,
    autoResolve: s?.autoResolve ?? true,
    resolutionCode: s?.resolutionCode ?? 'fixed',
    enabled: s?.enabled ?? true,
  });
  const set = <K extends keyof typeof form>(k: K, v: (typeof form)[K]) => setForm((f) => ({ ...f, [k]: v }));
  const setImpact = (sev: Severity, field: keyof Impact, v: Level) =>
    setForm((f) => ({ ...f, severityMap: { ...f.severityMap, [sev]: { ...f.severityMap[sev], [field]: v } } }));

  const groups = useGroups();
  const codes = useResolutionCodes();
  const alerts = useActiveAlerts(s?.id ?? null);
  const create = useCreateSource();
  const update = useUpdateSource();
  const rotate = useRotateToken();
  const remove = useDeleteSource();
  const [confirmDelete, setConfirmDelete] = useState(false);
  const pending = create.isPending || update.isPending || rotate.isPending || remove.isPending;

  const submit = () => {
    const input: SourceInput = {
      ...form,
      name: form.name.trim(),
      requesterId: form.requesterId,
      groupId: form.groupId,
      clearGroup: !!s && !form.groupId,
    };
    if (!s) create.mutate(input, { onSuccess: onToken });
    else update.mutate({ id: s.id, version: s.version, input }, { onSuccess: onClose });
  };

  const levelOptions = LEVELS.map((l) => ({ value: l, label: t(`integrations.levels.${l}`) }));

  return (
    <Modal
      opened
      onClose={onClose}
      size="xl"
      title={s ? t('integrations.monitoring.editTitle', { name: s.name }) : t('integrations.monitoring.newTitle')}
    >
      <Stack gap="sm">
        <TextInput
          required
          label={t('integrations.monitoring.name')}
          value={form.name}
          disabled={!canEdit}
          onChange={(e) => set('name', e.currentTarget.value)}
        />
        <SegmentedControl
          value={form.kind}
          disabled={!canEdit}
          onChange={(v) => set('kind', v as SourceKind)}
          data={(['zabbix', 'alertmanager', 'generic'] as const).map((k) => ({
            value: k,
            label: t(`integrations.kinds.${k}`),
          }))}
        />
        <Group grow align="flex-start">
          <PersonPicker
            label={t('integrations.monitoring.requester')}
            description={s ? undefined : t('integrations.monitoring.requesterHint')}
            value={form.requesterId}
            onChange={(v) => canEdit && set('requesterId', v)}
          />
          <Select
            label={t('integrations.monitoring.group')}
            placeholder={t('integrations.monitoring.routing')}
            data={(groups.data ?? []).map((g) => ({ value: g.id, label: g.name }))}
            value={form.groupId}
            disabled={!canEdit}
            clearable
            searchable
            onChange={(v) => set('groupId', v)}
          />
        </Group>
        <Select
          label={t('integrations.monitoring.minSeverity')}
          description={t('integrations.monitoring.minSeverityHint')}
          data={SEVERITIES.map((v) => ({ value: v, label: t(`integrations.severity.${v}`) }))}
          value={form.minSeverity}
          disabled={!canEdit}
          allowDeselect={false}
          maw={360}
          onChange={(v) => set('minSeverity', (v ?? 'warning') as Severity)}
        />
        <div>
          <Text size="sm" fw={500}>
            {t('integrations.monitoring.mapping')}
          </Text>
          <Text size="xs" c="dimmed" mb={6}>
            {t('integrations.monitoring.mappingHint')}
          </Text>
          <Table.ScrollContainer minWidth={480}>
            <Table verticalSpacing={4}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t('integrations.monitoring.severityColumn')}</Table.Th>
                  <Table.Th>{t('integrations.monitoring.impact')}</Table.Th>
                  <Table.Th>{t('integrations.monitoring.urgency')}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {SEVERITIES.map((sev) => (
                  <Table.Tr key={sev}>
                    <Table.Td>{t(`integrations.severity.${sev}`)}</Table.Td>
                    {(['impact', 'urgency'] as const).map((field) => (
                      <Table.Td key={field}>
                        <Select
                          aria-label={`${t(`integrations.severity.${sev}`)}: ${t(`integrations.monitoring.${field}`)}`}
                          size="xs"
                          data={levelOptions}
                          value={form.severityMap[sev][field]}
                          disabled={!canEdit}
                          allowDeselect={false}
                          onChange={(v) => setImpact(sev, field, (v ?? 'medium') as Level)}
                        />
                      </Table.Td>
                    ))}
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Table.ScrollContainer>
        </div>
        <Group align="flex-end" wrap="wrap">
          <Switch
            label={t('integrations.monitoring.autoResolve')}
            description={t('integrations.monitoring.autoResolveHint')}
            checked={form.autoResolve}
            disabled={!canEdit}
            onChange={(e) => set('autoResolve', e.currentTarget.checked)}
            style={{ flex: 1, minWidth: 260 }}
          />
          <Select
            label={t('integrations.monitoring.resolutionCode')}
            data={(codes.data ?? [{ code: form.resolutionCode, name: form.resolutionCode }]).map((c) => ({
              value: c.code,
              label: c.name,
            }))}
            value={form.resolutionCode}
            disabled={!canEdit || !form.autoResolve}
            allowDeselect={false}
            w={240}
            onChange={(v) => set('resolutionCode', v ?? 'fixed')}
          />
        </Group>
        <Switch
          label={t('integrations.monitoring.enabled')}
          checked={form.enabled}
          disabled={!canEdit}
          onChange={(e) => set('enabled', e.currentTarget.checked)}
        />

        {s && (
          <>
            <Divider />
            <Group justify="space-between">
              <Text size="sm" fw={500}>
                {t('integrations.monitoring.activeAlerts')}
              </Text>
              <Button variant="subtle" size="xs" component={Link} to={`/admin/integrations?tab=events&source=${s.id}`} onClick={onClose}>
                {t('integrations.monitoring.openJournal')}
              </Button>
            </Group>
            {(alerts.data ?? []).length === 0 ? (
              <Text size="sm" c="dimmed">
                {t('integrations.monitoring.noAlerts')}
              </Text>
            ) : (
              <Stack gap={4}>
                {(alerts.data ?? []).slice(0, 10).map((a) => (
                  <Group key={a.id} gap="xs" wrap="nowrap">
                    <Badge size="sm" variant="light" color={a.severity === 'critical' ? 'red' : a.severity === 'high' ? 'orange' : 'gray'}>
                      {t(`integrations.severity.${a.severity}`)}
                    </Badge>
                    <Anchor component={Link} size="sm" to={`/agent/tickets/${a.ticketNumber}`} onClick={onClose}>
                      {a.ticketNumber}
                    </Anchor>
                    <Text size="sm" truncate>
                      {a.title}
                    </Text>
                    <Text size="xs" c="dimmed" style={{ whiteSpace: 'nowrap' }}>
                      ×{a.occurrences}
                    </Text>
                  </Group>
                ))}
              </Stack>
            )}
            <Divider />
            <SetupGuide kind={form.kind} token={null} />
          </>
        )}

        <ErrorLine error={create.error ?? update.error ?? rotate.error ?? remove.error} />
        <Group justify="space-between" wrap="wrap">
          <Group gap="xs">
            {s && canEdit && (
              <>
                <Button variant="default" loading={rotate.isPending} disabled={pending} onClick={() => rotate.mutate(s.id, { onSuccess: onToken })}>
                  {t('integrations.monitoring.rotate')}
                </Button>
                {confirmDelete ? (
                  <Button color="red" loading={remove.isPending} disabled={pending} onClick={() => remove.mutate(s.id, { onSuccess: onClose })}>
                    {t('integrations.monitoring.confirmDelete')}
                  </Button>
                ) : (
                  <Button variant="subtle" color="red" disabled={pending} onClick={() => setConfirmDelete(true)}>
                    {t('integrations.monitoring.delete')}
                  </Button>
                )}
              </>
            )}
          </Group>
          <Group gap="xs">
            <Button variant="default" onClick={onClose}>
              {t('integrations.cancel')}
            </Button>
            {canEdit && (
              <Button onClick={submit} loading={create.isPending || update.isPending} disabled={pending || !form.name.trim()}>
                {s ? t('integrations.save') : t('integrations.monitoring.create')}
              </Button>
            )}
          </Group>
        </Group>
      </Stack>
    </Modal>
  );
}
