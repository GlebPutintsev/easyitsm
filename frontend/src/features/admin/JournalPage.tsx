import { Button, Drawer, Group, Select, Stack, Table, Text, TextInput, Title } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { usePersonName } from '@/shared/api/people';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import { type VersionSummary, useDiff, useVersions } from './api';
import { DiffTable, VersionLine, formatTime } from './ui/History';
import classes from './ui/admin.module.css';

const AREAS = ['admin', 'identity', 'sla', 'tickets', 'catalog'];
const ACTIONS = ['create', 'update', 'delete', 'restore', 'import', 'rollback', 'setup'];

function Author({ id }: { id: string | null }) {
  const name = usePersonName(id);
  const { t } = useTranslation();
  return <>{id ? name : t('admin.history.system')}</>;
}

/** Журнал действий администратора и сравнение версий (ADM-06): видят ADM, SEC и AUD. */
export function JournalPage() {
  const { t, i18n } = useTranslation();
  const [area, setArea] = useState<string | null>(null);
  const [action, setAction] = useState<string | null>(null);
  const [entity, setEntity] = useState('');
  const [debounced] = useDebouncedValue(entity.trim(), 300);
  const versions = useVersions({ entity: debounced || area || undefined, action: action ?? undefined });
  const rows = versions.data?.pages.flatMap((p) => p.items) ?? [];
  const state = listStateOf(versions, rows.length, !!(area || action || debounced));
  const [open, setOpen] = useState<VersionSummary | null>(null);
  const diff = useDiff(open?.id ?? null);

  return (
    <Stack gap="md" maw={1200}>
      <Title order={2}>{t('admin.journal.title')}</Title>
      <Group gap="xs">
        <Select placeholder={t('admin.journal.allAreas')} aria-label={t('admin.journal.area')} clearable value={area}
          onChange={setArea} data={AREAS.map((a) => ({ value: a, label: t(`admin.journal.areas.${a}`) }))} />
        <Select placeholder={t('admin.journal.allActions')} aria-label={t('admin.journal.action')} clearable value={action}
          onChange={setAction} data={ACTIONS.map((a) => ({ value: a, label: t(`admin.history.action.${a}`) }))} />
        <TextInput placeholder={t('admin.journal.entity')} aria-label={t('admin.journal.entity')} value={entity}
          onChange={(e) => setEntity(e.currentTarget.value)} className={classes.mono} />
      </Group>
      {state ? (
        <ListState state={state} error={versions.error} title={state === 'empty' ? t('admin.journal.empty') : undefined}
          onResetFilters={() => { setArea(null); setAction(null); setEntity(''); }} />
      ) : (
        <div className={classes.scroll}>
          <Table highlightOnHover verticalSpacing={8} miw={760}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('admin.journal.when')}</Table.Th>
                <Table.Th>{t('admin.journal.who')}</Table.Th>
                <Table.Th>{t('admin.journal.what')}</Table.Th>
                <Table.Th>{t('admin.journal.action')}</Table.Th>
                <Table.Th>{t('admin.journal.versionCol')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((v) => (
                <Table.Tr key={v.id} style={{ cursor: 'pointer' }} onClick={() => setOpen(v)} tabIndex={0}
                  onKeyDown={(e) => e.key === 'Enter' && setOpen(v)}>
                  <Table.Td>{formatTime(v.createdAt, i18n.language)}</Table.Td>
                  <Table.Td><Author id={v.authorId} /></Table.Td>
                  <Table.Td>
                    <Text size="sm">{v.label || v.entityId || '—'}</Text>
                    <Text size="xs" className={classes.mono}>{v.entity}</Text>
                  </Table.Td>
                  <Table.Td>{t(`admin.history.action.${v.action}`)}</Table.Td>
                  <Table.Td className={classes.mono}>{v.versionNo}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
          {versions.hasNextPage && (
            <Button variant="subtle" mt="sm" onClick={() => void versions.fetchNextPage()} loading={versions.isFetchingNextPage}>
              {t('admin.more')}
            </Button>
          )}
        </div>
      )}
      <Drawer opened={!!open} onClose={() => setOpen(null)} position="right" size="xl"
        title={open ? open.label || open.entity : ''}>
        {open && (
          <Stack gap="sm">
            <VersionLine v={open} />
            <Text size="sm" className={classes.muted}>
              {diff.data?.from ? t('admin.history.comparedWith', { n: diff.data.from.versionNo }) : t('admin.history.firstVersion')}
            </Text>
            {diff.isPending ? <ListState state="loading" rows={3} /> : <DiffTable changes={diff.data?.changes ?? []} />}
          </Stack>
        )}
      </Drawer>
    </Stack>
  );
}
