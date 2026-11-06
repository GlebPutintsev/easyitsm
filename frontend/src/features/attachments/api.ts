import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, newIdempotencyKey } from '@/shared/api/client';

/**
 * Клиент вложений (/api/v1/attachments). Файл грузится в три шага: сервер выдаёт подписанную ссылку, браузер
 * кладёт файл прямо в хранилище S3 на домене файлов, сервер ставит его на проверку. Скачивание и просмотр —
 * переадресация на короткую подписанную ссылку, поэтому в href достаточно адреса API.
 */

export type OwnerType = 'ticket' | 'article';
export type AttachmentStatus = 'awaiting_upload' | 'scanning' | 'clean' | 'infected' | 'blocked';

export interface Attachment {
  id: string;
  ownerType: OwnerType;
  ownerId: string;
  visibility: 'public' | 'internal';
  fileName: string;
  contentType: string;
  size: number;
  status: AttachmentStatus;
  previewable: boolean;
  /** Причина карантина: сигнатура антивируса или правило */
  verdict: string | null;
  sha256: string | null;
  scannedAt: string | null;
  uploadedBy: string | null;
  createdAt: string;
  canDelete: boolean;
  version: number;
}

interface Created {
  attachment: Attachment;
  upload: { url: string; method: string; headers: Record<string, string>; expiresAt: string };
}

/** Предел размера файла (TCK-04). Сервер проверяет его сам, здесь — чтобы не начинать заведомо лишнюю загрузку. */
export const MAX_FILE_SIZE = 100 * 1024 * 1024;

export const attachmentKeys = {
  owner: (type: OwnerType, id: string) => ['attachments', type, id] as const,
};

export const downloadUrl = (id: string) => `/api/v1/attachments/${id}/download`;
export const previewUrl = (id: string) => `/api/v1/attachments/${id}/preview`;

export function useAttachments(ownerType: OwnerType, ownerId: string | undefined) {
  return useQuery({
    queryKey: attachmentKeys.owner(ownerType, ownerId ?? ''),
    queryFn: ({ signal }) => api.get<Attachment[]>('/attachments', { query: { ownerType, ownerId: ownerId! }, signal }),
    enabled: Boolean(ownerId),
    // Пока файл проверяется, обновляем список: проверка занимает секунды.
    refetchInterval: (q) => (q.state.data?.some((a) => a.status === 'scanning') ? 3000 : false),
  });
}

/** PUT в хранилище с прогрессом: fetch прогресс отправки не сообщает. */
function put(url: string, headers: Record<string, string>, file: File, onProgress: (p: number) => void, signal?: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('PUT', url);
    for (const [k, v] of Object.entries(headers)) xhr.setRequestHeader(k, v);
    xhr.upload.onprogress = (e) => e.lengthComputable && onProgress(e.loaded / e.total);
    xhr.onload = () => (xhr.status >= 200 && xhr.status < 300 ? resolve() : reject(new Error(`HTTP ${xhr.status}`)));
    xhr.onerror = () => reject(new Error('network'));
    xhr.onabort = () => reject(new DOMException('aborted', 'AbortError'));
    signal?.addEventListener('abort', () => xhr.abort());
    xhr.send(file);
  });
}

export interface UploadInput {
  ownerType: OwnerType;
  ownerId: string;
  file: File;
  visibility: 'public' | 'internal';
  onProgress?: (fraction: number) => void;
  signal?: AbortSignal;
}

export async function uploadAttachment(input: UploadInput): Promise<Attachment> {
  const created = await api.post<Created>(
    '/attachments',
    {
      ownerType: input.ownerType,
      ownerId: input.ownerId,
      fileName: input.file.name,
      size: input.file.size,
      contentType: input.file.type || 'application/octet-stream',
      visibility: input.visibility,
    },
    { idempotencyKey: newIdempotencyKey() },
  );
  await put(created.upload.url, created.upload.headers, input.file, input.onProgress ?? (() => {}), input.signal);
  return api.post<Attachment>(`/attachments/${created.attachment.id}/complete`, {});
}

export function useUploadAttachment(ownerType: OwnerType, ownerId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input: Omit<UploadInput, 'ownerType' | 'ownerId'>) => uploadAttachment({ ...input, ownerType, ownerId }),
    onSettled: () => qc.invalidateQueries({ queryKey: attachmentKeys.owner(ownerType, ownerId) }),
  });
}

export function useDeleteAttachment(ownerType: OwnerType, ownerId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.delete(`/attachments/${id}`),
    onSettled: () => qc.invalidateQueries({ queryKey: attachmentKeys.owner(ownerType, ownerId) }),
  });
}

export function formatSize(bytes: number, lang: string): string {
  const units = lang.startsWith('ru') ? ['Б', 'КБ', 'МБ', 'ГБ'] : ['B', 'KB', 'MB', 'GB'];
  let v = bytes;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  const n = i === 0 ? String(v) : v.toLocaleString(lang, { maximumFractionDigits: v < 10 ? 1 : 0 });
  return `${n} ${units[i]}`;
}
