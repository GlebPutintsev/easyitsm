import { Anchor, Box, Button, Code, Group, Modal, Paper, Radio, ScrollArea, Stack, Table, Text, Title } from '@mantine/core';
import { IconArrowLeft, IconRestore } from '@tabler/icons-react';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams } from 'react-router';
import { ListState } from '@/shared/ui/ListState';
import { useArticle, useRestoreVersion, useVersion, useVersions } from './api';
import { collapseUnchanged, lineDiff } from './lineDiff';
import { Notice, notifyDone, notifyError, useDateFormat } from './ui';

const rowClass = {
  added: 'kb-diff-added',
  removed: 'kb-diff-removed',
  same: undefined,
} as const;

/** История версий (KB-03): список, сравнение двух версий и восстановление. */
export function ArticleHistory() {
  const { t } = useTranslation();
  const { id = '' } = useParams();
  const fmt = useDateFormat();
  const navigate = useNavigate();
  const article = useArticle(id, true);
  const versions = useVersions(id);
  const restore = useRestoreVersion();
  const [left, setLeft] = useState<number | null>(null);
  const [right, setRight] = useState<number | null>(null);
  const [restoreNo, setRestoreNo] = useState<number | null>(null);

  const list = versions.data ?? [];
  // По умолчанию сравниваем предыдущую версию с текущей.
  const rightNo = right ?? list[0]?.versionNo ?? null;
  const leftNo = left ?? list[1]?.versionNo ?? null;
  const oldV = useVersion(id, leftNo);
  const newV = useVersion(id, rightNo);

  const rows = useMemo(() => {
    if (!oldV.data || !newV.data) return null;
    const title = oldV.data.title === newV.data.title ? [] : lineDiff(`# ${oldV.data.title}`, `# ${newV.data.title}`);
    return collapseUnchanged([...title, ...lineDiff(oldV.data.body, newV.data.body)]);
  }, [oldV.data, newV.data]);

  if (versions.isPending || article.isPending) return <ListState state="loading" rows={6} />;
  if (versions.error || article.error) {
    return <ListState state={versions.error ? 'error' : 'error'} error={versions.error ?? article.error} onRetry={() => void versions.refetch()} />;
  }

  const canRestore = article.data.permissions.restore;

  return (
    <Stack gap="md" maw={1200} mx="auto">
      <Group gap="sm">
        <Button component={Link} to={`/agent/kb/${id}`} variant="subtle" leftSection={<IconArrowLeft size={16} />} px="xs">
          {t('kb.history.back')}
        </Button>
        <Title order={3}>{t('kb.history.title', { title: article.data.title })}</Title>
      </Group>

      <Paper withBorder radius="md">
        <ScrollArea>
          <Table verticalSpacing="xs" highlightOnHover miw={640}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th w={70}>{t('kb.history.from')}</Table.Th>
                <Table.Th w={70}>{t('kb.history.to')}</Table.Th>
                <Table.Th>{t('kb.history.version')}</Table.Th>
                <Table.Th>{t('kb.history.notes')}</Table.Th>
                <Table.Th>{t('kb.history.changed')}</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {list.map((v) => (
                <Table.Tr key={v.versionNo}>
                  <Table.Td>
                    <Radio checked={leftNo === v.versionNo} onChange={() => setLeft(v.versionNo)} aria-label={t('kb.history.from')} />
                  </Table.Td>
                  <Table.Td>
                    <Radio checked={rightNo === v.versionNo} onChange={() => setRight(v.versionNo)} aria-label={t('kb.history.to')} />
                  </Table.Td>
                  <Table.Td>
                    <Group gap={6}>
                      <Text size="sm" fw={500}>
                        {t('kb.article.version', { no: v.versionNo })}
                      </Text>
                      {v.published && (
                        <span className="kb-badge" data-tone="ok">
                          {t('kb.history.published')}
                        </span>
                      )}
                      {v.current && (
                        <span className="kb-badge" data-tone="outline">
                          {t('kb.history.current')}
                        </span>
                      )}
                    </Group>
                    <Text size="xs" c="dimmed" lineClamp={1}>
                      {v.title}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm">{v.notes || '—'}</Text>
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm">{fmt.dateTime(v.updatedAt)}</Text>
                  </Table.Td>
                  <Table.Td>
                    {canRestore && !v.current && (
                      <Button size="xs" variant="subtle" leftSection={<IconRestore size={14} />} onClick={() => setRestoreNo(v.versionNo)}>
                        {t('kb.history.restore')}
                      </Button>
                    )}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </ScrollArea>
      </Paper>

      {list.length < 2 ? (
        <Text c="dimmed" size="sm">
          {t('kb.history.single')}
        </Text>
      ) : (
        <Stack gap="xs">
          <Text fw={500}>{t('kb.history.compare', { from: leftNo, to: rightNo })}</Text>
          {!rows ? (
            <ListState state="loading" rows={4} />
          ) : rows.every((r) => r.kind === 'same' || r.kind === 'gap') ? (
            <Notice tone="neutral">{t('kb.history.noChanges')}</Notice>
          ) : (
            <Paper withBorder radius="md" style={{ overflow: 'hidden' }}>
              <ScrollArea>
                <Box component="pre" m={0} style={{ fontFamily: 'var(--mantine-font-family-monospace)', fontSize: 13 }}>
                  {rows.map((r, i) =>
                    r.kind === 'gap' ? (
                      <Box key={i} px="sm" py={2} className="kb-diff-same">
                        {t('kb.history.unchanged', { count: r.count })}
                      </Box>
                    ) : (
                      <Box key={i} px="sm" className={rowClass[r.kind]} style={{ whiteSpace: 'pre-wrap' }}>
                        <Text span c="dimmed" inherit>
                          {r.kind === 'added' ? '+ ' : r.kind === 'removed' ? '− ' : '  '}
                        </Text>
                        {r.text || ' '}
                      </Box>
                    ),
                  )}
                </Box>
              </ScrollArea>
            </Paper>
          )}
          <Text size="xs" c="dimmed">
            <Code>+</Code> {t('kb.history.legendAdded')} · <Code>−</Code> {t('kb.history.legendRemoved')}
          </Text>
        </Stack>
      )}

      <Modal opened={restoreNo != null} onClose={() => setRestoreNo(null)} title={t('kb.history.restoreTitle', { no: restoreNo })}>
        <Stack>
          <Text size="sm">{t('kb.history.restoreText')}</Text>
          <Group justify="flex-end">
            <Anchor component="button" size="sm" onClick={() => setRestoreNo(null)}>
              {t('kb.actions.cancel')}
            </Anchor>
            <Button
              loading={restore.isPending}
              onClick={() =>
                restore.mutate(
                  { article: article.data, versionNo: restoreNo! },
                  {
                    onSuccess: (a) => {
                      setRestoreNo(null);
                      notifyDone(t('kb.done.restored', { no: a.currentVersionNo }));
                      navigate(`/agent/kb/${id}/edit`);
                    },
                    onError: (e) => notifyError(t, e),
                  },
                )
              }
            >
              {t('kb.history.restore')}
            </Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  );
}
