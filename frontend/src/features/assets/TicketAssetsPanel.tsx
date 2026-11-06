import { Anchor, Group, Paper, Stack, Text } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { hasRole, useMe } from '@/features/auth/api';
import { api, type CursorPage } from '@/shared/api/client';
import { type AssetRow, useTicketAssets } from './api';
import { AssetStateBadge } from './ui';

/**
 * Блок правой панели карточки заявки (AST-05): оборудование, связанное с заявкой, и выданное заявителю.
 * Виден только ролям, которые читают активы; без данных не занимает места.
 */
export function TicketAssetsPanel({ ticketId, requesterId }: { ticketId: string; requesterId: string | null | undefined }) {
  const { t } = useTranslation();
  const me = useMe();
  const allowed = hasRole(me.data, 'AST', 'ADM', 'AGT', 'LEAD', 'CFG', 'AUD');
  const linked = useTicketAssets(allowed ? ticketId : undefined);
  const held = useQuery({
    queryKey: ['assets', 'list', { holderId: requesterId }],
    queryFn: ({ signal }) =>
      api.get<CursorPage<AssetRow>>('/assets', { query: { holderId: requesterId ?? undefined, limit: 10 }, signal }).then((p) => p.items),
    enabled: allowed && !!requesterId,
  });
  const linkedRows = linked.data ?? [];
  const heldRows = (held.data ?? []).filter((a) => !linkedRows.some((l) => l.id === a.id));
  if (!allowed || (linkedRows.length === 0 && heldRows.length === 0)) return null;

  const list = (rows: AssetRow[]) => (
    <Stack gap={4}>
      {rows.map((a) => (
        <Group key={a.id} gap={6} wrap="nowrap" justify="space-between">
          <Group gap={6} wrap="nowrap" style={{ minWidth: 0 }}>
            <Anchor component={Link} to={`/agent/assets/${a.id}`} size="xs" ff="monospace">
              {a.tag}
            </Anchor>
            <Text size="xs" lineClamp={1}>
              {a.name}
            </Text>
          </Group>
          <AssetStateBadge state={a.state} />
        </Group>
      ))}
    </Stack>
  );

  return (
    <Paper withBorder p="sm" radius="md">
      <Text size="xs" c="dimmed" fw={500} mb={4}>
        {t('assets.ticketPanel.title')}
      </Text>
      {linkedRows.length > 0 && (
        <>
          <Text size="xs" c="dimmed" mb={2}>
            {t('assets.ticketPanel.linked')}
          </Text>
          {list(linkedRows)}
        </>
      )}
      {heldRows.length > 0 && (
        <>
          <Text size="xs" c="dimmed" mt={linkedRows.length ? 'xs' : 0} mb={2}>
            {t('assets.ticketPanel.requester')}
          </Text>
          {list(heldRows)}
        </>
      )}
    </Paper>
  );
}
