import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, newIdempotencyKey } from '@/shared/api/client';
import {
  type SecretScanResult,
  type StatusCategory,
  type Ticket,
  type TicketFilter,
  type TicketSort,
  type TicketState,
  type TicketType,
  ticketKeys,
  ticketsApi,
  useTickets,
} from '@/features/tickets/api';

/**
 * То, что нужно порталу поверх клиента модуля заявок (features/tickets/api.ts): списки заявителя,
 * проверка секретов, опрос и понятные заявителю шаги статуса. Запросы и изменения заявок — только через
 * клиент модуля, чтобы If-Match, ключи идемпотентности и кэш были одни на всё приложение.
 */

export type MyTicketsTab = TicketState;

export interface MyTicketsFilter {
  state?: MyTicketsTab;
  q?: string;
  type?: string;
  createdFrom?: string;
}

/** «Мои» — я заявитель, получатель или наблюдатель (scope=mine). */
const mine = (f: MyTicketsFilter): TicketFilter => ({ scope: 'mine', ...f, q: f.q || undefined });

export function useMyTickets(filter: MyTicketsFilter, sort: TicketSort = '-updated') {
  return useTickets(mine(filter), sort);
}

/** Короткий список для главной: последние обновлённые. */
export function useRecentTickets(state?: MyTicketsTab, limit = 5) {
  const filter = mine({ state });
  return useQuery({
    queryKey: [...ticketKeys.list(filter, '-updated'), 'top', limit],
    queryFn: ({ signal }) => ticketsApi.list(filter, { sort: '-updated', limit }, signal),
    select: (p) => p.items,
  });
}

export function useTicketSearch(q: string, enabled: boolean) {
  const filter = mine({ q });
  return useQuery({
    queryKey: [...ticketKeys.list(filter, '-updated'), 'search'],
    queryFn: ({ signal }) => ticketsApi.list(filter, { sort: '-updated', limit: 5 }, signal),
    enabled,
    retry: false,
    select: (p) => p.items,
  });
}

/** Проверка текста на секреты до отправки (TCK-17). Если проверка недоступна, отправка не блокируется. */
export async function scanSecrets(text: string): Promise<SecretScanResult | null> {
  if (!text.trim()) return null;
  try {
    return await ticketsApi.secretScan(text);
  } catch {
    return null;
  }
}

// ------------------------------------------------------------------ опрос (модуль surveys)

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

// ------------------------------------------------------------------ шкала статуса

/** Понятные заявителю шаги шкалы статуса (10.5): запрос и инцидент. */
export function progressSteps(type: TicketType, category: StatusCategory, approval: boolean): StatusCategory[] {
  const base: StatusCategory[] = ['new', 'in_progress', 'resolved', 'closed'];
  if (type === 'request' && (approval || category === 'approval')) return ['approval', ...base];
  return base;
}

/** Номер текущего шага на шкале: «Назначена» — это ещё «Принята», «Ожидание» — всё ещё «В работе». */
export function progressIndex(steps: StatusCategory[], category: StatusCategory): number {
  if (category === 'closed' || category === 'cancelled') return steps.length;
  const mapped = category === 'assigned' || category === 'draft' ? 'new' : category === 'pending' ? 'in_progress' : category;
  return Math.max(steps.indexOf(mapped), 0);
}

/** Заявка ждёт действия заявителя: ответить на вопрос или подтвердить решение. */
export function waitsForRequester(t: Pick<Ticket, 'status'>): boolean {
  return t.status.category === 'pending' || t.status.category === 'resolved';
}
