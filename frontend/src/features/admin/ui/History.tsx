import { Badge, Button, Drawer, Group, ScrollArea, Stack, Table, Text } from '@mantine/core';
import { IconArrowBackUp, IconDeviceFloppy, IconHistory } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { usePersonName } from '@/shared/api/people';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import { type Change, type VersionSummary, getVersion, useDiff, useVersions } from '../api';
import classes from './admin.module.css';

export function formatTime(iso: string | null | undefined, locale: string) {
  if (!iso) return '—';
  return new Date(iso).toLocaleString(locale, { dateStyle: 'medium', timeStyle: 'short' });
}

function value(v: unknown): string {
  if (v === null || v === undefined) return '—';
  if (typeof v === 'string') return v;
  return JSON.stringify(v);
}

/** Сравнение двух версий настройки: путь, было, стало (ADM-06). */
export function DiffTable({ changes }: { changes: Change[] }) {
  const { t } = useTranslation();
  if (!changes.length) return <Text size="sm" className={classes.muted}>{t('admin.history.noChanges')}</Text>;
  return (
    <div className={classes.scroll}>
      <Table withTableBorder verticalSpacing={6} fz="sm">
        <Table.Thead>
          <Table.Tr>
            <Table.Th>{t('admin.history.path')}</Table.Th>
            <Table.Th>{t('admin.history.before')}</Table.Th>
            <Table.Th>{t('admin.history.after')}</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {changes.map((c) => (
            <Table.Tr key={c.path}>
              <Table.Td className={classes.mono}>
                {c.path}{' '}
                <Badge size="xs" variant="light" className={classes[c.kind]}>
                  {t(`admin.history.kind.${c.kind}`)}
                </Badge>
              </Table.Td>
              <Table.Td className={c.kind === 'added' ? classes.muted : classes.removed}>{value(c.before)}</Table.Td>
              <Table.Td className={c.kind === 'removed' ? classes.muted : classes.added}>{value(c.after)}</Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </div>
  );
}

function Author({ id }: { id: string | null }) {
  const name = usePersonName(id);
  const { t } = useTranslation();
  return <>{id ? name : t('admin.history.system')}</>;
}

export function VersionLine({ v }: { v: VersionSummary }) {
  const { t, i18n } = useTranslation();
  return (
    <Stack gap={2}>
      <Group gap={6}>
        <Text size="sm" fw={600}>
          {t('admin.history.version', { n: v.versionNo })}
        </Text>
        <Badge size="xs" variant="outline">
          {t(`admin.history.action.${v.action}`)}
        </Badge>
      </Group>
      <Text size="xs" className={classes.muted}>
        {formatTime(v.createdAt, i18n.language)} · <Author id={v.authorId} />
      </Text>
      {v.comment && <Text size="xs">{v.comment}</Text>}
    </Stack>
  );
}

interface HistoryProps {
  opened: boolean;
  onClose: () => void;
  entity: string;
  /** Нет — все объекты этого вида; пустая строка — настройка в одном экземпляре. */
  entityId?: string;
  title: string;
  /** Восстановить настройку из снимка. Нет — кнопки «Восстановить» нет. */
  onRestore?: (snapshot: unknown) => Promise<void>;
}

/** «История» экрана: версии настройки, сравнение с предыдущей и восстановление (10.16). */
export function HistoryDrawer({ opened, onClose, entity, entityId, title, onRestore }: HistoryProps) {
  const { t } = useTranslation();
  const versions = useVersions({ entity, entityId }, opened);
  const rows = versions.data?.pages.flatMap((p) => p.items) ?? [];
  const [selected, setSelected] = useState<string | null>(null);
  const current = selected ?? rows[0]?.id ?? null;
  const diff = useDiff(opened ? current : null);
  const [restoring, setRestoring] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const state = listStateOf(versions, rows.length);

  async function restore() {
    if (!current || !onRestore) return;
    setRestoring(true);
    setError(null);
    try {
      const v = await getVersion(current);
      await onRestore(v.snapshot);
      onClose();
    } catch (e) {
      setError(e);
    } finally {
      setRestoring(false);
    }
  }

  return (
    <Drawer opened={opened} onClose={onClose} position="right" size="xl" title={t('admin.history.title', { what: title })}>
      {state ? (
        <ListState state={state} error={versions.error} title={state === 'empty' ? t('admin.history.empty') : undefined} />
      ) : (
        <Group align="flex-start" wrap="nowrap" gap="md">
          <ScrollArea.Autosize mah="75vh" w={240} type="auto">
            <Stack gap={4}>
              {rows.map((v) => (
                <button type="button" key={v.id} className={classes.listItem} aria-current={v.id === current}
                  onClick={() => setSelected(v.id)}>
                  <VersionLine v={v} />
                </button>
              ))}
              {versions.hasNextPage && (
                <Button variant="subtle" size="xs" onClick={() => void versions.fetchNextPage()}>
                  {t('admin.more')}
                </Button>
              )}
            </Stack>
          </ScrollArea.Autosize>
          <Stack gap="sm" style={{ flex: 1, minWidth: 0 }}>
            <Text size="sm" className={classes.muted}>
              {diff.data?.from
                ? t('admin.history.comparedWith', { n: diff.data.from.versionNo })
                : t('admin.history.firstVersion')}
            </Text>
            {diff.isPending ? <ListState state="loading" rows={3} /> : <DiffTable changes={diff.data?.changes ?? []} />}
            {onRestore && current && current !== rows[0]?.id && (
              <Button variant="default" leftSection={<IconArrowBackUp size={16} />} loading={restoring}
                onClick={() => void restore()} w="fit-content">
                {t('admin.history.restore')}
              </Button>
            )}
            <ErrorLine error={error} />
          </Stack>
        </Group>
      )}
    </Drawer>
  );
}

interface SaveBarProps {
  dirty: boolean;
  saving?: boolean;
  onSave: () => void;
  onCancel: () => void;
  onHistory?: () => void;
  error?: unknown;
}

/** «Сохранить», «Отменить», «История» — обязательные кнопки каждого экрана «Управления» (10.16). */
export function SaveBar({ dirty, saving, onSave, onCancel, onHistory, error }: SaveBarProps) {
  const { t } = useTranslation();
  return (
    <Stack gap="xs">
      <ErrorLine error={error} />
      <Group gap="xs">
        <Button leftSection={<IconDeviceFloppy size={16} />} disabled={!dirty} loading={saving} onClick={onSave}>
          {t('admin.save')}
        </Button>
        <Button variant="default" disabled={!dirty || saving} onClick={onCancel}>
          {t('admin.cancel')}
        </Button>
        {onHistory && (
          <Button variant="subtle" leftSection={<IconHistory size={16} />} onClick={onHistory}>
            {t('admin.history.button')}
          </Button>
        )}
      </Group>
    </Stack>
  );
}
