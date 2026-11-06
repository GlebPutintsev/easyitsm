import './i18n';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, api, etagOf, newIdempotencyKey } from '@/shared/api/client';
import type { Priority, StatusCategory } from '@/shared/ui/TicketBadges';

/** Типы и запросы командного центра крупных инцидентов, контракт — docs/api/mim-contract.md. */

export type MajorState = 'requested' | 'active' | 'restored' | 'closed' | 'rejected';
export type MajorRole = 'manager' | 'tech_lead' | 'comms' | 'scribe';
export const ROLES: MajorRole[] = ['manager', 'tech_lead', 'comms', 'scribe'];
export type Audience = 'users' | 'management';
export type MajorFilter = 'open' | 'requested' | 'active' | 'closed' | 'all';

export interface TicketRef {
  id: string;
  number: string | null;
  title: string | null;
  statusCode: string | null;
  statusCategory: StatusCategory | null;
  priority: Priority | null;
  visible: boolean;
}

export interface ServiceRef {
  id: string;
  name: string;
}

/** Услуга CMDB, которая по связям зависит от КЕ инцидента; depth 0 — КЕ сама услуга. */
export interface CmdbService {
  id: string;
  name: string;
  layer: string;
  criticality: 'low' | 'medium' | 'high' | 'critical';
  depth: number;
}

export interface TimelineEvent {
  id: string;
  kind: string;
  at: string;
  text: string;
  actorId: string | null;
}

export interface Task {
  id: string;
  title: string;
  assigneeId: string | null;
  done: boolean;
  doneAt: string | null;
  createdAt: string;
}

export interface Update {
  id: string;
  audience: Audience;
  title: string;
  body: string;
  statusPage: boolean;
  nextUpdateAt: string | null;
  publishedAt: string;
  publishedBy: string | null;
}

export interface PageTarget {
  step: number;
  userId: string;
  notifiedAt: string;
  ackedAt: string | null;
}

export interface Page {
  id: string;
  name: string;
  channel: 'center' | 'email';
  step: number;
  steps: number;
  state: 'paging' | 'acknowledged' | 'exhausted' | 'cancelled';
  nextStepAt: string | null;
  ackedBy: string | null;
  ackedAt: string | null;
  createdAt: string;
  targets: PageTarget[];
}

export interface MajorIncident {
  id: string;
  ticket: TicketRef;
  state: MajorState;
  publicTitle: string;
  reason: string;
  hypothesis: string | null;
  services: ServiceRef[];
  declaredBy: string | null;
  declaredAt: string;
  approvedBy: string | null;
  approvedAt: string | null;
  restoredAt: string | null;
  closedAt: string | null;
  nextUpdateAt: string | null;
  roles: { role: MajorRole; userId: string | null }[];
  cmdbServices?: CmdbService[];
  timeline: TimelineEvent[];
  tasks: Task[];
  updates: Update[];
  pages: Page[];
  children: TicketRef[];
  pir: string | null;
  problem: TicketRef | null;
  permissions: { manage: boolean; approve: boolean; close: boolean };
  version: number;
}

export interface MajorRow {
  id: string;
  ticket: TicketRef;
  state: MajorState;
  publicTitle: string;
  services: ServiceRef[];
  managerId: string | null;
  declaredAt: string;
  restoredAt: string | null;
  closedAt: string | null;
  nextUpdateAt: string | null;
  children: number;
}

export interface Template {
  id: string;
  name: string;
  audience: Audience;
  title: string;
  body: string;
  position: number;
  active: boolean;
  version: number;
}

export interface Step {
  groupId: string | null;
  userId: string | null;
  waitMinutes: number;
}

export interface Chain {
  id: string;
  name: string;
  steps: Step[];
  active: boolean;
  version: number;
}

export interface StatusPage {
  majorIncidents: {
    id: string;
    title: string;
    state: 'active' | 'restored';
    services: ServiceRef[];
    declaredAt: string;
    restoredAt: string | null;
    nextUpdateAt: string | null;
    lastUpdate: Update | null;
  }[];
  announcements: { id: string; kind: 'info' | 'maintenance' | 'outage'; title: string; body: string; startsAt: string; endsAt: string | null }[];
  generatedAt: string;
}

export interface DeclareInput {
  ticketId: string;
  reason: string;
  managerId?: string | null;
  serviceIds?: string[];
  publicTitle?: string;
}

export interface PublishInput {
  audience: Audience;
  title: string;
  body: string;
  statusPage: boolean;
  nextUpdateAt: string | null;
}

const base = '/major-incidents';

export const majorApi = {
  list: (state: MajorFilter, signal?: AbortSignal) => api.get<MajorRow[]>(base, { query: { state }, signal }),
  get: (id: string, signal?: AbortSignal) => api.get<MajorIncident>(`${base}/${id}`, { signal }),
  /** Последний крупный инцидент заявки; нет — null. */
  byTicket: async (ticketId: string, signal?: AbortSignal) => {
    try {
      return await api.get<MajorIncident>(`${base}/by-ticket/${ticketId}`, { signal });
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) return null;
      throw e;
    }
  },
  declare: (input: DeclareInput) => api.post<MajorIncident>(base, input, { idempotencyKey: newIdempotencyKey() }),
  approve: (id: string) => api.post<MajorIncident>(`${base}/${id}/approve`, {}),
  reject: (id: string, reason: string) => api.post<MajorIncident>(`${base}/${id}/reject`, { reason }),
  patch: (m: MajorIncident, body: { hypothesis?: string; serviceIds?: string[]; publicTitle?: string; nextUpdateAt?: string }) =>
    api.patch<MajorIncident>(`${base}/${m.id}`, body, { ifMatch: etagOf(m.version) }),
  role: (id: string, role: MajorRole, userId: string | null) => api.put<MajorIncident>(`${base}/${id}/roles/${role}`, { userId }),
  event: (id: string, text: string) => api.post<MajorIncident>(`${base}/${id}/events`, { text }),
  task: (id: string, title: string, assigneeId: string | null) => api.post<MajorIncident>(`${base}/${id}/tasks`, { title, assigneeId }),
  taskDone: (id: string, taskId: string, done: boolean) => api.put<MajorIncident>(`${base}/${id}/tasks/${taskId}/done`, { done }),
  children: (id: string, incidentIds: string[]) => api.post<MajorIncident>(`${base}/${id}/children`, { incidentIds }),
  render: (id: string, templateId: string) =>
    api.post<{ title: string; body: string; audience: Audience }>(`${base}/${id}/render`, { templateId }),
  publish: (id: string, input: PublishInput) => api.post<MajorIncident>(`${base}/${id}/updates`, input),
  page: (id: string, input: { chainId?: string; steps?: Step[]; channel: 'center' | 'email' }) =>
    api.post<MajorIncident>(`${base}/${id}/pages`, input),
  cancelPage: (id: string, pageId: string) => api.post<MajorIncident>(`${base}/${id}/pages/${pageId}/cancel`, {}),
  ack: (pageId: string) => api.post<MajorIncident>(`${base}/pages/${pageId}/ack`, {}),
  restore: (id: string, comment: string) => api.post<MajorIncident>(`${base}/${id}/restore`, { comment: comment || null }),
  close: (id: string, createProblem: boolean, problemTitle: string) =>
    api.post<MajorIncident>(`${base}/${id}/close`, { createProblem, problemTitle: problemTitle || null }),
  pirDraft: (id: string) => api.get<{ pir: string }>(`${base}/${id}/pir-draft`),
  savePir: (m: MajorIncident, pir: string) => api.put<MajorIncident>(`${base}/${m.id}/pir`, { pir }, { ifMatch: etagOf(m.version) }),
  templates: (all: boolean, signal?: AbortSignal) => api.get<Template[]>('/mim/templates', { query: { all }, signal }),
  saveTemplate: (t: Partial<Template> & Pick<Template, 'name' | 'audience' | 'title' | 'body'>) =>
    t.id
      ? api.put<Template>(`/mim/templates/${t.id}`, t, { ifMatch: etagOf(t.version ?? 0) })
      : api.post<Template>('/mim/templates', t),
  chains: (all: boolean, signal?: AbortSignal) => api.get<Chain[]>('/mim/chains', { query: { all }, signal }),
  saveChain: (c: Partial<Chain> & Pick<Chain, 'name' | 'steps'>) =>
    c.id ? api.put<Chain>(`/mim/chains/${c.id}`, c, { ifMatch: etagOf(c.version ?? 0) }) : api.post<Chain>('/mim/chains', c),
  status: (signal?: AbortSignal) => api.get<StatusPage>('/status', { signal }),
};

export const majorKeys = {
  all: ['major'] as const,
  list: (state: MajorFilter) => ['major', 'list', state] as const,
  detail: (id: string) => ['major', 'detail', id] as const,
  byTicket: (ticketId: string) => ['major', 'by-ticket', ticketId] as const,
  templates: (all: boolean) => ['major', 'templates', all] as const,
  chains: (all: boolean) => ['major', 'chains', all] as const,
  status: ['major', 'status'] as const,
};

export function useMajorList(state: MajorFilter) {
  return useQuery({ queryKey: majorKeys.list(state), queryFn: ({ signal }) => majorApi.list(state, signal), refetchInterval: 60_000 });
}

/** Командный центр обновляется сам: в нём работают несколько человек одновременно. */
export function useMajor(id: string) {
  return useQuery({ queryKey: majorKeys.detail(id), queryFn: ({ signal }) => majorApi.get(id, signal), refetchInterval: 20_000 });
}

export function useMajorForTicket(ticketId: string | undefined) {
  return useQuery({
    queryKey: majorKeys.byTicket(ticketId ?? ''),
    queryFn: ({ signal }) => majorApi.byTicket(ticketId!, signal),
    enabled: !!ticketId,
    staleTime: 30_000,
  });
}

export function useTemplates(all = false, enabled = true) {
  return useQuery({ queryKey: majorKeys.templates(all), queryFn: ({ signal }) => majorApi.templates(all, signal), enabled, staleTime: 60_000 });
}

export function useChains(all = false, enabled = true) {
  return useQuery({ queryKey: majorKeys.chains(all), queryFn: ({ signal }) => majorApi.chains(all, signal), enabled, staleTime: 60_000 });
}

export function useStatusPage(enabled = true) {
  return useQuery({ queryKey: majorKeys.status, queryFn: ({ signal }) => majorApi.status(signal), enabled, refetchInterval: 60_000 });
}

/** Действие командного центра: ответ — свежая карточка, её кладём в кэш, чтобы следующий If-Match был верным. */
export function useMajorAction<V>(fn: (v: V) => Promise<MajorIncident>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: (m) => {
      qc.setQueryData(majorKeys.detail(m.id), m);
      void qc.invalidateQueries({ queryKey: ['major', 'list'] });
      void qc.invalidateQueries({ queryKey: majorKeys.byTicket(m.ticket.id) });
      void qc.invalidateQueries({ queryKey: majorKeys.status });
    },
  });
}

export function roleHolder(m: MajorIncident, role: MajorRole): string | null {
  return m.roles.find((r) => r.role === role)?.userId ?? null;
}

/** «1 ч 05 мин» от начала до конца или до сейчас. */
export function durationText(from: string, to: string | null, now: number, t: (k: string, o?: Record<string, unknown>) => string): string {
  const minutes = Math.max(0, Math.floor(((to ? Date.parse(to) : now) - Date.parse(from)) / 60_000));
  const h = Math.floor(minutes / 60);
  return h > 0 ? t('major.duration.hm', { h, m: String(minutes % 60).padStart(2, '0') }) : t('major.duration.m', { m: minutes });
}
