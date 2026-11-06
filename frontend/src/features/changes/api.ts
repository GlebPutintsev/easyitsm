import './i18n';
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, etagOf, newIdempotencyKey, request, type CursorPage } from '@/shared/api/client';

/** Типы и запросы API изменений (docs/api/changes-contract.md). */

export type ChangeType = 'standard' | 'normal' | 'emergency';
export type ChangeState =
  | 'draft'
  | 'assessment'
  | 'approval'
  | 'scheduled'
  | 'implementation'
  | 'verification'
  | 'review'
  | 'closed'
  | 'rejected'
  | 'cancelled';
export type RiskLevel = 'low' | 'medium' | 'high';
export type Phase = 'prepare' | 'implement' | 'verify' | 'rollback';
export type StepStatus = 'pending' | 'done' | 'failed' | 'skipped';
export type Outcome = 'successful' | 'partial' | 'rolled_back';

export interface Step {
  id: string;
  phase: Phase;
  seq: number;
  title: string;
  description: string | null;
  ownerId: string | null;
  ownerName: string | null;
  team: string | null;
  contacts: string | null;
  plannedStart: string | null;
  start: string | null;
  end: string | null;
  durationMinutes: number;
  upto: boolean;
  interruption: string | null;
  checkCriteria: string | null;
  rollbackRef: number | null;
  status: StepStatus;
  resultNote: string | null;
  executedAt: string | null;
  executedBy: string | null;
}

export type StepInput = Partial<Omit<Step, 'seq' | 'start' | 'end' | 'status' | 'resultNote' | 'executedAt' | 'executedBy'>> & {
  phase: Phase;
  title: string;
};

export interface Approval {
  id: string;
  kind: 'manager' | 'group' | 'user' | 'cab';
  groupId: string | null;
  approverId: string | null;
  round: number;
  postFactum: boolean;
  decision: 'pending' | 'approved' | 'rejected' | 'superseded';
  comment: string | null;
  decidedBy: string | null;
  decidedAt: string | null;
  dueAt: string | null;
  canDecide: boolean;
}

export interface ImpactRow {
  service: string;
  description?: string | null;
  duration?: string | null;
}

export interface Permissions {
  edit: boolean;
  editPlan: boolean;
  submit: boolean;
  assess: boolean;
  sendToApproval: boolean;
  returnToDraft: boolean;
  reschedule: boolean;
  start: boolean;
  recordSteps: boolean;
  startRollback: boolean;
  complete: boolean;
  close: boolean;
  cancel: boolean;
}

export interface Change {
  id: string;
  number: string;
  title: string;
  type: ChangeType;
  state: ChangeState;
  templateId: string | null;
  sourceTicketId: string | null;
  authorId: string;
  groupId: string | null;
  assigneeId: string | null;
  confidential: boolean;
  goal: string;
  justification: string | null;
  impact: string | null;
  risks: string | null;
  impactRows: ImpactRow[];
  systemCriticality: string | null;
  windowStart: string | null;
  windowEnd: string | null;
  timeZone: string;
  prepStart: string | null;
  downtimeMinutes: number | null;
  ciIds: string[];
  serviceIds: string[];
  cis: CiRef[];
  risk: {
    answers: Record<string, string>;
    score: number | null;
    level: RiskLevel | null;
    manual: boolean;
    reason: string | null;
    assessedAt: string | null;
    assessedBy: string | null;
    computedLevel: RiskLevel | null;
  };
  conflictsAck: string | null;
  submittedAt: string | null;
  approvedAt: string | null;
  postApprovalDueAt: string | null;
  postApprovalOverdue: boolean;
  actualStart: string | null;
  actualEnd: string | null;
  rollbackStartedAt: string | null;
  outcome: Outcome | null;
  conclusion: string | null;
  review: Record<string, unknown>;
  closedAt: string | null;
  cancelReason: string | null;
  pipeline: Record<string, string> | null;
  steps: Step[];
  plan: {
    prepareMinutes: number;
    implementMinutes: number;
    verifyMinutes: number;
    rollbackMinutes: number;
    planEnd: string | null;
    fitsWindow: boolean | null;
  };
  approvals: Approval[];
  cab: {
    meetingId: string;
    meetingTitle: string;
    startsAt: string;
    meetingState: string;
    itemId: string;
    decision: string;
    comment: string | null;
  }[];
  missing: string[];
  permissions: Permissions;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface ChangeItem {
  id: string;
  number: string;
  title: string;
  type: ChangeType;
  state: ChangeState;
  riskLevel: RiskLevel | null;
  windowStart: string | null;
  windowEnd: string | null;
  authorId: string;
  groupId: string | null;
  assigneeId: string | null;
  ciIds: string[];
  serviceIds: string[];
  outcome: Outcome | null;
  updatedAt: string;
}

export interface CiRef {
  id: string;
  name: string;
  className: string;
  service: boolean;
}

export interface Conflict {
  kind: 'ci' | 'service' | 'freeze';
  changeId: string | null;
  number: string | null;
  title: string;
  state: ChangeState | null;
  freezeId: string | null;
  sharedCiIds: string[];
  sharedServiceIds: string[];
  from: string;
  to: string;
}

export interface Freeze {
  id: string;
  title: string;
  reason: string | null;
  startsAt: string;
  endsAt: string;
  scope: 'all' | 'targeted';
  serviceIds: string[];
  ciIds: string[];
  version: number;
}

export interface Template {
  id: string;
  name: string;
  description: string | null;
  changeType: ChangeType;
  preapproved: boolean;
  riskLevel: RiskLevel | null;
  pipelineKey: string | null;
  content: Record<string, unknown>;
  steps: StepInput[];
  ciIds: string[];
  serviceIds: string[];
  active: boolean;
  updatedAt: string;
  version: number;
}

export interface Route {
  manager: boolean;
  groupId: string | null;
  cab: boolean;
}

export interface ChangeSettings {
  low: Route;
  medium: Route;
  high: Route;
  emergency: { preApproval: boolean; postApprovalBusinessDays: number };
  requireAffected: boolean;
  timeZone: string;
  version: number | null;
}

export interface RiskQuestion {
  code: string;
  text: string;
  options: { code: string; label: string; weight: number }[];
}

export interface PendingApproval {
  approval: Approval;
  change: ChangeItem;
}

export interface RelatedIncident {
  id: string;
  number: string;
  title: string;
  statusCode: string;
  priority: string | null;
  createdAt: string;
}

export interface Impact {
  cmdb: boolean;
  cis: CiRef[];
  services: { serviceId: string; name: string; depth: number; path: string[] }[];
  declaredServiceIds: string[];
}

export interface Meeting {
  id: string;
  title: string;
  startsAt: string;
  durationMinutes: number;
  location: string | null;
  chairId: string | null;
  memberIds: string[];
  state: 'planned' | 'in_progress' | 'completed' | 'cancelled';
  minutes: string | null;
  startedAt: string | null;
  completedAt: string | null;
  protocolSentAt: string | null;
  items: MeetingItemView[];
  permissions: { manage: boolean; start: boolean; complete: boolean; sendProtocol: boolean };
  version: number;
}

export interface MeetingItemView {
  id: string;
  seq: number;
  change: ChangeItem;
  decision: 'pending' | 'approved' | 'rejected' | 'deferred';
  comment: string | null;
  decidedBy: string | null;
  decidedAt: string | null;
  votes: { voterId: string; vote: 'approve' | 'reject' | 'abstain'; comment: string | null; votedAt: string }[];
  tally: Record<string, number>;
  myVote: string | null;
  canVote: boolean;
  canDecide: boolean;
}

export interface MeetingSummary {
  id: string;
  title: string;
  startsAt: string;
  durationMinutes: number;
  location: string | null;
  chairId: string | null;
  state: Meeting['state'];
  items: number;
}

export interface ChangeFilter {
  state?: string;
  type?: string;
  risk?: string;
  q?: string;
  mine?: boolean;
  ciId?: string;
}

export const changeKeys = {
  all: ['changes'] as const,
  list: (f: ChangeFilter) => ['changes', 'list', f] as const,
  one: (id: string) => ['changes', 'one', id] as const,
  pending: ['changes', 'pending'] as const,
  calendar: (from: string, to: string) => ['changes', 'calendar', from, to] as const,
  meetings: (state?: string) => ['changes', 'cab', 'list', state ?? ''] as const,
  meeting: (id: string) => ['changes', 'cab', id] as const,
};

export function useChanges(filter: ChangeFilter) {
  return useInfiniteQuery({
    queryKey: changeKeys.list(filter),
    queryFn: ({ pageParam, signal }) =>
      api.get<CursorPage<ChangeItem>>('/changes', {
        query: { ...filter, mine: filter.mine || undefined, cursor: pageParam ?? undefined, limit: 50 },
        signal,
      }),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor,
  });
}

export function useChange(id: string | undefined) {
  return useQuery({
    queryKey: changeKeys.one(id ?? ''),
    queryFn: ({ signal }) => api.get<Change>(`/changes/${id}`, { signal }),
    enabled: !!id,
  });
}

export function usePendingApprovals() {
  return useQuery({
    queryKey: changeKeys.pending,
    queryFn: ({ signal }) => api.get<PendingApproval[]>('/changes/approvals/pending', { signal }),
  });
}

export function useRiskQuestions() {
  return useQuery({
    queryKey: ['changes', 'risk-questions'],
    queryFn: ({ signal }) => api.get<RiskQuestion[]>('/changes/risk-questions', { signal }),
    staleTime: Infinity,
  });
}

export interface NewChangeInput {
  type?: ChangeType;
  title: string;
  description?: string;
  templateId?: string | null;
  ciIds?: string[];
  serviceIds?: string[];
}

export function useCreateChange() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input: NewChangeInput) => api.post<Change>('/changes', input, { idempotencyKey: newIdempotencyKey() }),
    onSuccess: (c) => {
      qc.setQueryData(changeKeys.one(c.id), c);
      void qc.invalidateQueries({ queryKey: ['changes', 'list'] });
    },
  });
}

/**
 * Любое изменяющее действие над изменением: путь относительно карточки, тело, метод. Ответ — свежая карточка,
 * она сразу кладётся в кэш; список и календарь перечитываются.
 */
export function useChangeAction(change: Pick<Change, 'id' | 'version'> | undefined) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ path, body, method = 'POST' }: { path: string; body?: unknown; method?: 'POST' | 'PUT' | 'PATCH' }) =>
      request<Change>(method, `/changes/${change!.id}${path}`, {
        body: body ?? {},
        ifMatch: etagOf(change!.version),
      }),
    onSuccess: (c) => {
      qc.setQueryData(changeKeys.one(c.id), c);
      void qc.invalidateQueries({ queryKey: ['changes', 'list'] });
      void qc.invalidateQueries({ queryKey: ['changes', 'calendar'] });
      void qc.invalidateQueries({ queryKey: changeKeys.pending });
    },
  });
}

export function useConflicts(id: string | undefined) {
  return useQuery({
    queryKey: ['changes', 'conflicts', id],
    queryFn: ({ signal }) => api.get<Conflict[]>(`/changes/${id}/conflicts`, { signal }),
    enabled: !!id,
  });
}

export function useImpact(id: string | undefined, enabled: boolean) {
  return useQuery({
    queryKey: ['changes', 'impact', id],
    queryFn: ({ signal }) => api.get<Impact>(`/changes/${id}/impact`, { query: { depth: 3 }, signal }),
    enabled: !!id && enabled,
  });
}

export function useRelatedIncidents(id: string | undefined, enabled: boolean) {
  return useQuery({
    queryKey: ['changes', 'related', id],
    queryFn: ({ signal }) => api.get<RelatedIncident[]>(`/changes/${id}/related-incidents`, { signal }),
    enabled: !!id && enabled,
  });
}

export function useCalendar(from: string, to: string) {
  return useQuery({
    queryKey: changeKeys.calendar(from, to),
    queryFn: ({ signal }) =>
      api.get<{ changes: ChangeItem[]; freezes: Freeze[] }>('/changes/calendar', { query: { from, to }, signal }),
  });
}

/** Скачать документ, который сервер отдаёт на GET (план работ, iCal). */
export async function downloadGet(path: string, fallbackName: string): Promise<void> {
  const response = await request<Response>('GET', path, { raw: true });
  const blob = await response.blob();
  const disposition = response.headers.get('Content-Disposition') ?? '';
  const utf8 = /filename\*=UTF-8''([^;]+)/i.exec(disposition);
  const name = utf8?.[1] ? decodeURIComponent(utf8[1]) : fallbackName;
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

// ---------------------------------------------------------------- настройки

export function useTemplates(active?: boolean) {
  return useQuery({
    queryKey: ['changes', 'templates', active ?? 'all'],
    queryFn: ({ signal }) => api.get<Template[]>('/change-templates', { query: { active }, signal }),
  });
}

export function useSaveTemplate() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, version, input }: { id?: string; version?: number; input: Partial<Template> }) =>
      id
        ? api.patch<Template>(`/change-templates/${id}`, input, { ifMatch: etagOf(version ?? 0) })
        : api.post<Template>('/change-templates', input, { idempotencyKey: newIdempotencyKey() }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['changes', 'templates'] }),
  });
}

export function useFreezes() {
  return useQuery({
    queryKey: ['changes', 'freezes'],
    queryFn: ({ signal }) => api.get<Freeze[]>('/change-freezes', { signal }),
  });
}

export function useSaveFreeze() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, version, input }: { id?: string; version?: number; input: Partial<Freeze> }) =>
      id
        ? api.patch<Freeze>(`/change-freezes/${id}`, input, { ifMatch: etagOf(version ?? 0) })
        : api.post<Freeze>('/change-freezes', input, { idempotencyKey: newIdempotencyKey() }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['changes', 'freezes'] });
      void qc.invalidateQueries({ queryKey: ['changes', 'calendar'] });
    },
  });
}

export function useDeleteFreeze() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (f: Freeze) => api.delete<void>(`/change-freezes/${f.id}`, { ifMatch: etagOf(f.version) }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['changes', 'freezes'] });
      void qc.invalidateQueries({ queryKey: ['changes', 'calendar'] });
    },
  });
}

export function useChangeSettings() {
  return useQuery({
    queryKey: ['changes', 'settings'],
    queryFn: ({ signal }) => api.get<ChangeSettings>('/change-settings', { signal }),
  });
}

export function useSaveChangeSettings() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (doc: ChangeSettings) =>
      api.put<ChangeSettings>('/change-settings', doc, { ifMatch: etagOf(doc.version ?? 0) }),
    onSuccess: (saved) => qc.setQueryData(['changes', 'settings'], saved),
  });
}

// ---------------------------------------------------------------- CAB

export function useMeetings(state?: string) {
  return useQuery({
    queryKey: changeKeys.meetings(state),
    queryFn: ({ signal }) =>
      api.get<CursorPage<MeetingSummary>>('/cab/meetings', { query: { state, limit: 100 }, signal }),
    select: (page) => page.items,
  });
}

export function useMeeting(id: string | undefined) {
  return useQuery({
    queryKey: changeKeys.meeting(id ?? ''),
    queryFn: ({ signal }) => api.get<Meeting>(`/cab/meetings/${id}`, { signal }),
    enabled: !!id,
  });
}

export interface MeetingInput {
  title?: string;
  startsAt?: string;
  durationMinutes?: number;
  location?: string | null;
  chairId?: string | null;
  memberIds?: string[];
  minutes?: string | null;
}

export function useCreateMeeting() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input: MeetingInput) =>
      api.post<Meeting>('/cab/meetings', input, { idempotencyKey: newIdempotencyKey() }),
    onSuccess: (m) => {
      qc.setQueryData(changeKeys.meeting(m.id), m);
      void qc.invalidateQueries({ queryKey: ['changes', 'cab', 'list'] });
    },
  });
}

export function useMeetingAction(meeting: Pick<Meeting, 'id' | 'version'> | undefined) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ path, body, method = 'POST' }: { path: string; body?: unknown; method?: 'POST' | 'PATCH' | 'DELETE' }) =>
      request<Meeting>(method, `/cab/meetings/${meeting!.id}${path}`, {
        body: method === 'DELETE' ? undefined : (body ?? {}),
        ifMatch: etagOf(meeting!.version),
      }),
    onSuccess: (m) => {
      qc.setQueryData(changeKeys.meeting(m.id), m);
      void qc.invalidateQueries({ queryKey: ['changes', 'cab', 'list'] });
      void qc.invalidateQueries({ queryKey: ['changes', 'one'] });
      void qc.invalidateQueries({ queryKey: ['changes', 'list'] });
    },
  });
}

// ---------------------------------------------------------------- CMDB

export interface CmdbCi {
  id: string;
  name: string;
  className: string;
  layer: string;
}

/** Поиск КЕ и услуг в CMDB для полей изменения. */
export function useCiSearch(q: string, layer?: string) {
  return useQuery({
    queryKey: ['changes', 'ci-search', q, layer ?? ''],
    queryFn: ({ signal }) =>
      api.get<CursorPage<CmdbCi>>('/cmdb/cis', { query: { q: q || undefined, layer, limit: 20 }, signal }),
    select: (page) => page.items,
    staleTime: 30_000,
  });
}
