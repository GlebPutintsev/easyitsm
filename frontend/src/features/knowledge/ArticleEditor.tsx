import { Alert, Button, Group, Select, SimpleGrid, Stack, Text, TextInput } from '@mantine/core';
import { useWindowEvent } from '@mantine/hooks';
import { IconAlertTriangle, IconArrowLeft, IconDeviceFloppy } from '@tabler/icons-react';
import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { ApiError } from '@/shared/api/client';
import { ListState } from '@/shared/ui/ListState';
import { AUDIENCES, useArticle, useUpdateArticle, type ArticleDetails, type Audience, type UpdateArticleInput } from './api';
import { ArticleActions } from './ArticleActions';
import { CategoryField } from './CategoryField';
import { MarkdownEditor } from './MarkdownEditor';
import { StatusBadge, notifyError, useDateFormat } from './ui';

const AUTOSAVE_MS = 2500;

interface Draft {
  title: string;
  body: string;
  categoryId: string | null;
  audience: Audience;
  groupId: string;
}

const draftOf = (a: ArticleDetails): Draft => ({
  title: a.title,
  body: a.body,
  categoryId: a.categoryId,
  audience: a.audience,
  groupId: a.audienceGroupId ?? '',
});

/** Изменения черновика относительно сохранённого. Группа без идентификатора пока не отправляется. */
export function patchOf(saved: Draft, draft: Draft): { input: UpdateArticleInput; next: Draft } | null {
  const input: UpdateArticleInput = {};
  const next = { ...saved };
  if (draft.title.trim() && draft.title !== saved.title) {
    input.title = draft.title;
    next.title = draft.title;
  }
  if (draft.body !== saved.body) {
    input.body = draft.body;
    next.body = draft.body;
  }
  if (draft.categoryId !== saved.categoryId) {
    if (draft.categoryId) input.categoryId = draft.categoryId;
    else input.clearCategory = true;
    next.categoryId = draft.categoryId;
  }
  const audienceChanged = draft.audience !== saved.audience || (draft.audience === 'group' && draft.groupId !== saved.groupId);
  if (audienceChanged && (draft.audience !== 'group' || draft.groupId.trim())) {
    input.audience = draft.audience;
    input.audienceGroupId = draft.audience === 'group' ? draft.groupId.trim() : undefined;
    next.audience = draft.audience;
    next.groupId = draft.groupId;
  }
  return Object.keys(input).length ? { input, next } : null;
}

/** Редактор статьи (10.12): автосохранение и кнопка, Ctrl+S, предупреждение о конфликте версий. */
export function ArticleEditor() {
  const { t } = useTranslation();
  const { id } = useParams();
  const article = useArticle(id, true);
  const [session, setSession] = useState(0);

  if (article.isPending) return <ListState state="loading" rows={8} />;
  if (article.error) {
    const notFound = article.error instanceof ApiError && article.error.status === 404;
    return notFound ? (
      <ListState state="empty" title={t('kb.article.notFound')} />
    ) : (
      <ListState state="error" error={article.error} onRetry={() => void article.refetch()} />
    );
  }
  if (!article.data.permissions.edit) {
    return (
      <Stack maw={720} mx="auto" py="md">
        <Alert color="yellow" icon={<IconAlertTriangle />} title={t('kb.editor.readOnly')}>
          {t(`kb.editor.readOnlyReason.${article.data.status}`, { defaultValue: t('states.forbidden') })}
        </Alert>
        <Button component={Link} to={`/agent/kb/${id}`} variant="default" w="fit-content">
          {t('kb.editor.back')}
        </Button>
      </Stack>
    );
  }
  return (
    <EditorForm
      key={`${article.data.id}-${session}`}
      article={article.data}
      onReload={() => void article.refetch().then(() => setSession((s) => s + 1))}
    />
  );
}

function EditorForm({ article, onReload }: { article: ArticleDetails; onReload: () => void }) {
  const { t } = useTranslation();
  const fmt = useDateFormat();
  const update = useUpdateArticle();
  const [draft, setDraft] = useState<Draft>(() => draftOf(article));
  const [saved, setSaved] = useState<Draft>(() => draftOf(article));
  const [savedAt, setSavedAt] = useState<Date | null>(null);
  const [conflict, setConflict] = useState(false);

  const pending = patchOf(saved, draft);
  const dirty = pending !== null || (!draft.title.trim() && draft.title !== saved.title);

  const save = useCallback(() => {
    const change = patchOf(saved, draft);
    if (!change || update.isPending || conflict) return;
    update.mutate(
      { article, input: change.input },
      {
        onSuccess: () => {
          setSaved(change.next);
          setSavedAt(new Date());
        },
        onError: (e) => {
          if (e instanceof ApiError && (e.code === 'version_conflict' || e.status === 412)) setConflict(true);
          else notifyError(t, e);
        },
      },
    );
  }, [saved, draft, article, update, conflict, t]);

  // Автосохранение через паузу после последней правки и после завершения предыдущего сохранения.
  // После ошибки (кроме конфликта) автосохранение ждёт следующей правки, чтобы не повторять ошибку по таймеру.
  const hasPending = pending !== null;
  useEffect(() => {
    if (!hasPending || update.isPending || conflict || update.isError) return;
    const timer = setTimeout(save, AUTOSAVE_MS);
    return () => clearTimeout(timer);
  }, [draft, hasPending, update.isPending, update.isError, conflict, save]);

  useWindowEvent('keydown', (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
      e.preventDefault();
      save();
    }
  });
  useWindowEvent('beforeunload', (e) => {
    if (dirty) e.preventDefault();
  });

  const set = <K extends keyof Draft>(key: K, value: Draft[K]) => {
    if (update.isError) update.reset();
    setDraft((d) => ({ ...d, [key]: value }));
  };

  const saveState = update.isPending
    ? t('kb.editor.saving')
    : dirty
      ? t('kb.editor.unsaved')
      : savedAt
        ? t('kb.editor.savedAt', { time: fmt.time(savedAt) })
        : t('kb.editor.allSaved');

  const newVersionHint =
    article.publishedVersionNo != null && article.currentVersionNo === article.publishedVersionNo && !savedAt;

  return (
    <Stack gap="md" maw={1400} mx="auto">
      <Group justify="space-between" gap="sm">
        <Group gap="sm">
          <Button component={Link} to={`/agent/kb/${article.id}`} variant="subtle" leftSection={<IconArrowLeft size={16} />} px="xs">
            {t('kb.editor.back')}
          </Button>
          <Text size="sm" c="dimmed">
            {article.key} · {t('kb.article.version', { no: article.currentVersionNo })}
          </Text>
          <StatusBadge status={article.status} />
        </Group>
        <Group gap="sm">
          <Text size="sm" c={dirty ? 'orange' : 'dimmed'} aria-live="polite">
            {saveState}
          </Text>
          <Button variant="default" leftSection={<IconDeviceFloppy size={16} />} onClick={save} disabled={!pending || conflict} loading={update.isPending}>
            {t('kb.editor.save')}
          </Button>
          <ArticleActions article={article} compact hideEdit disabled={dirty || update.isPending} />
        </Group>
      </Group>

      {conflict && (
        <Alert color="red" icon={<IconAlertTriangle />} title={t('kb.editor.conflictTitle')}>
          <Group justify="space-between">
            <Text size="sm">{t('kb.editor.conflictText')}</Text>
            <Button size="xs" color="red" variant="light" onClick={onReload}>
              {t('kb.editor.reload')}
            </Button>
          </Group>
        </Alert>
      )}
      {newVersionHint && (
        <Alert color="blue" p="xs">
          <Text size="sm">{t('kb.editor.newVersionHint')}</Text>
        </Alert>
      )}
      {article.reviewComment && article.status === 'draft' && (
        <Alert color="orange" title={t('kb.article.returned')} p="sm">
          {article.reviewComment}
        </Alert>
      )}

      <TextInput
        value={draft.title}
        onChange={(e) => set('title', e.currentTarget.value)}
        aria-label={t('kb.fields.title')}
        placeholder={t('kb.fields.title')}
        maxLength={255}
        size="xl"
        variant="unstyled"
        styles={{ input: { fontWeight: 600, fontSize: 28 } }}
        error={!draft.title.trim() ? t('kb.editor.titleRequired') : undefined}
      />
      <SimpleGrid cols={{ base: 1, sm: draft.audience === 'group' ? 3 : 2 }} maw={900}>
        <CategoryField value={draft.categoryId} onChange={(v) => set('categoryId', v)} />
        <Select
          label={t('kb.fields.audience')}
          value={draft.audience}
          onChange={(v) => v && set('audience', v as Audience)}
          data={AUDIENCES.map((a) => ({ value: a, label: t(`kb.audience.${a}`) }))}
          allowDeselect={false}
        />
        {draft.audience === 'group' && (
          <TextInput
            label={t('kb.fields.groupId')}
            description={t('kb.fields.groupIdHint')}
            value={draft.groupId}
            onChange={(e) => set('groupId', e.currentTarget.value)}
          />
        )}
      </SimpleGrid>
      <MarkdownEditor
        value={draft.body}
        onChange={(v) => set('body', v)}
        previewNote={t('kb.editor.previewFor', { audience: t(`kb.audience.${draft.audience}`) })}
      />
    </Stack>
  );
}
