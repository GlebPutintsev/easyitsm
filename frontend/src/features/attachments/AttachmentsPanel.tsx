import { ActionIcon, Badge, Checkbox, Group, Progress, Stack, Text, Tooltip } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconFile, IconPaperclip, IconShieldX, IconTrash } from '@tabler/icons-react';
import { useId, useRef, useState, type DragEvent, type KeyboardEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@/shared/api/client';
import {
  MAX_FILE_SIZE,
  downloadUrl,
  formatSize,
  previewUrl,
  useAttachments,
  useDeleteAttachment,
  useUploadAttachment,
  type Attachment,
  type OwnerType,
} from './api';
import './attachments.css';
import './i18n';

interface Props {
  ownerType: OwnerType;
  ownerId: string;
  /** Можно ли добавлять файлы (право комментировать, заявка не закрыта). */
  canUpload: boolean;
  /** Показать переключатель «внутренний файл» (поддержка с правом на внутренние записи). */
  canUploadInternal?: boolean;
  /** Плоский вид без заголовка: для портала, где панель стоит внутри карточки. */
  title?: boolean;
  /** Класс обёртки, например карточка портала; без файлов и без права загрузки панель не рисуется вовсе. */
  className?: string;
}

interface Pending {
  key: string;
  name: string;
  progress: number;
}

/** Файлы заявки или статьи: список, загрузка перетаскиванием и выбором, удаление своих (TCK-04, SEC-25). */
export function AttachmentsPanel({ ownerType, ownerId, canUpload, canUploadInternal = false, title = true, className }: Props) {
  const { t, i18n } = useTranslation();
  const list = useAttachments(ownerType, ownerId);
  const upload = useUploadAttachment(ownerType, ownerId);
  const remove = useDeleteAttachment(ownerType, ownerId);
  const input = useRef<HTMLInputElement>(null);
  const [over, setOver] = useState(false);
  const [internal, setInternal] = useState(false);
  const [pending, setPending] = useState<Pending[]>([]);
  const hintId = useId();

  const files = list.data ?? [];
  if (!canUpload && files.length === 0) return null;

  const send = (picked: FileList | null) => {
    for (const file of Array.from(picked ?? [])) {
      if (file.size > MAX_FILE_SIZE) {
        notifications.show({ color: 'red', message: t('attachments.tooLarge', { name: file.name }) });
        continue;
      }
      const key = crypto.randomUUID();
      setPending((p) => [...p, { key, name: file.name, progress: 0 }]);
      upload.mutate(
        {
          file,
          visibility: canUploadInternal && internal ? 'internal' : 'public',
          onProgress: (progress) => setPending((p) => p.map((x) => (x.key === key ? { ...x, progress } : x))),
        },
        {
          onError: (e) =>
            notifications.show({
              color: 'red',
              message: e instanceof ApiError && e.problem.detail ? e.problem.detail : t('attachments.uploadFailed', { name: file.name }),
            }),
          onSettled: () => setPending((p) => p.filter((x) => x.key !== key)),
        },
      );
    }
    if (input.current) input.current.value = '';
  };

  const onDrop = (e: DragEvent) => {
    e.preventDefault();
    setOver(false);
    send(e.dataTransfer.files);
  };

  const onKey = (e: KeyboardEvent) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      input.current?.click();
    }
  };

  const confirmDelete = (a: Attachment) => {
    if (window.confirm(t('attachments.deleteConfirm', { name: a.fileName }))) remove.mutate(a.id);
  };

  return (
    <Stack gap="xs" component="section" className={className} aria-label={t('attachments.title')}>
      {title && (
        <Group gap={6}>
          <IconPaperclip size={16} aria-hidden />
          <Text size="xs" c="dimmed" fw={500}>
            {t('attachments.title')}
            {files.length > 0 && ` · ${files.length}`}
          </Text>
        </Group>
      )}

      {files.length > 0 && (
        <ul className="ea-list">
          {files.map((a) => (
            <AttachmentRow key={a.id} a={a} lang={i18n.language} onDelete={() => confirmDelete(a)} />
          ))}
        </ul>
      )}

      {pending.map((p) => (
        <div key={p.key}>
          <Text size="xs" c="dimmed" mb={2} truncate>
            {t('attachments.uploading', { name: p.name })}
          </Text>
          <Progress value={p.progress * 100} size="sm" aria-label={t('attachments.uploading', { name: p.name })} />
        </div>
      ))}

      {canUpload && (
        <>
          <div
            className="ea-drop"
            role="button"
            tabIndex={0}
            aria-describedby={hintId}
            data-over={over || undefined}
            onClick={() => input.current?.click()}
            onKeyDown={onKey}
            onDragOver={(e) => {
              e.preventDefault();
              setOver(true);
            }}
            onDragLeave={() => setOver(false)}
            onDrop={onDrop}
          >
            <IconPaperclip size={20} className="ea-drop-icon" aria-hidden />
            <div>
              <div>{t('attachments.drop')}</div>
              <Text id={hintId} size="xs" c="dimmed">
                {t('attachments.hint')}
              </Text>
            </div>
          </div>
          <input
            ref={input}
            type="file"
            multiple
            hidden
            aria-label={t('attachments.add')}
            onChange={(e) => send(e.currentTarget.files)}
          />
          {canUploadInternal && (
            <Checkbox
              size="xs"
              label={t('attachments.internal')}
              checked={internal}
              onChange={(e) => setInternal(e.currentTarget.checked)}
            />
          )}
        </>
      )}
    </Stack>
  );
}

function AttachmentRow({ a, lang, onDelete }: { a: Attachment; lang: string; onDelete: () => void }) {
  const { t } = useTranslation();
  const quarantine = a.status === 'infected' || a.status === 'blocked';
  const ready = a.status === 'clean';

  return (
    <li className="ea-item" data-quarantine={quarantine || undefined}>
      {ready && a.previewable ? (
        <a href={previewUrl(a.id)} target="_blank" rel="noopener noreferrer" aria-label={t('attachments.preview', { name: a.fileName })}>
          <img className="ea-thumb" src={previewUrl(a.id)} alt="" loading="lazy" />
        </a>
      ) : (
        <span className="ea-icon" aria-hidden>
          {quarantine ? <IconShieldX size={20} /> : <IconFile size={20} />}
        </span>
      )}
      <div className="ea-name">
        {ready ? (
          <a href={downloadUrl(a.id)} aria-label={t('attachments.download', { name: a.fileName })}>
            {a.fileName}
          </a>
        ) : (
          <span className="ea-title">{a.fileName}</span>
        )}
        <div className="ea-meta" data-danger={quarantine || undefined}>
          {formatSize(a.size, lang)}
          {!ready && ` · ${t(`attachments.status.${a.status}`)}`}
          {quarantine && a.verdict && ` (${reason(a, t)})`}
        </div>
      </div>
      {a.visibility === 'internal' && (
        <Badge size="sm" variant="light" color="yellow">
          {t('attachments.internalBadge')}
        </Badge>
      )}
      {a.status === 'scanning' && (
        <Badge size="sm" variant="light" color="gray">
          {t('attachments.status.scanning')}
        </Badge>
      )}
      {a.canDelete && (
        <Tooltip label={t('attachments.delete', { name: a.fileName })}>
          <ActionIcon variant="subtle" color="gray" onClick={onDelete} aria-label={t('attachments.delete', { name: a.fileName })}>
            <IconTrash size={16} />
          </ActionIcon>
        </Tooltip>
      )}
    </li>
  );
}

/** Причина карантина: код правила «executable:application/x-msdownload» переводится, сигнатура антивируса — как есть. */
function reason(a: Attachment, t: (k: string, o?: Record<string, unknown>) => string): string {
  const verdict = a.verdict ?? '';
  if (a.status === 'infected') return verdict;
  const [code, detail] = verdict.split(':', 2);
  const text = t(`attachments.reasons.${code}`, { defaultValue: '' });
  if (!text) return verdict;
  return detail ? `${text}: ${detail}` : text;
}
