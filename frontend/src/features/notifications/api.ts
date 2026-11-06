import './i18n';
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, etagOf, type CursorPage } from '@/shared/api/client';

/** Запись центра уведомлений (NTF-01). link — путь в интерфейсе. */
export interface Notification {
  id: string;
  kind: string;
  title: string;
  body: string;
  link: string | null;
  ticketId: string | null;
  read: boolean;
  createdAt: string;
}

export interface PushInfo {
  enabled: boolean;
  publicKey: string | null;
  subscriptions: number;
}

export interface Template {
  kind: string;
  locale: 'ru' | 'en';
  subject: string;
  body: string;
  custom: boolean;
  version: number;
  email: boolean;
  push: boolean;
  variables: string[];
  defaultSubject: string;
  defaultBody: string;
}

export interface Preview {
  subject: string;
  text: string;
  html: string;
}

export const notificationKeys = {
  all: ['notifications'] as const,
  list: (unread: boolean) => ['notifications', 'list', unread] as const,
  count: ['notifications', 'count'] as const,
  push: ['notifications', 'push'] as const,
  templates: ['notification-templates'] as const,
};

/** Счётчик непрочитанных. Обновляется опросом, пока нет WebSocket. */
export function useUnreadCount() {
  return useQuery({
    queryKey: notificationKeys.count,
    queryFn: ({ signal }) => api.get<{ count: number }>('/notifications/unread-count', { signal }),
    select: (r) => r.count,
    refetchInterval: 30_000,
  });
}

export function useNotifications(unread: boolean, enabled = true) {
  return useInfiniteQuery({
    queryKey: notificationKeys.list(unread),
    enabled,
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) =>
      api.get<CursorPage<Notification>>('/notifications', {
        query: { unread, cursor: pageParam, limit: 20 },
        signal,
      }),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  });
}

export function useMarkRead() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, read }: { id: string; read: boolean }) => api.post<void>(`/notifications/${id}/read`, { read }),
    onSettled: () => qc.invalidateQueries({ queryKey: notificationKeys.all }),
  });
}

export function useMarkAllRead() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api.post<{ updated: number }>('/notifications/read-all', {}),
    onSettled: () => qc.invalidateQueries({ queryKey: notificationKeys.all }),
  });
}

export function usePushInfo(enabled: boolean) {
  return useQuery({
    queryKey: notificationKeys.push,
    enabled,
    queryFn: ({ signal }) => api.get<PushInfo>('/notifications/push', { signal }),
    staleTime: 5 * 60_000,
  });
}

// ------------------------------------------------------------------ шаблоны (ADM)

export function useTemplates() {
  return useQuery({
    queryKey: notificationKeys.templates,
    queryFn: ({ signal }) => api.get<Template[]>('/notification-settings/templates', { signal }),
  });
}

export function useSaveTemplate() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (t: { kind: string; locale: string; subject: string; body: string; version: number }) =>
      api.put<Template>(
        `/notification-settings/templates/${t.kind}/${t.locale}`,
        { subject: t.subject, body: t.body },
        { ifMatch: etagOf(t.version) },
      ),
    onSuccess: () => qc.invalidateQueries({ queryKey: notificationKeys.templates }),
  });
}

export function useResetTemplate() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (t: { kind: string; locale: string }) =>
      api.delete<Template>(`/notification-settings/templates/${t.kind}/${t.locale}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: notificationKeys.templates }),
  });
}

export function previewTemplate(kind: string, locale: string, body: { subject: string; body: string; confidential: boolean }) {
  return api.post<Preview>(`/notification-settings/templates/${kind}/${locale}/preview`, body);
}
