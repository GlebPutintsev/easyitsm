import { keepPreviousData, useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo } from 'react';
import { builtInViews } from './builtInViews';
import { ticketsApi } from './index';
import type { EscalationRequest, NewActivity, SavedView, Ticket, TicketPatch, TicketQuery, TransitionRequest } from './model';

export const ticketKeys = {
  all: ['tickets'] as const,
  list: (query: TicketQuery) => ['tickets', 'list', query] as const,
  changed: (query: TicketQuery, since: string) => ['tickets', 'changed', query, since] as const,
  detail: (number: string) => ['tickets', 'detail', number] as const,
  activity: (id: string) => ['tickets', 'activity', id] as const,
  requester: (id: string) => ['tickets', 'requester', id] as const,
  views: ['ticket-views'] as const,
  directory: ['agent-directory'] as const,
  templates: ['response-templates'] as const,
};

export function useTicketList(query: TicketQuery) {
  return useInfiniteQuery({
    queryKey: ticketKeys.list(query),
    queryFn: ({ signal, pageParam }) => ticketsApi.list({ ...query, cursor: pageParam }, signal),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    placeholderData: keepPreviousData,
    // Список не обновляется сам, чтобы строки не прыгали под курсором: новое показывает плашка.
    staleTime: Number.POSITIVE_INFINITY,
    refetchOnWindowFocus: false,
  });
}

/** Счётчик для плашки «Показать N новых» (10.6). WebSocket заменит опрос, когда появится. */
export function useChangedCount(query: TicketQuery, since: string | undefined) {
  return useQuery({
    queryKey: ticketKeys.changed(query, since ?? ''),
    queryFn: ({ signal }) => ticketsApi.countChangedSince(query, since ?? '', signal),
    enabled: Boolean(since),
    refetchInterval: 15_000,
    refetchIntervalInBackground: false,
  });
}

export function useTicket(number: string) {
  return useQuery({ queryKey: ticketKeys.detail(number), queryFn: ({ signal }) => ticketsApi.get(number, signal) });
}

export function useActivity(id: string | undefined) {
  return useQuery({
    queryKey: ticketKeys.activity(id ?? ''),
    queryFn: ({ signal }) => ticketsApi.activity(id ?? '', signal),
    enabled: Boolean(id),
  });
}

export function useRequesterTickets(ticket: Ticket | undefined) {
  return useQuery({
    queryKey: ticketKeys.requester(ticket?.requester.id ?? ''),
    queryFn: ({ signal }) => ticketsApi.requesterTickets(ticket?.requester.id ?? '', ticket?.id ?? '', signal),
    enabled: Boolean(ticket),
  });
}

/** Встроенные и сохранённые представления одним списком. */
export function useViews() {
  const directory = useDirectory();
  const saved = useQuery({ queryKey: ticketKeys.views, queryFn: ({ signal }) => ticketsApi.views(signal), staleTime: 5 * 60_000 });
  const views = useMemo(() => [...builtInViews(directory.data), ...(saved.data ?? [])], [directory.data, saved.data]);
  return { views, ready: directory.isSuccess, saved };
}

export function useSaveView() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (view: Omit<SavedView, 'id' | 'builtIn'>) => ticketsApi.saveView(view, crypto.randomUUID()),
    onSuccess: () => qc.invalidateQueries({ queryKey: ticketKeys.views }),
  });
}

export function useDeleteView() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => ticketsApi.deleteView(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ticketKeys.views }),
  });
}

export function useDirectory() {
  return useQuery({ queryKey: ticketKeys.directory, queryFn: ({ signal }) => ticketsApi.directory(signal), staleTime: 10 * 60_000 });
}

export function useTemplates() {
  return useQuery({ queryKey: ticketKeys.templates, queryFn: ({ signal }) => ticketsApi.templates(signal), staleTime: 10 * 60_000 });
}

/** После изменения карточки кладём свежую версию в кэш, чтобы следующий If-Match был верным. */
function useTicketUpdated() {
  const qc = useQueryClient();
  return (ticket: Ticket) => {
    qc.setQueryData(ticketKeys.detail(ticket.number), ticket);
    void qc.invalidateQueries({ queryKey: ticketKeys.activity(ticket.id) });
    void qc.invalidateQueries({ queryKey: ['tickets', 'list'] });
  };
}

export function useUpdateTicket(ticket: Ticket | undefined) {
  const updated = useTicketUpdated();
  return useMutation({
    mutationFn: (patch: TicketPatch) => {
      if (!ticket) throw new Error('ticket not loaded');
      return ticketsApi.update(ticket.id, patch, ticket.version);
    },
    onSuccess: updated,
  });
}

export function useTransition(ticket: Ticket | undefined) {
  const updated = useTicketUpdated();
  return useMutation({
    mutationFn: (request: TransitionRequest) => {
      if (!ticket) throw new Error('ticket not loaded');
      return ticketsApi.transition(ticket.id, request, ticket.version);
    },
    onSuccess: updated,
  });
}

export function useTake(ticket: Ticket | undefined) {
  const updated = useTicketUpdated();
  return useMutation({
    mutationFn: () => {
      if (!ticket) throw new Error('ticket not loaded');
      return ticketsApi.take(ticket.id, ticket.version);
    },
    onSuccess: updated,
  });
}

export function useEscalate(ticket: Ticket | undefined) {
  const updated = useTicketUpdated();
  return useMutation({
    mutationFn: (request: EscalationRequest) => {
      if (!ticket) throw new Error('ticket not loaded');
      return ticketsApi.escalate(ticket.id, request, ticket.version);
    },
    onSuccess: updated,
  });
}

export function useAddActivity(ticket: Ticket | undefined) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ activity, idempotencyKey }: { activity: NewActivity; idempotencyKey: string }) => {
      if (!ticket) throw new Error('ticket not loaded');
      return ticketsApi.addActivity(ticket.id, activity, idempotencyKey);
    },
    onSuccess: () => {
      if (!ticket) return;
      void qc.invalidateQueries({ queryKey: ticketKeys.activity(ticket.id) });
      void qc.invalidateQueries({ queryKey: ticketKeys.detail(ticket.number) });
      void qc.invalidateQueries({ queryKey: ['tickets', 'list'] });
    },
  });
}
