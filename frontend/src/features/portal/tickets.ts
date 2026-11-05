import { keepPreviousData, useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, newIdempotencyKey, type CursorPage } from '@/shared/api/client';

/** Заявки для портала: REST модуля tickets (docs/api/tickets.yaml), только то, что видит заявитель. */

export type StatusCategory = 'draft' | 'new' | 'approval' | 'assigned' | 'in_progress' | 'pending' | 'resolved' | 'closed' | 'cancelled';

export interface TicketListItem {
  id: string;
  number: string;
  type: 'incident' | 'request' | 'problem' | 'change' | 'task';
  title: string;
  status: { code: string; name: string; category: StatusCategory };
  priority: string;
  requesterId: string;
  createdForId: string | null;
  assigneeId: string | null;
  catalogItemId: string | null;
  dueAt: string | null;
  pendingReason: string | null;
  resolutionText: string | null;
  closedAt: string | null;
  resolvedAt: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface AvailableTransition {
  to: string;
  name: string;
  category: StatusCategory;
  requiredFields: ('comment' | 'reason_code' | 'resolution' | 'pending_until' | 'group' | 'assignee')[];
}

export interface TicketDetails extends TicketListItem {
  description: string;
  transitions: AvailableTransition[];
  permissions: { comment?: boolean };
}

export interface Activity {
  id: string;
  kind: 'reply' | 'note' | 'system' | 'change';
  visibility: 'public' | 'internal';
  authorId: string | null;
  fromRequester?: boolean;
  body: string;
  meta: Record<string, unknown>;
  createdAt: string;
}

export type MyTicketsTab = 'active' | 'waiting' | 'closed';

export interface MyTicketsFilter {
  state: MyTicketsTab;
  q?: string;
  type?: string;
  sort?: '-created' | 'created' | '-updated';
  createdFrom?: string;
}

export const ticketKeys = {
  mine: (f: MyTicketsFilter | 'recent') => ['tickets', 'mine', f] as const,
  detail: (ref: string) => ['tickets', 'detail', ref] as const,
  activity: (id: string) => ['tickets', 'activity', id] as const,
};

export function useMyTickets(filter: MyTicketsFilter) {
  return useInfiniteQuery({
    queryKey: ticketKeys.mine(filter),
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) =>
      api.get<CursorPage<TicketListItem>>('/tickets', {
        query: {
          scope: 'mine',
          state: filter.state,
          q: filter.q || undefined,
          type: filter.type,
          sort: filter.sort,
          createdFrom: filter.createdFrom,
          cursor: pageParam,
          limit: 50,
        },
        signal,
      }),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    placeholderData: keepPreviousData,
  });
}

/** Блок «Мои заявки» на главной: 5 последних обновлённых. */
export function useRecentTickets(state?: MyTicketsTab, limit = 5) {
  return useQuery({
    queryKey: [...ticketKeys.mine('recent'), state ?? 'all', limit],
    queryFn: ({ signal }) =>
      api.get<CursorPage<TicketListItem>>('/tickets', { query: { scope: 'mine', state, sort: '-updated', limit }, signal }),
    select: (p) => p.items,
  });
}

export function useTicket(ref: string | undefined) {
  return useQuery({
    queryKey: ticketKeys.detail(ref ?? ''),
    queryFn: ({ signal }) => api.get<TicketDetails>(`/tickets/${ref}`, { signal }),
    enabled: !!ref,
  });
}

export function useActivity(ticketId: string | undefined) {
  return useInfiniteQuery({
    queryKey: ticketKeys.activity(ticketId ?? ''),
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) =>
      api.get<CursorPage<Activity>>(`/tickets/${ticketId}/activity`, {
        query: { visibility: 'public', cursor: pageParam, limit: 100 },
        signal,
      }),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    enabled: !!ticketId,
  });
}

export function useReply(ticketId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ body, secrets }: { body: string; secrets?: 'warn' | 'mask' }) =>
      api.post<Activity>(`/tickets/${ticketId}/activity`, { body, visibility: 'public', secrets }, { idempotencyKey: newIdempotencyKey() }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ticketKeys.activity(ticketId) });
      void qc.invalidateQueries({ queryKey: ['tickets', 'detail'] });
    },
  });
}

export function useTransition(ticketId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (req: { to: string; comment?: string; reasonCode?: string }) =>
      api.post<TicketDetails>(`/tickets/${ticketId}/transitions`, req, { idempotencyKey: newIdempotencyKey() }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['tickets'] });
      void qc.invalidateQueries({ queryKey: ['approvals'] });
    },
  });
}

export interface NewIncident {
  type: 'incident';
  title: string;
  description: string;
  impact: 'low' | 'medium' | 'high';
  createdForId?: string;
  channel: 'portal';
  secrets?: 'warn' | 'mask';
}

export function useCreateTicket(idempotencyKey: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input: NewIncident) => api.post<TicketDetails>('/tickets', input, { idempotencyKey }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['tickets'] }),
  });
}

export interface SecretFinding {
  kind: string;
  start: number;
  end: number;
  masked: string;
}

/** Проверка текста на секреты до отправки (TCK-17). Если сервис недоступен, отправка не блокируется. */
export async function scanSecrets(text: string): Promise<{ findings: SecretFinding[]; masked: string } | null> {
  if (!text.trim()) return null;
  try {
    return await api.post<{ findings: SecretFinding[]; masked: string }>('/tickets/secret-scan', { text });
  } catch {
    return null;
  }
}

export interface Survey {
  ticketId: string;
  score: number;
  comment: string | null;
  createdAt: string;
}

export function useSurvey(ticketId: string | undefined, enabled: boolean) {
  return useQuery({
    queryKey: ['surveys', ticketId],
    queryFn: ({ signal }) => api.get<Survey>(`/surveys/${ticketId}`, { signal }),
    enabled: !!ticketId && enabled,
    retry: false,
  });
}

export function useSubmitSurvey(ticketId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input: { score: number; comment?: string }) =>
      api.post<Survey>('/surveys', { ticketId, ...input }, { idempotencyKey: newIdempotencyKey() }),
    onSuccess: (s) => qc.setQueryData(['surveys', ticketId], s),
  });
}

/** Понятные заявителю шаги шкалы статуса (10.5): запрос и инцидент. */
export function progressSteps(type: TicketListItem['type'], category: StatusCategory, approval: boolean): StatusCategory[] {
  const base: StatusCategory[] = ['new', 'in_progress', 'resolved', 'closed'];
  if (type === 'request' && (approval || category === 'approval')) return ['approval', ...base];
  return base;
}
