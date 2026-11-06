import { Anchor, Badge, Button, Group, Stack, Switch, Table, Text } from '@mantine/core';
import { IconPlus } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { hasRole, useMe } from '@/features/auth/api';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import { type SourceView, type WithToken, useSources, useUpdateSource } from './api';
import { SourceModal } from './SourceModal';
import { TokenModal } from './TokenModal';

/** Источники мониторинга: список, включение, создание и изменение. */
export function SourcesPanel() {
  const { t, i18n } = useTranslation();
  const me = useMe();
  const canEdit = hasRole(me.data, 'ADM');
  const sources = useSources();
  const list = sources.data ?? [];
  const state = listStateOf(sources, list.length);
  const update = useUpdateSource();
  const [editing, setEditing] = useState<SourceView | 'new' | null>(null);
  const [issued, setIssued] = useState<WithToken | null>(null);

  const toggle = (s: SourceView, enabled: boolean) =>
    update.mutate({ id: s.source.id, version: s.source.version, input: { enabled } });

  return (
    <Stack gap="sm">
      <Group justify="space-between" align="flex-start" wrap="wrap">
        <Text size="sm" c="dimmed" maw={760}>
          {t('integrations.monitoring.intro')}
        </Text>
        {canEdit && (
          <Button leftSection={<IconPlus size={16} />} onClick={() => setEditing('new')}>
            {t('integrations.monitoring.add')}
          </Button>
        )}
      </Group>
      <ErrorLine error={update.error} />
      {state ? (
        <ListState
          state={state}
          title={state === 'empty' ? t('integrations.monitoring.empty') : undefined}
          error={sources.error}
          onRetry={() => void sources.refetch()}
          action={
            state === 'empty' ? (
              <Text size="sm" c="dimmed">
                {t('integrations.monitoring.emptyHint')}
              </Text>
            ) : undefined
          }
        />
      ) : (
        <Table.ScrollContainer minWidth={720}>
          <Table highlightOnHover verticalSpacing="sm">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('integrations.monitoring.columns.name')}</Table.Th>
                <Table.Th>{t('integrations.monitoring.columns.kind')}</Table.Th>
                <Table.Th>{t('integrations.monitoring.columns.group')}</Table.Th>
                <Table.Th>{t('integrations.monitoring.columns.lastEvent')}</Table.Th>
                <Table.Th>{t('integrations.monitoring.columns.token')}</Table.Th>
                <Table.Th w={110}>{t('integrations.monitoring.columns.enabled')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {list.map((s) => (
                <Table.Tr key={s.source.id} style={{ cursor: 'pointer' }} onClick={() => setEditing(s)}>
                  <Table.Td>
                    <Anchor component="button" type="button" onClick={() => setEditing(s)}>
                      {s.source.name}
                    </Anchor>
                    {s.requesterName && (
                      <Text size="xs" c="dimmed">
                        {t('integrations.monitoring.asUser', { name: s.requesterName })}
                      </Text>
                    )}
                  </Table.Td>
                  <Table.Td>
                    <Badge variant="light">{t(`integrations.kinds.${s.source.kind}`)}</Badge>
                  </Table.Td>
                  <Table.Td>{s.groupName ?? t('integrations.monitoring.routing')}</Table.Td>
                  <Table.Td>
                    <Text size="sm" c="dimmed">
                      {s.source.lastEventAt
                        ? new Date(s.source.lastEventAt).toLocaleString(i18n.language, {
                            dateStyle: 'short',
                            timeStyle: 'short',
                          })
                        : t('integrations.monitoring.never')}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm" ff="monospace">
                      {s.source.tokenHint}
                    </Text>
                  </Table.Td>
                  <Table.Td onClick={(e) => e.stopPropagation()}>
                    <Switch
                      aria-label={t('integrations.monitoring.enabled')}
                      checked={s.source.enabled}
                      disabled={!canEdit || update.isPending}
                      onChange={(e) => toggle(s, e.currentTarget.checked)}
                    />
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
      {editing && (
        <SourceModal
          current={editing === 'new' ? null : editing}
          canEdit={canEdit}
          onClose={() => setEditing(null)}
          onToken={(w) => {
            setEditing(null);
            setIssued(w);
          }}
        />
      )}
      {issued && <TokenModal issued={issued} onClose={() => setIssued(null)} />}
    </Stack>
  );
}
