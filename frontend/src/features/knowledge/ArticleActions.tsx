import { Button, Checkbox, Group, List, Modal, Select, Stack, Text, TextInput, Textarea } from '@mantine/core';
import {
  IconArchive,
  IconArrowBackUp,
  IconCalendarCheck,
  IconEdit,
  IconHistory,
  IconRefreshAlert,
  IconRestore,
  IconSend,
  IconWorldUpload,
} from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { ApiError } from '@/shared/api/client';
import {
  AUDIENCES,
  useArticleAction,
  type ArticleAction,
  type ArticleDetails,
  type Audience,
  type SecretFinding,
} from './api';
import { Notice, dateInputToIso, monthsFromNow, notifyDone, notifyError } from './ui';

type Dialog = 'submit' | 'publish' | 'reject' | 'archive' | 'confirmReview' | null;

/**
 * Кнопки жизненного цикла статьи (10.12). Показываются по правам из ответа сервера,
 * но сервер проверяет их снова (ACC-01).
 */
export function ArticleActions({
  article,
  compact = false,
  hideEdit = false,
  disabled = false,
}: {
  article: ArticleDetails;
  compact?: boolean;
  /** В редакторе кнопка «Редактировать» не нужна */
  hideEdit?: boolean;
  /** Например, пока в редакторе есть несохранённые изменения */
  disabled?: boolean;
}) {
  const { t } = useTranslation();
  const p = disabled
    ? { ...article.permissions, submit: false, publish: false, reject: false, archive: false, unarchive: false, requestReview: false, confirmReview: false }
    : article.permissions;
  const [dialog, setDialog] = useState<Dialog>(null);
  const action = useArticleAction();

  const run = (name: ArticleAction, body?: Record<string, unknown>, done?: string) =>
    action.mutate(
      { article, action: name, body },
      {
        onSuccess: () => {
          setDialog(null);
          if (done) notifyDone(done);
        },
        onError: (e) => notifyError(t, e),
      },
    );

  const size = compact ? 'xs' : 'sm';
  return (
    <>
      <Group gap="xs">
        {p.edit && !hideEdit && (
          <Button size={size} component={Link} to={`/agent/kb/${article.id}/edit`} leftSection={<IconEdit size={16} />}>
            {t('kb.actions.edit')}
          </Button>
        )}
        {p.submit && (
          <Button size={size} variant="light" leftSection={<IconSend size={16} />} onClick={() => setDialog('submit')}>
            {t('kb.actions.submit')}
          </Button>
        )}
        {p.publish && (
          <Button size={size} leftSection={<IconWorldUpload size={16} />} onClick={() => setDialog('publish')}>
            {t('kb.actions.publish')}
          </Button>
        )}
        {p.reject && (
          <Button size={size} variant="default" leftSection={<IconArrowBackUp size={16} />} onClick={() => setDialog('reject')}>
            {t('kb.actions.reject')}
          </Button>
        )}
        {p.confirmReview && (
          <Button size={size} variant="light" leftSection={<IconCalendarCheck size={16} />} onClick={() => setDialog('confirmReview')}>
            {t('kb.actions.confirmReview')}
          </Button>
        )}
        {p.requestReview && (
          <Button
            size={size}
            variant="subtle"
            leftSection={<IconRefreshAlert size={16} />}
            loading={action.isPending && action.variables?.action === 'request-review'}
            onClick={() => run('request-review', undefined, t('kb.done.reviewRequested'))}
          >
            {t('kb.actions.requestReview')}
          </Button>
        )}
        {p.viewHistory && (
          <Button size={size} variant="subtle" component={Link} to={`/agent/kb/${article.id}/history`} leftSection={<IconHistory size={16} />}>
            {t('kb.actions.history')}
          </Button>
        )}
        {p.unarchive && (
          <Button
            size={size}
            variant="light"
            leftSection={<IconRestore size={16} />}
            loading={action.isPending && action.variables?.action === 'unarchive'}
            onClick={() => run('unarchive', undefined, t('kb.done.unarchived'))}
          >
            {t('kb.actions.unarchive')}
          </Button>
        )}
        {p.archive && (
          <Button size={size} variant="subtle" color="red" leftSection={<IconArchive size={16} />} onClick={() => setDialog('archive')}>
            {t('kb.actions.archive')}
          </Button>
        )}
      </Group>

      <SubmitDialog article={article} opened={dialog === 'submit'} onClose={() => setDialog(null)} />
      <PublishDialog
        article={article}
        opened={dialog === 'publish'}
        onClose={() => setDialog(null)}
        pending={action.isPending}
        onPublish={(body) => run('publish', body, t('kb.done.published'))}
      />
      <CommentDialog
        opened={dialog === 'reject'}
        title={t('kb.reject.title')}
        label={t('kb.reject.comment')}
        submitLabel={t('kb.actions.reject')}
        pending={action.isPending}
        onClose={() => setDialog(null)}
        onSubmit={(comment) => run('reject', { comment }, t('kb.done.rejected'))}
      />
      <Modal opened={dialog === 'confirmReview'} onClose={() => setDialog(null)} title={t('kb.confirmReview.title')}>
        <ReviewDateForm
          pending={action.isPending}
          submitLabel={t('kb.actions.confirmReview')}
          onSubmit={(reviewAt) => run('confirm-review', { reviewAt }, t('kb.done.reviewConfirmed'))}
        />
      </Modal>
      <Modal opened={dialog === 'archive'} onClose={() => setDialog(null)} title={t('kb.archive.title')}>
        <Stack>
          <Text size="sm">{t('kb.archive.text')}</Text>
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setDialog(null)}>
              {t('kb.actions.cancel')}
            </Button>
            <Button color="red" loading={action.isPending} onClick={() => run('archive', undefined, t('kb.done.archived'))}>
              {t('kb.actions.archive')}
            </Button>
          </Group>
        </Stack>
      </Modal>
    </>
  );
}

/** Отправка на проверку с проверкой секретов (10.12). */
function SubmitDialog({ article, opened, onClose }: { article: ArticleDetails; opened: boolean; onClose: () => void }) {
  const { t } = useTranslation();
  const action = useArticleAction();
  const [comment, setComment] = useState('');
  const [findings, setFindings] = useState<SecretFinding[]>([]);
  const [confirmed, setConfirmed] = useState(false);

  const close = () => {
    setFindings([]);
    setConfirmed(false);
    onClose();
  };

  const submit = () =>
    action.mutate(
      { article, action: 'submit', body: { comment: comment || undefined, confirmNoSecrets: confirmed } },
      {
        onSuccess: () => {
          notifyDone(t('kb.done.submitted'));
          setComment('');
          close();
        },
        onError: (e) => {
          if (e instanceof ApiError && e.code === 'secrets_detected') {
            setFindings((e.problem.findings as SecretFinding[] | undefined) ?? []);
          } else {
            notifyError(t, e);
          }
        },
      },
    );

  return (
    <Modal opened={opened} onClose={close} title={t('kb.submit.title')}>
      <Stack>
        <Text size="sm" c="dimmed">
          {article.fourEyes ? t('kb.submit.fourEyes') : t('kb.submit.anyReviewer')}
        </Text>
        <Textarea label={t('kb.submit.comment')} value={comment} onChange={(e) => setComment(e.currentTarget.value)} autosize minRows={2} maxLength={2000} />
        {findings.length > 0 && (
          <Notice tone="danger" title={t('kb.submit.secretsTitle')}>
            <Stack gap="xs">
              <Text size="sm">{t('kb.submit.secretsText')}</Text>
              <List size="sm">
                {findings.map((f, i) => (
                  <List.Item key={i}>
                    {t(f.line === 0 ? 'kb.submit.findingInTitle' : 'kb.submit.finding', {
                      kind: t(`kb.secret.${f.kind}`, { defaultValue: f.kind }),
                      line: f.line,
                    })}
                  </List.Item>
                ))}
              </List>
              <Checkbox label={t('kb.submit.confirmNoSecrets')} checked={confirmed} onChange={(e) => setConfirmed(e.currentTarget.checked)} />
            </Stack>
          </Notice>
        )}
        <Group justify="flex-end">
          <Button variant="default" onClick={close}>
            {t('kb.actions.cancel')}
          </Button>
          <Button onClick={submit} loading={action.isPending} disabled={findings.length > 0 && !confirmed}>
            {t('kb.actions.submit')}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}

function PublishDialog({
  article,
  opened,
  onClose,
  onPublish,
  pending,
}: {
  article: ArticleDetails;
  opened: boolean;
  onClose: () => void;
  onPublish: (body: Record<string, unknown>) => void;
  pending: boolean;
}) {
  const { t } = useTranslation();
  const [audience, setAudience] = useState<Audience>(article.audience);
  const [groupId, setGroupId] = useState(article.audienceGroupId ?? '');
  const [reviewDate, setReviewDate] = useState(monthsFromNow(12));
  const [notes, setNotes] = useState('');

  return (
    <Modal opened={opened} onClose={onClose} title={t('kb.publish.title')}>
      <Stack>
        <Select
          label={t('kb.fields.audience')}
          value={audience}
          onChange={(v) => v && setAudience(v as Audience)}
          data={AUDIENCES.map((a) => ({ value: a, label: t(`kb.audience.${a}`) }))}
          allowDeselect={false}
        />
        {audience === 'group' && (
          <TextInput label={t('kb.fields.groupId')} description={t('kb.fields.groupIdHint')} value={groupId} onChange={(e) => setGroupId(e.currentTarget.value)} required />
        )}
        <TextInput type="date" label={t('kb.fields.reviewAt')} description={t('kb.publish.reviewHint')} value={reviewDate} onChange={(e) => setReviewDate(e.currentTarget.value)} />
        <Textarea label={t('kb.publish.notes')} placeholder={t('kb.publish.notesPlaceholder')} value={notes} onChange={(e) => setNotes(e.currentTarget.value)} autosize minRows={2} maxLength={2000} />
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            {t('kb.actions.cancel')}
          </Button>
          <Button
            loading={pending}
            disabled={audience === 'group' && !groupId.trim()}
            onClick={() =>
              onPublish({
                audience,
                audienceGroupId: audience === 'group' ? groupId.trim() : undefined,
                reviewAt: dateInputToIso(reviewDate),
                notes: notes || undefined,
              })
            }
          >
            {t('kb.actions.publish')}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}

function CommentDialog(props: {
  opened: boolean;
  title: string;
  label: string;
  submitLabel: string;
  pending: boolean;
  onClose: () => void;
  onSubmit: (comment: string) => void;
}) {
  const { t } = useTranslation();
  const [comment, setComment] = useState('');
  return (
    <Modal opened={props.opened} onClose={props.onClose} title={props.title}>
      <Stack>
        <Textarea label={props.label} value={comment} onChange={(e) => setComment(e.currentTarget.value)} autosize minRows={3} maxLength={2000} required data-autofocus />
        <Group justify="flex-end">
          <Button variant="default" onClick={props.onClose}>
            {t('kb.actions.cancel')}
          </Button>
          <Button loading={props.pending} disabled={!comment.trim()} onClick={() => props.onSubmit(comment.trim())}>
            {props.submitLabel}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}

function ReviewDateForm({ pending, submitLabel, onSubmit }: { pending: boolean; submitLabel: string; onSubmit: (iso?: string) => void }) {
  const { t } = useTranslation();
  const [date, setDate] = useState(monthsFromNow(12));
  return (
    <Stack>
      <Text size="sm">{t('kb.confirmReview.text')}</Text>
      <TextInput type="date" label={t('kb.fields.reviewAt')} value={date} onChange={(e) => setDate(e.currentTarget.value)} />
      <Group justify="flex-end">
        <Button loading={pending} onClick={() => onSubmit(dateInputToIso(date))}>
          {submitLabel}
        </Button>
      </Group>
    </Stack>
  );
}
