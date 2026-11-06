import { Alert, Badge, Button, Checkbox, FileInput, Group, SegmentedControl, Stack, Table, Text, Title } from '@mantine/core';
import { IconArrowBackUp, IconCheck, IconUpload } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import { type ImportJob, type ImportKind, getImport, useImportMutations, useImports } from './api';
import { formatTime } from './ui/History';
import classes from './ui/admin.module.css';

const KINDS: ImportKind[] = ['users', 'groups', 'departments'];
const PREVIEW_LIMIT = 200;

/** Импорт CSV (ADM-04): файл → предпросмотр с проверкой каждой строки → применить → откатить. */
export function ImportPage() {
  const { t, i18n } = useTranslation();
  const [params] = useSearchParams();
  const [kind, setKind] = useState<ImportKind>((KINDS as string[]).includes(params.get('kind') ?? '') ? (params.get('kind') as ImportKind) : 'users');
  const [file, setFile] = useState<File | null>(null);
  const [job, setJob] = useState<ImportJob | null>(null);
  const [skipInvalid, setSkipInvalid] = useState(false);
  const m = useImportMutations();
  const history = useImports();
  const past = history.data?.items ?? [];

  async function upload() {
    if (!file) return;
    const content = await file.text();
    setJob(await m.upload.mutateAsync({ kind, fileName: file.name, content }));
  }

  return (
    <Stack gap="md" maw={1200}>
      <Title order={2}>{t('admin.import.title')}</Title>
      <Stack gap="sm" className={classes.panel}>
        <SegmentedControl value={kind} onChange={(v) => { setKind(v as ImportKind); setJob(null); }} w="fit-content"
          data={KINDS.map((k) => ({ value: k, label: t(`admin.import.kinds.${k}`) }))} />
        <Text size="sm" className={classes.muted}>{t(`admin.import.columns.${kind}`)}</Text>
        <Group align="flex-end" gap="xs">
          <FileInput label={t('admin.import.file')} accept=".csv,text/csv,text/plain" value={file} onChange={setFile}
            placeholder={t('admin.import.choose')} w={320} clearable />
          <Button leftSection={<IconUpload size={16} />} disabled={!file} loading={m.upload.isPending} onClick={() => void upload()}>
            {t('admin.import.check')}
          </Button>
        </Group>
        <ErrorLine error={m.upload.error} />
      </Stack>

      {job && <JobView job={job} skipInvalid={skipInvalid} setSkipInvalid={setSkipInvalid} onChange={setJob} />}

      <Stack gap="sm" className={classes.panel}>
        <Title order={3}>{t('admin.import.history')}</Title>
        {listStateOf(history, past.length) ? (
          <ListState state={listStateOf(history, past.length)!} error={history.error} rows={3} title={t('admin.import.noHistory')} />
        ) : (
          <div className={classes.scroll}>
            <Table highlightOnHover verticalSpacing={6} miw={600}>
              <Table.Tbody>
                {past.map((j) => (
                  <Table.Tr key={j.id} style={{ cursor: 'pointer' }} onClick={() => void getImport(j.id).then(setJob)}>
                    <Table.Td>{formatTime(j.createdAt, i18n.language)}</Table.Td>
                    <Table.Td>{t(`admin.import.kinds.${j.kind}`)}</Table.Td>
                    <Table.Td>{j.fileName || '—'}</Table.Td>
                    <Table.Td>{t('admin.import.rowsOf', { valid: j.validRows, total: j.totalRows })}</Table.Td>
                    <Table.Td><StatusBadge status={j.status} /></Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </div>
        )}
      </Stack>
    </Stack>
  );
}

function StatusBadge({ status }: { status: ImportJob['status'] }) {
  const { t } = useTranslation();
  const color = status === 'applied' ? 'green' : status === 'rolled_back' ? 'gray' : 'blue';
  return <Badge variant="light" color={color}>{t(`admin.import.status.${status}`)}</Badge>;
}

interface JobProps {
  job: ImportJob;
  skipInvalid: boolean;
  setSkipInvalid: (v: boolean) => void;
  onChange: (j: ImportJob) => void;
}

function JobView({ job, skipInvalid, setSkipInvalid, onChange }: JobProps) {
  const { t } = useTranslation();
  const m = useImportMutations();
  const rows = job.rows.slice(0, PREVIEW_LIMIT);
  const canApply = job.status === 'preview' && job.validRows > 0 && (job.errorRows === 0 || skipInvalid);

  return (
    <Stack gap="sm" className={classes.panel}>
      <Group justify="space-between" wrap="wrap">
        <Group gap="xs">
          <Title order={3}>{job.fileName || t(`admin.import.kinds.${job.kind}`)}</Title>
          <StatusBadge status={job.status} />
        </Group>
        <Group gap="md">
          <Text size="sm">{t('admin.import.summary', { valid: job.validRows, skip: job.skipRows, errors: job.errorRows })}</Text>
        </Group>
      </Group>
      {job.status === 'applied' && (
        <Alert color="green" icon={<IconCheck />}>
          {t('admin.import.applied', {
            users: job.result.user ?? 0, groups: job.result.group ?? 0,
            departments: job.result.department ?? 0, memberships: job.result.membership ?? 0,
          })}
        </Alert>
      )}
      {job.status === 'rolled_back' && (
        <Alert color="gray">
          {t('admin.import.rolledBack', {
            removed: job.result.removedUsers ?? 0, blocked: job.result.blockedUsers ?? 0,
          })}
        </Alert>
      )}
      <div className={classes.scroll}>
        <Table verticalSpacing={6} withTableBorder miw={720}>
          <Table.Thead>
            <Table.Tr>
              <Table.Th w={64}>{t('admin.import.line')}</Table.Th>
              {job.columns.map((c) => <Table.Th key={c}>{t(`admin.import.col.${c}`)}</Table.Th>)}
              <Table.Th>{t('admin.import.result')}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {rows.map((r) => (
              <Table.Tr key={r.line} className={r.action === 'error' ? classes.rowError : r.action === 'skip' ? classes.rowSkip : undefined}>
                <Table.Td className={classes.mono}>{r.line}</Table.Td>
                {job.columns.map((c) => <Table.Td key={c}>{r.values[c] || '—'}</Table.Td>)}
                <Table.Td>
                  <Text size="sm" fw={500}>{t(`admin.import.actions.${r.action}`)}</Text>
                  {[...r.errors, ...r.warnings].map((e) => <Text key={e} size="xs">{e}</Text>)}
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </div>
      {job.rows.length > PREVIEW_LIMIT && (
        <Text size="xs" className={classes.muted}>{t('admin.import.truncated', { shown: PREVIEW_LIMIT, total: job.rows.length })}</Text>
      )}
      <ErrorLine error={m.apply.error ?? m.rollback.error} />
      <Group gap="sm">
        {job.status === 'preview' && (
          <>
            {job.errorRows > 0 && (
              <Checkbox label={t('admin.import.skipInvalid')} checked={skipInvalid} onChange={(e) => setSkipInvalid(e.currentTarget.checked)} />
            )}
            <Button disabled={!canApply} loading={m.apply.isPending}
              onClick={() => m.apply.mutate({ id: job.id, skipInvalid }, { onSuccess: onChange })}>
              {t('admin.import.apply', { count: job.validRows })}
            </Button>
          </>
        )}
        {job.status === 'applied' && (
          <Button variant="default" color="red" leftSection={<IconArrowBackUp size={16} />} loading={m.rollback.isPending}
            onClick={() => m.rollback.mutate(job.id, { onSuccess: onChange })}>
            {t('admin.import.rollback')}
          </Button>
        )}
      </Group>
    </Stack>
  );
}
