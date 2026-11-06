import { Button, Card, Group, Modal, NumberInput, Select, Stack, Switch, Table, Text, TextInput } from '@mantine/core';
import { IconPlus } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { hasRole, useMe } from '@/features/auth/api';
import { ApiError } from '@/shared/api/client';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { ListState } from '@/shared/ui/ListState';
import { SOURCE_KINDS, type Source, type SourceKind, useBatches, useSaveSource, useSources } from './api';
import { CmdbHeader, SectionLabel, ToneBadge, formatDateTime } from './ui';
import classes from './cmdb.module.css';

/** «CMDB → Источники» (CFG-04): откуда приходят данные, их приоритет и последние пакеты. */
export function SourcesPage() {
  const { t } = useTranslation();
  const me = useMe();
  const canConfigure = hasRole(me.data, 'CFG', 'ADM');
  const sources = useSources();
  const [editing, setEditing] = useState<Source | 'new' | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const list = sources.data ?? [];
  const add = canConfigure && (
    <Button leftSection={<IconPlus size={16} />} onClick={() => setEditing('new')}>
      {t('cmdb.sources.add')}
    </Button>
  );
  return (
    <Stack gap="md" maw={1280}>
      <CmdbHeader action={add} />
      <Text size="sm" c="var(--ei-text-2)" maw={760}>
        {t('cmdb.sources.intro')}
      </Text>
      {sources.isPending ? (
        <ListState state="loading" />
      ) : sources.isError ? (
        <ListState state="error" error={sources.error} onRetry={() => sources.refetch()} />
      ) : list.length === 0 ? (
        <ListState state="empty" title={t('cmdb.sources.empty')} action={add || undefined} />
      ) : (
        <Card withBorder padding={0} radius="lg" className={classes.tableCard}>
          <Table.ScrollContainer minWidth={760}>
            <Table highlightOnHover verticalSpacing={10} className={classes.table}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t('cmdb.sources.name')}</Table.Th>
                  <Table.Th>{t('cmdb.sources.kind')}</Table.Th>
                  <Table.Th>{t('cmdb.sources.priority')}</Table.Th>
                  <Table.Th>{t('cmdb.sources.records')}</Table.Th>
                  <Table.Th>{t('cmdb.sources.lastBatch')}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {list.map((s) => (
                  <Table.Tr
                    key={s.id}
                    className={classes.row}
                    tabIndex={0}
                    aria-selected={selected === s.code}
                    onClick={() => setSelected(s.code)}
                    onKeyDown={(k) => (k.key === 'Enter' ? setSelected(s.code) : undefined)}
                  >
                    <Table.Td>
                      <Group gap="xs">
                        <Text size="sm" fw={600}>
                          {s.name}
                        </Text>
                        {!s.active && <ToneBadge tone="neutral">{t('cmdb.sources.disabled')}</ToneBadge>}
                      </Group>
                      <Text size="xs" className={`${classes.muted} ei-mono`}>
                        {s.code}
                      </Text>
                    </Table.Td>
                    <Table.Td>{t(`cmdb.sources.kinds.${s.kind}`)}</Table.Td>
                    <Table.Td className="ei-mono">{s.priority}</Table.Td>
                    <Table.Td className="ei-mono">{s.records}</Table.Td>
                    <Table.Td className="ei-mono">{s.lastBatchAt ? formatDateTime(s.lastBatchAt) : t('cmdb.sources.never')}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Table.ScrollContainer>
        </Card>
      )}
      {selected && (
        <Batches
          source={list.find((s) => s.code === selected) ?? null}
          onEdit={canConfigure ? (s) => setEditing(s) : undefined}
        />
      )}
      <SourceForm value={editing} onClose={() => setEditing(null)} />
    </Stack>
  );
}

function Batches({ source, onEdit }: { source: Source | null; onEdit?: (s: Source) => void }) {
  const { t } = useTranslation();
  const batches = useBatches(source?.code ?? null);
  if (!source) return null;
  return (
    <div className={classes.panel}>
      <Stack gap="sm">
        <Group justify="space-between">
          <SectionLabel>
            {source.name} · {t('cmdb.sources.batches')}
          </SectionLabel>
          {onEdit && (
            <Button size="xs" variant="default" onClick={() => onEdit(source)}>
              {t('cmdb.card.edit')}
            </Button>
          )}
        </Group>
        {batches.isPending ? (
          <ListState state="loading" rows={2} />
        ) : batches.isError ? (
          <ListState state="error" error={batches.error} onRetry={() => batches.refetch()} />
        ) : batches.data.length === 0 ? (
          <Text size="sm" className={classes.muted}>
            {t('cmdb.sources.noBatches')}
          </Text>
        ) : (
          <ol className={classes.timeline}>
            {batches.data.map((b) => (
              <li key={b.batchId}>
                <Text size="sm" className="ei-mono">
                  {b.batchId}
                </Text>
                <Text size="xs" className={classes.muted}>
                  {formatDateTime(b.createdAt)} · {t('cmdb.sources.batchLine', { ...b })}
                </Text>
              </li>
            ))}
          </ol>
        )}
      </Stack>
    </div>
  );
}

function SourceForm({ value, onClose }: { value: Source | 'new' | null; onClose: () => void }) {
  const { t } = useTranslation();
  const source = value === 'new' ? null : value;
  return (
    <Modal
      opened={!!value}
      onClose={onClose}
      title={source ? t('cmdb.sources.editTitle', { name: source.name }) : t('cmdb.sources.createTitle')}
    >
      {value && <SourceFormBody source={source} onClose={onClose} />}
    </Modal>
  );
}

function SourceFormBody({ source, onClose }: { source: Source | null; onClose: () => void }) {
  const { t } = useTranslation();
  const save = useSaveSource(source);
  const [code, setCode] = useState(source?.code ?? '');
  const [name, setName] = useState(source?.name ?? '');
  const [kind, setKind] = useState<SourceKind>(source?.kind ?? 'discovery');
  const [priority, setPriority] = useState<number | ''>(source?.priority ?? 20);
  const [staleDays, setStaleDays] = useState<number | ''>(source?.staleDays ?? 30);
  const [active, setActive] = useState(source?.active ?? true);
  const fieldError = (field: string) =>
    save.error instanceof ApiError && (save.error.problem.field === field || save.error.problem.errors?.[field]) ? save.error.message : undefined;
  const submit = () => {
    const body = source
      ? { name, priority: priority === '' ? undefined : priority, staleDays: staleDays === '' ? undefined : staleDays, active }
      : { code, name, kind, priority: priority === '' ? undefined : priority, staleDays: staleDays === '' ? undefined : staleDays };
    save.mutate(body, { onSuccess: onClose });
  };
  return (
    <Stack gap="sm">
      <TextInput
        label={t('cmdb.sources.code')}
        description={t('cmdb.sources.codeHint')}
        required
        disabled={!!source}
        value={code}
        onChange={(e) => setCode(e.currentTarget.value)}
        error={fieldError('code')}
        className="ei-mono"
      />
      <TextInput label={t('cmdb.sources.name')} required value={name} onChange={(e) => setName(e.currentTarget.value)} error={fieldError('name')} />
      <Select
        label={t('cmdb.sources.kind')}
        disabled={!!source}
        value={kind}
        onChange={(v) => v && setKind(v as SourceKind)}
        allowDeselect={false}
        data={SOURCE_KINDS.map((k) => ({ value: k, label: t(`cmdb.sources.kinds.${k}`) }))}
      />
      <NumberInput
        label={t('cmdb.sources.priority')}
        description={t('cmdb.sources.priorityHint')}
        min={1}
        max={100}
        value={priority}
        onChange={(v) => setPriority(v === '' ? '' : Number(v))}
        error={fieldError('priority')}
      />
      <NumberInput label={t('cmdb.sources.staleDays')} min={1} max={3650} value={staleDays} onChange={(v) => setStaleDays(v === '' ? '' : Number(v))} />
      {source && <Switch label={t('cmdb.sources.active')} checked={active} onChange={(e) => setActive(e.currentTarget.checked)} />}
      <ErrorLine error={save.error && !fieldError('code') && !fieldError('name') && !fieldError('priority') ? save.error : null} />
      <Group justify="flex-end">
        <Button variant="default" onClick={onClose}>
          {t('cmdb.form.cancel')}
        </Button>
        <Button onClick={submit} loading={save.isPending} disabled={!name.trim() || (!source && !code.trim())}>
          {t(source ? 'cmdb.form.save' : 'cmdb.form.create')}
        </Button>
      </Group>
    </Stack>
  );
}
