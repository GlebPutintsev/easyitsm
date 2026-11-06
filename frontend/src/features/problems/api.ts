import './i18n';
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, etagOf, newIdempotencyKey, type CursorPage } from '@/shared/api/client';
import type { Priority, StatusCategory } from '@/shared/ui/TicketBadges';

/** Типы и запросы API проблем (/api/v1/problems, /api/v1/known-errors), контракт — docs/api/problems-contract.md. */

export const ISHIKAWA = ['people', 'process', 'technology', 'environment', 'suppliers', 'measurement'] as const;
export type IshikawaCategory = (typeof ISHIKAWA)[number];

export interface TimelineEntry {
  at: string | null;
  text: string;
}

export interface Why {
  question: string;
  answer: string;
}

export interface Analysis {
  timeline: TimelineEntry[];
  whys: Why[];
  ishikawa: Partial<Record<IshikawaCategory, string[]>>;
  conclusion: string;
}

export interface LinkedTicket {
  id: string;
  number: string | null;
  title: string | null;
  statusCode: string | null;
  statusCategory: StatusCategory | null;
  priority: Priority | null;
  createdAt: string | null;
  visible: boolean;
}

export interface KnownError {
  id: string;
  problemId: string;
  problemNumber: string | null;
  title: string;
  symptoms: string;
  workaround: string;
  serviceId: string | null;
  ciId: string | null;
  categoryId: string | null;
  published: boolean;
  publishedAt: string | null;
  fixed: boolean;
  updatedAt: string;
  version: number;
}

export interface Problem {
  id: string;
  number: string;
  title: string;
  statusCode: string;
  statusCategory: StatusCategory;
  priority: Priority;
  groupId: string | null;
  assigneeId: string | null;
  serviceId: string | null;
  ciId: string | null;
  categoryId: string | null;
  createdAt: string;
  resolvedAt: string | null;
  source: 'manual' | 'incident' | 'recurring' | 'major_incident';
  rootCause: string;
  workaround: string;
  analysis: Analysis;
  closeIncidents: boolean;
  risk: { acceptedBy: string; acceptedAt: string; reviewAt: string | null; reason: string } | null;
  knownError: KnownError | null;
  incidents: LinkedTicket[];
  changes: LinkedTicket[];
  permissions: { edit: boolean; publishKnownError: boolean; createChange: boolean; resolveIncidents: boolean };
  version: number;
}

/** Строка списка проблем: заявка типа problem из /api/v1/tickets. */
export interface ProblemRow {
  id: string;
  number: string;
  title: string;
  status: { code: string; name: string; category: StatusCategory };
  priority: Priority;
  assigneeId: string | null;
  groupId: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface RecurringCluster {
  dimension: 'service' | 'category' | 'ci';
  key: string;
  name: string | null;
  count: number;
  open: number;
  firstAt: string;
  lastAt: string;
  incidentIds: string[];
  sample: LinkedTicket[];
  problems: { id: string; number: string }[];
}

export interface NewProblemInput {
  title: string;
  description?: string;
  incidentIds?: string[];
  source?: 'manual' | 'incident' | 'recurring';
  groupId?: string | null;
  impact?: string | null;
  urgency?: string | null;
}

export interface KnownErrorInput {
  title: string;
  symptoms: string;
  workaround: string;
  serviceId?: string | null;
  ciId?: string | null;
  categoryId?: string | null;
}

export interface ResolveResult {
  resolved: string[];
  skipped: { number: string; reason: string }[];
}

/** Вкладки списка: те же группы статусов, что у state в /api/v1/tickets. */
export type ProblemListState = 'active' | 'waiting' | 'closed' | 'all';

export const problemKeys = {
  all: ['problems'] as const,
  list: (state: string) => ['problems', 'list', state] as const,
  detail: (ref: string) => ['problems', 'detail', ref] as const,
  knownErrors: (params: object) => ['problems', 'known-errors', params] as const,
  recurring: (params: object) => ['problems', 'recurring', params] as const,
};

const base = '/problems';

export const problemsApi = {
  list: (state: ProblemListState, cursor?: string, signal?: AbortSignal) => {
    const q = new URLSearchParams({ type: 'problem', limit: '50', sort: '-created' });
    if (state !== 'all') q.set('state', state);
    if (cursor) q.set('cursor', cursor);
    return api.get<CursorPage<ProblemRow>>(`/tickets?${q}`, { signal });
  },
  get: (ref: string, signal?: AbortSignal) => api.get<Problem>(`${base}/${encodeURIComponent(ref)}`, { signal }),
  /** id заявки по номеру: связи принимают id. */
  ticketId: async (number: string) => (await api.get<{ id: string }>(`/tickets/${encodeURIComponent(number)}`)).id,
  create: (input: NewProblemInput) =>
    api.post<Problem>(base, input, { idempotencyKey: newIdempotencyKey() }),
  saveAnalysis: (p: Problem, body: { rootCause?: string; workaround?: string; analysis?: Analysis; closeIncidents?: boolean }) =>
    api.put<Problem>(`${base}/${p.id}/analysis`, body, { ifMatch: etagOf(p.version) }),
  acceptRisk: (p: Problem, reason: string, reviewAt: string | null) =>
    api.post<Problem>(`${base}/${p.id}/accept-risk`, { reason, reviewAt }, { ifMatch: etagOf(p.version) }),
  linkIncidents: (p: Problem, incidentIds: string[]) => api.post<Problem>(`${base}/${p.id}/incidents`, { incidentIds }),
  createChange: (p: Problem, title: string, description: string) =>
    api.post<LinkedTicket>(`${base}/${p.id}/changes`, { title, description }, { idempotencyKey: newIdempotencyKey() }),
  resolveIncidents: (p: Problem, comment: string) => api.post<ResolveResult>(`${base}/${p.id}/resolve-incidents`, { comment }),
  saveKnownError: (p: Problem, input: KnownErrorInput) =>
    api.put<Problem>(`${base}/${p.id}/known-error`, input, p.knownError ? { ifMatch: etagOf(p.knownError.version) } : undefined),
  publishKnownError: (p: Problem, publish: boolean) =>
    api.post<Problem>(`${base}/${p.id}/known-error/${publish ? 'publish' : 'unpublish'}`, undefined, { ifMatch: etagOf(p.knownError?.version ?? 0) }),
  knownErrors: (params: { q?: string; ticketId?: string; includeDrafts?: boolean; limit?: number }, signal?: AbortSignal) => {
    const q = new URLSearchParams();
    if (params.q) q.set('q', params.q);
    if (params.ticketId) q.set('ticketId', params.ticketId);
    if (params.includeDrafts) q.set('includeDrafts', 'true');
    q.set('limit', String(params.limit ?? 20));
    return api.get<KnownError[]>(`/known-errors?${q}`, { signal });
  },
  recurring: (params: { dimension: string; days: number; minCount: number }, signal?: AbortSignal) => {
    const q = new URLSearchParams({ dimension: params.dimension, days: String(params.days), minCount: String(params.minCount) });
    return api.get<RecurringCluster[]>(`${base}/recurring?${q}`, { signal });
  },
};

export function useProblemList(state: ProblemListState) {
  return useInfiniteQuery({
    queryKey: problemKeys.list(state),
    queryFn: ({ pageParam, signal }) => problemsApi.list(state, pageParam, signal),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  });
}

export function useProblem(ref: string) {
  return useQuery({ queryKey: problemKeys.detail(ref), queryFn: ({ signal }) => problemsApi.get(ref, signal) });
}

export function useKnownErrors(params: { q?: string; ticketId?: string; includeDrafts?: boolean }, enabled = true) {
  return useQuery({
    queryKey: problemKeys.knownErrors(params),
    queryFn: ({ signal }) => problemsApi.knownErrors(params, signal),
    enabled,
    retry: false,
  });
}

export function useRecurring(params: { dimension: string; days: number; minCount: number }) {
  return useQuery({ queryKey: problemKeys.recurring(params), queryFn: ({ signal }) => problemsApi.recurring(params, signal) });
}

/** Изменение карточки: свежая версия сразу в кэш (по номеру), чтобы следующий If-Match был верным. */
export function useProblemMutation<TArgs>(fn: (args: TArgs) => Promise<Problem>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: (p) => {
      qc.setQueryData(problemKeys.detail(p.number), p);
      void qc.invalidateQueries({ queryKey: ['problems', 'list'] });
      void qc.invalidateQueries({ queryKey: ['problems', 'known-errors'] });
    },
  });
}

export function useCreateProblem() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: problemsApi.create,
    onSuccess: (p) => {
      qc.setQueryData(problemKeys.detail(p.number), p);
      void qc.invalidateQueries({ queryKey: problemKeys.all });
      void qc.invalidateQueries({ queryKey: ['tickets'] });
    },
  });
}
