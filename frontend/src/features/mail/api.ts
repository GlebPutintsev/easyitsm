import './i18n';
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, etagOf, type CursorPage } from '@/shared/api/client';

/** Настройки почтового канала (CHN-01, CHN-02), только ADM. */

export type Security = 'none' | 'starttls' | 'ssl';

export interface Transport {
  id: string | null;
  protocol: 'smtp' | 'graph';
  host: string | null;
  port: number | null;
  security: Security | null;
  username: string | null;
  secretSet: boolean;
  graphTenant: string | null;
  graphClientId: string | null;
  fromAddress: string;
  fromName: string | null;
  replyTo: string | null;
  enabled: boolean;
  version: number;
  /** Настройки из переменных окружения установки: арендатор ещё не сохранил свои. */
  fallback: boolean;
}

export interface TransportInput {
  protocol: 'smtp' | 'graph';
  host?: string | null;
  port?: number | null;
  security?: Security | null;
  username?: string | null;
  /** undefined — не менять, '' — стереть */
  secret?: string;
  graphTenant?: string | null;
  graphClientId?: string | null;
  fromAddress: string;
  fromName?: string | null;
  replyTo?: string | null;
  enabled: boolean;
}

export interface Mailbox {
  id: string;
  address: string;
  protocol: 'imap' | 'graph' | 'smtp';
  host: string | null;
  port: number | null;
  security: Security | null;
  username: string | null;
  secretSet: boolean;
  folder: string | null;
  graphTenant: string | null;
  graphClientId: string | null;
  ticketType: 'incident' | 'request';
  groupId: string | null;
  authPolicy: 'enforce' | 'strict' | 'monitor';
  trustedAuthserv: string | null;
  enabled: boolean;
  lastPolledAt: string | null;
  lastError: string | null;
  version: number;
}

export type MailboxInput = Omit<Mailbox, 'id' | 'secretSet' | 'lastPolledAt' | 'lastError' | 'version'> & {
  secret?: string;
};

export interface CheckResult {
  ok: boolean;
  error: string | null;
}

export interface Inbound {
  id: string;
  mailboxId: string | null;
  messageId: string | null;
  fromAddress: string | null;
  fromName: string | null;
  subject: string | null;
  receivedAt: string;
  spf: string | null;
  dkim: string | null;
  dmarc: string | null;
  verdict: string;
  detail: string | null;
  ticketId: string | null;
  canRelease: boolean;
  size: number;
}

export interface Outgoing {
  id: string;
  kind: string;
  recipient: string;
  ticketId: string | null;
  subject: string;
  status: 'pending' | 'sent' | 'failed' | 'suppressed';
  attempts: number;
  nextAttemptAt: string | null;
  lastError: string | null;
  sentAt: string | null;
  createdAt: string;
}

export interface Group {
  id: string;
  name: string;
}

const base = '/mail-settings';
export const mailKeys = {
  transport: ['mail', 'transport'] as const,
  mailboxes: ['mail', 'mailboxes'] as const,
  inbound: (verdict: string | null) => ['mail', 'inbound', verdict] as const,
  outgoing: (status: string | null) => ['mail', 'outgoing', status] as const,
  groups: ['mail', 'groups'] as const,
};

export function useTransport() {
  return useQuery({
    queryKey: mailKeys.transport,
    // 204 — отправка не настроена ни у арендатора, ни в переменных установки.
    queryFn: async ({ signal }) => (await api.get<Transport | undefined>(`${base}/transport`, { signal })) ?? null,
  });
}

export function useSaveTransport() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ input, current }: { input: TransportInput; current: Transport | null }) =>
      api.put<Transport>(`${base}/transport`, input, {
        ifMatch: current && !current.fallback ? etagOf(current.version) : undefined,
      }),
    onSuccess: (v) => qc.setQueryData(mailKeys.transport, v),
  });
}

export function testTransport(to?: string) {
  return api.post<CheckResult>(`${base}/transport/test`, { to: to || null });
}

export function useMailboxes() {
  return useQuery({
    queryKey: mailKeys.mailboxes,
    queryFn: ({ signal }) => api.get<Mailbox[]>(`${base}/mailboxes`, { signal }),
  });
}

export function useSaveMailbox() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ input, current, idempotencyKey }: { input: MailboxInput; current: Mailbox | null; idempotencyKey: string }) =>
      current
        ? api.put<Mailbox>(`${base}/mailboxes/${current.id}`, input, { ifMatch: etagOf(current.version) })
        : api.post<Mailbox>(`${base}/mailboxes`, input, { idempotencyKey }),
    onSuccess: () => qc.invalidateQueries({ queryKey: mailKeys.mailboxes }),
  });
}

export function useDeleteMailbox() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.delete(`${base}/mailboxes/${id}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: mailKeys.mailboxes }),
  });
}

export function checkMailbox(id: string) {
  return api.post<CheckResult>(`${base}/mailboxes/${id}/check`, {});
}

export function pollMailbox(id: string) {
  return api.post<{ processed: number; error: string }>(`${base}/mailboxes/${id}/poll`, {});
}

export function useInbound(verdict: string | null) {
  return useInfiniteQuery({
    queryKey: mailKeys.inbound(verdict),
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) =>
      api.get<CursorPage<Inbound>>(`${base}/inbound`, {
        query: { verdict: verdict ?? undefined, cursor: pageParam, limit: 50 },
        signal,
      }),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  });
}

export function useRelease() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.post<{ verdict: string; ticketId: string | null }>(`${base}/inbound/${id}/release`, {}),
    onSettled: () => qc.invalidateQueries({ queryKey: ['mail', 'inbound'] }),
  });
}

export function useOutgoing(status: string | null) {
  return useInfiniteQuery({
    queryKey: mailKeys.outgoing(status),
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) =>
      api.get<CursorPage<Outgoing>>(`${base}/outgoing`, {
        query: { status: status ?? undefined, cursor: pageParam, limit: 50 },
        signal,
      }),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  });
}

export function useRetry() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.post<void>(`${base}/outgoing/${id}/retry`, {}),
    onSettled: () => qc.invalidateQueries({ queryKey: ['mail', 'outgoing'] }),
  });
}

export function useGroups() {
  return useQuery({
    queryKey: mailKeys.groups,
    queryFn: ({ signal }) => api.get<CursorPage<Group>>('/groups', { query: { limit: 200 }, signal }),
    select: (p) => p.items,
    staleTime: 5 * 60_000,
  });
}
