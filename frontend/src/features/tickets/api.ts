/**
 * Клиент API заявок (docs/api/tickets.yaml): типы ответов, сырые вызовы `ticketsApi` для адаптеров
 * и хуки TanStack Query для экранов. Изменения идут с If-Match из `version` записи, создание —
 * с Idempotency-Key, который вызывающий создаёт один раз на действие пользователя.
 */
import { keepPreviousData, useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError, request, type CursorPage } from '@/shared/api/client';
import type { Priority, StatusCategory } from '@/shared/ui/TicketBadges';

export type { Priority, StatusCategory };

// ------------------------------------------------------------------ справочники

export type TicketType = 'incident' | 'request' | 'problem' | 'change' | 'task';
export type Level = 'high' | 'medium' | 'low';
export type Channel = 'portal' | 'email' | 'api' | 'chat' | 'monitoring' | 'agent';
export type Visibility = 'public' | 'internal';
/** Что делать, если в тексте найден секрет (TCK-17): warn — ошибка 422, mask — скрыть, allow — как есть (только поддержка) */
export type SecretsPolicy = 'warn' | 'mask' | 'allow';
export type EscalationKind = 'functional' | 'hierarchical';
export type IdentityMethod = 'callback' | 'manager_confirmation' | 'in_person' | 'mfa' | 'other';
export type LinkKind =
  | 'parent_of'
  | 'child_of'
  | 'duplicate_of'
  | 'duplicated_by'
  | 'blocks'
  | 'blocked_by'
  | 'caused_by'
  | 'causes'
  | 'relates_to';
export type RequiredField =
  | 'group'
  | 'assignee'
  | 'comment'
  | 'resolution'
  | 'pending_reason'
  | 'pending_until'
  | 'reason_code';

export const TICKET_PREFIX: Record<TicketType, string> = {
  incident: 'INC',
  request: 'REQ',
  problem: 'PRB',
  change: 'CHG',
  task: 'TSK',
};

// ------------------------------------------------------------------ заявка

export interface StatusRef {
  code: string;
  name: string;
  category: StatusCategory;
}

export interface IdentityCheck {
  required: boolean;
  verifiedAt: string | null;
  verifiedBy: string | null;
  method: IdentityMethod | null;
}

/** Строка списка и основа карточки */
export interface Ticket {
  id: string;
  number: string;
  type: TicketType;
  title: string;
  status: StatusRef;
  priority: Priority;
  impact: Level;
  urgency: Level;
  priorityManual: boolean;
  requesterId: string;
  createdForId: string | null;
  organizationId: string | null;
  groupId: string | null;
  assigneeId: string | null;
  serviceId: string | null;
  ciId: string | null;
  categoryId: string | null;
  catalogItemId: string | null;
  parentId: string | null;
  channel: Channel;
  confidential: boolean;
  securityGroupId: string | null;
  dueAt: string | null;
  /** Ближайший срок SLA, его пишет модуль SLA */
  slaDueAt: string | null;
  /** Заявитель ответил, ждём агента */
  awaitingAgent: boolean;
  pendingReason: string | null;
  pendingUntil: string | null;
  resolutionCode: string | null;
  resolutionText: string | null;
  reopenCount: number;
  escalationLevel: number;
  identityCheck: IdentityCheck;
  custom: Record<string, unknown>;
  firstResponseAt: string | null;
  resolvedAt: string | null;
  closedAt: string | null;
  createdAt: string;
  createdBy: string;
  updatedAt: string;
  updatedBy: string;
  version: number;
}

export interface AvailableTransition {
  to: string;
  name: string;
  category: StatusCategory;
  requiredFields: RequiredField[];
}

export interface TicketPermissions {
  edit: boolean;
  assign: boolean;
  comment: boolean;
  internalNotes: boolean;
  merge: boolean;
  setConfidential: boolean;
}

/** Карточка: заявка, описание, доступные текущему пользователю переходы и действия */
export interface TicketDetails extends Ticket {
  description: string | null;
  transitions: AvailableTransition[];
  permissions: TicketPermissions;
}

/** Значение для If-Match */
export const etagOf = (t: { version: number }) => `"${t.version}"`;

export interface CreateTicketInput {
  type: TicketType;
  title: string;
  description?: string;
  /** Только поддержка может создать от имени другого */
  requesterId?: string;
  createdForId?: string;
  organizationId?: string;
  impact?: Level;
  urgency?: Level;
  /** Ручной приоритет (поддержка), нужен priorityReason */
  priority?: Priority;
  priorityReason?: string;
  groupId?: string;
  assigneeId?: string;
  serviceId?: string;
  ciId?: string;
  categoryId?: string;
  channel?: Channel;
  confidential?: boolean;
  securityGroupId?: string;
  dueAt?: string;
  custom?: Record<string, unknown>;
  watcherIds?: string[];
  secrets?: SecretsPolicy;
}

export type ClearableField =
  | 'groupId'
  | 'assigneeId'
  | 'serviceId'
  | 'ciId'
  | 'categoryId'
  | 'createdForId'
  | 'organizationId'
  | 'dueAt'
  | 'priority'
  | 'securityGroupId';

/** PATCH: передаются только изменённые поля; чтобы очистить поле, укажите его в clear */
export interface UpdateTicketInput {
  title?: string;
  description?: string;
  impact?: Level;
  urgency?: Level;
  priority?: Priority;
  priorityReason?: string;
  groupId?: string;
  assigneeId?: string;
  serviceId?: string;
  ciId?: string;
  categoryId?: string;
  createdForId?: string;
  organizationId?: string;
  confidential?: boolean;
  confidentialReason?: string;
  securityGroupId?: string;
  dueAt?: string;
  /** Слияние с текущими значениями; null удаляет поле */
  custom?: Record<string, unknown>;
  clear?: ClearableField[];
  secrets?: SecretsPolicy;
}

export interface TransitionInput {
  to: string;
  comment?: string;
  reasonCode?: string;
  resolutionCode?: string;
  resolutionText?: string;
  pendingUntil?: string;
}

// ------------------------------------------------------------------ список

/** mine — я заявитель, получатель или наблюдатель */
export type TicketScope = 'all' | 'mine' | 'assigned_to_me' | 'unassigned' | 'watching';
/** active — в работе у поддержки, waiting — ожидание и решённые, closed — закрытые и отменённые */
export type TicketState = 'active' | 'waiting' | 'closed';
export type TicketSort = '-created' | 'created' | '-updated' | 'priority' | 'due';

export interface TicketFilter {
  scope?: TicketScope;
  state?: TicketState;
  /** Через запятую: incident,request */
  type?: string;
  status?: string;
  statusCategory?: string;
  /** Через запятую: P1,P2 */
  priority?: string;
  /** Через запятую, `none` — без группы */
  groupId?: string;
  /** Через запятую, `none` — без исполнителя (для «моих» используйте scope=assigned_to_me) */
  assigneeId?: string;
  requesterId?: string;
  serviceId?: string;
  categoryId?: string;
  q?: string;
  createdFrom?: string;
  createdTo?: string;
  /** Для плашки «Показать N новых» */
  updatedFrom?: string;
  /** Ближайший срок (slaDueAt или dueAt) раньше этого времени; «просроченные» — now */
  dueBefore?: string;
  awaitingAgent?: boolean;
  /** Пары `код:значение` по настраиваемым полям */
  custom?: string[];
}

/** Собирает строку запроса; повторяемые параметры (custom) добавляются по одному */
export function ticketQuery(filter: TicketFilter, extra: Record<string, string | number | undefined> = {}): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries({ ...filter, ...extra })) {
    if (value === undefined || value === null || value === '') continue;
    if (Array.isArray(value)) {
      for (const item of value) params.append(key, String(item));
    } else {
      params.set(key, key === 'q' ? String(value).trim() : String(value));
    }
  }
  const qs = params.toString();
  return qs ? `?${qs}` : '';
}

// ------------------------------------------------------------------ лента, связи, наблюдатели

export type ActivityKind = 'reply' | 'note' | 'system' | 'change';

export interface ActivityEntry {
  id: string;
  kind: ActivityKind;
  visibility: Visibility;
  authorId: string | null;
  fromRequester: boolean;
  body: string | null;
  /** Для системных записей: event (status_changed, routed, escalated, merged, linked…) и детали */
  meta: Record<string, unknown>;
  mentions: string[];
  createdAt: string;
}

export interface FieldChange {
  field: string;
  from: unknown;
  to: unknown;
  actorId: string | null;
  reason: string | null;
  at: string;
}

export interface CommentInput {
  body: string;
  /** По умолчанию: internal для поддержки, public для заявителя */
  visibility?: Visibility;
  mentions?: string[];
  secrets?: SecretsPolicy;
}

export interface LinkedTicket {
  id: string;
  /** Без прав на связанную заявку приходят только id и number */
  number: string;
  title: string | null;
  status: StatusRef | null;
}

export interface TicketLink {
  id: string;
  kind: LinkKind;
  ticket: LinkedTicket;
  createdAt: string;
}

export interface SubtaskInput {
  title: string;
  description?: string;
  groupId?: string;
  assigneeId?: string;
  dueAt?: string;
}

export interface Watcher {
  userId: string;
  reason: 'self' | 'added' | 'mention' | 'merge';
  createdAt: string;
}

// ------------------------------------------------------------------ слияние и массовые операции

export interface MergeResult {
  preview: boolean;
  target: Ticket;
  sources: { id: string; number: string; ok: boolean; code: string | null }[];
  watchersAdded: string[];
}

export type BulkAction = 'assign' | 'transition' | 'priority' | 'watch';

export interface BulkInput {
  ticketIds: string[];
  action: BulkAction;
  /** assign: {groupId, assigneeId}; transition: TransitionInput без to → {to, ...}; priority: {priority, reason}; watch: {} */
  params?: Record<string, unknown>;
  preview?: boolean;
}

export interface BulkItem {
  ticketId: string;
  number: string | null;
  ok: boolean;
  code: string | null;
  detail: string | null;
  changes: FieldChange[];
}

export interface BulkResult {
  preview: boolean;
  /** Есть после выполнения; отмена доступна до undoUntil (10 секунд) */
  operationId: string | null;
  undoUntil: string | null;
  items: BulkItem[];
}

// ------------------------------------------------------------------ секреты

export interface SecretFinding {
  kind: string;
  start: number;
  end: number;
  masked: string;
}

export interface SecretScanResult {
  findings: SecretFinding[];
  masked: string;
}

/** Если сервер ответил 422 secrets_detected, возвращает найденное, чтобы показать предупреждение */
export function secretFindings(error: unknown): SecretFinding[] | null {
  if (error instanceof ApiError && error.code === 'secrets_detected') {
    return (error.problem.findings as SecretFinding[] | undefined) ?? [];
  }
  return null;
}

// ------------------------------------------------------------------ представления и готовые ответы

export type SharedVisibility = 'personal' | 'group' | 'shared';

export interface TicketViewColumn {
  field: string;
  width?: number;
}

export interface SavedTicketView {
  id: string;
  name: string;
  visibility: SharedVisibility;
  groupId: string | null;
  ownerId: string;
  filter: TicketFilter;
  sort: TicketSort | null;
  columns: TicketViewColumn[];
  position: number | null;
  version: number;
}

export type SavedTicketViewInput = Partial<Omit<SavedTicketView, 'id' | 'ownerId' | 'version'>> & { name: string };

export interface ResponseTemplate {
  id: string;
  name: string;
  /** Подстановки вида {{ticket.number}} */
  body: string;
  visibility: SharedVisibility;
  groupId: string | null;
  ticketTypes: TicketType[];
  ownerId: string;
  version: number;
}

export type ResponseTemplateInput = Partial<Omit<ResponseTemplate, 'id' | 'ownerId' | 'version'>> & {
  name: string;
  body: string;
};

// ------------------------------------------------------------------ сырые вызовы

const T = '/tickets';
const enc = encodeURIComponent;
const del = <R = void>(path: string, ifMatch?: string) => request<R>('DELETE', path, { ifMatch });

export const ticketsApi = {
  list: (filter: TicketFilter, page: { sort?: TicketSort; cursor?: string; limit?: number } = {}, signal?: AbortSignal) =>
    api.get<CursorPage<Ticket>>(`${T}${ticketQuery(filter, page)}`, { signal }),
  count: (filter: TicketFilter, signal?: AbortSignal) =>
    api.get<{ count: number }>(`${T}/count${ticketQuery(filter)}`, { signal }),
  similar: (q: string, type: TicketType = 'incident', limit = 5, signal?: AbortSignal) =>
    api.get<Ticket[]>(`${T}/similar`, { query: { q, type, limit }, signal }),
  /** ref — id или номер (INC-000123) */
  get: (ref: string, signal?: AbortSignal) => api.get<TicketDetails>(`${T}/${enc(ref)}`, { signal }),
  create: (input: CreateTicketInput, idempotencyKey: string) =>
    api.post<TicketDetails>(T, input, { idempotencyKey }),
  update: (t: { id: string; version: number }, input: UpdateTicketInput) =>
    api.patch<TicketDetails>(`${T}/${t.id}`, input, { ifMatch: etagOf(t) }),
  transitions: (ref: string, signal?: AbortSignal) =>
    api.get<AvailableTransition[]>(`${T}/${enc(ref)}/transitions`, { signal }),
  transition: (t: { id: string; version: number }, input: TransitionInput) =>
    api.post<TicketDetails>(`${T}/${t.id}/transitions`, input, { ifMatch: etagOf(t) }),
  take: (t: { id: string; version: number }) => api.post<TicketDetails>(`${T}/${t.id}/take`, {}, { ifMatch: etagOf(t) }),
  /** null — очередь пуста (204) */
  takeNext: async (groupId?: string) =>
    ((await api.post<TicketDetails | undefined>(`${T}/take-next`, groupId ? { groupId } : {})) ?? null),
  escalate: (t: { id: string; version: number }, input: { kind: EscalationKind; groupId?: string; reason: string }) =>
    api.post<TicketDetails>(`${T}/${t.id}/escalate`, input, { ifMatch: etagOf(t) }),
  identityCheck: (id: string, input: { method: IdentityMethod; note?: string }) =>
    api.post<TicketDetails>(`${T}/${id}/identity-check`, input),

  activity: (ref: string, opts: { visibility?: Visibility; cursor?: string; limit?: number } = {}, signal?: AbortSignal) =>
    api.get<CursorPage<ActivityEntry>>(`${T}/${enc(ref)}/activity`, { query: opts, signal }),
  comment: (id: string, input: CommentInput) => api.post<ActivityEntry>(`${T}/${id}/activity`, input),
  history: (ref: string, opts: { cursor?: string; limit?: number } = {}, signal?: AbortSignal) =>
    api.get<CursorPage<FieldChange>>(`${T}/${enc(ref)}/history`, { query: opts, signal }),

  links: (ref: string, signal?: AbortSignal) => api.get<TicketLink[]>(`${T}/${enc(ref)}/links`, { signal }),
  link: (id: string, input: { targetId: string; kind: LinkKind }) => api.post<TicketLink>(`${T}/${id}/links`, input),
  unlink: (id: string, linkId: string) => del(`${T}/${id}/links/${linkId}`),
  subtasks: (ref: string, signal?: AbortSignal) => api.get<Ticket[]>(`${T}/${enc(ref)}/subtasks`, { signal }),
  addSubtask: (id: string, input: SubtaskInput) => api.post<TicketDetails>(`${T}/${id}/subtasks`, input),
  watchers: (ref: string, signal?: AbortSignal) => api.get<Watcher[]>(`${T}/${enc(ref)}/watchers`, { signal }),
  /** user — id пользователя или `me` */
  watch: (id: string, user = 'me') => api.put<void>(`${T}/${id}/watchers/${user}`, undefined),
  unwatch: (id: string, user = 'me') => del(`${T}/${id}/watchers/${user}`),

  merge: (targetId: string, sourceIds: string[], preview = false) =>
    api.post<MergeResult>(`${T}/${targetId}/merge`, { sourceIds, preview }),
  bulk: (input: BulkInput) => api.post<BulkResult>(`${T}/bulk`, input),
  undoBulk: (operationId: string) => api.post<BulkResult>(`${T}/bulk/${operationId}/undo`, {}),
  secretScan: (text: string) => api.post<SecretScanResult>(`${T}/secret-scan`, { text }),

  views: (signal?: AbortSignal) => api.get<SavedTicketView[]>('/ticket-views', { signal }),
  createView: (input: SavedTicketViewInput) => api.post<SavedTicketView>('/ticket-views', input),
  updateView: (v: { id: string; version: number }, input: Partial<SavedTicketViewInput>) =>
    api.patch<SavedTicketView>(`/ticket-views/${v.id}`, input, { ifMatch: etagOf(v) }),
  deleteView: (v: { id: string; version: number }) => del(`/ticket-views/${v.id}`, etagOf(v)),
  templates: (q?: string, signal?: AbortSignal) =>
    api.get<ResponseTemplate[]>('/response-templates', { query: { q: q?.trim() || undefined }, signal }),
  createTemplate: (input: ResponseTemplateInput) => api.post<ResponseTemplate>('/response-templates', input),
  updateTemplate: (t: { id: string; version: number }, input: Partial<ResponseTemplateInput>) =>
    api.patch<ResponseTemplate>(`/response-templates/${t.id}`, input, { ifMatch: etagOf(t) }),
  deleteTemplate: (t: { id: string; version: number }) => del(`/response-templates/${t.id}`, etagOf(t)),
};

// ------------------------------------------------------------------ хуки

export const ticketKeys = {
  all: ['tickets'] as const,
  list: (filter: TicketFilter, sort?: TicketSort) => ['tickets', 'list', filter, sort ?? '-created'] as const,
  count: (filter: TicketFilter) => ['tickets', 'count', filter] as const,
  similar: (q: string, type: TicketType) => ['tickets', 'similar', type, q] as const,
  ticket: (ref: string) => ['tickets', 'ticket', ref] as const,
  part: (ref: string, part: 'activity' | 'history' | 'links' | 'subtasks' | 'watchers', extra?: unknown) =>
    ['tickets', 'ticket', ref, part, extra] as const,
  views: ['tickets', 'views'] as const,
  templates: (q?: string) => ['tickets', 'templates', q ?? ''] as const,
};

export const newIdempotencyKey = () => crypto.randomUUID();

export function useTickets(filter: TicketFilter, sort?: TicketSort, limit = 50) {
  return useInfiniteQuery({
    queryKey: ticketKeys.list(filter, sort),
    queryFn: ({ pageParam, signal }) => ticketsApi.list(filter, { sort, cursor: pageParam, limit }, signal),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    placeholderData: keepPreviousData,
  });
}

export function useTicketCount(filter: TicketFilter, options: { refetchInterval?: number } = {}) {
  return useQuery({
    queryKey: ticketKeys.count(filter),
    queryFn: ({ signal }) => ticketsApi.count(filter, signal).then((r) => r.count),
    refetchInterval: options.refetchInterval,
  });
}

/** Похожие открытые заявки при вводе темы (дубликаты, массовые сбои) */
export function useSimilarTickets(q: string, type: TicketType = 'incident') {
  const trimmed = q.trim();
  return useQuery({
    queryKey: ticketKeys.similar(trimmed, type),
    queryFn: ({ signal }) => ticketsApi.similar(trimmed, type, 5, signal),
    enabled: trimmed.length >= 4,
    staleTime: 30_000,
  });
}

export function useTicket(ref: string | undefined) {
  return useQuery({
    queryKey: ticketKeys.ticket(ref ?? ''),
    queryFn: ({ signal }) => ticketsApi.get(ref as string, signal),
    enabled: !!ref,
  });
}

export function useTicketActivity(ref: string | undefined, visibility?: Visibility) {
  return useInfiniteQuery({
    queryKey: ticketKeys.part(ref ?? '', 'activity', visibility),
    queryFn: ({ pageParam, signal }) =>
      ticketsApi.activity(ref as string, { visibility, cursor: pageParam, limit: 50 }, signal),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    enabled: !!ref,
  });
}

export function useTicketHistory(ref: string | undefined) {
  return useInfiniteQuery({
    queryKey: ticketKeys.part(ref ?? '', 'history'),
    queryFn: ({ pageParam, signal }) => ticketsApi.history(ref as string, { cursor: pageParam, limit: 50 }, signal),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    enabled: !!ref,
  });
}

export function useTicketLinks(ref: string | undefined) {
  return useQuery({
    queryKey: ticketKeys.part(ref ?? '', 'links'),
    queryFn: ({ signal }) => ticketsApi.links(ref as string, signal),
    enabled: !!ref,
  });
}

export function useSubtasks(ref: string | undefined) {
  return useQuery({
    queryKey: ticketKeys.part(ref ?? '', 'subtasks'),
    queryFn: ({ signal }) => ticketsApi.subtasks(ref as string, signal),
    enabled: !!ref,
  });
}

export function useWatchers(ref: string | undefined) {
  return useQuery({
    queryKey: ticketKeys.part(ref ?? '', 'watchers'),
    queryFn: ({ signal }) => ticketsApi.watchers(ref as string, signal),
    enabled: !!ref,
  });
}

/**
 * Кладёт свежую карточку в кэш (по id и номеру), чтобы следующее изменение ушло с новым ETag,
 * и обновляет списки и части карточки.
 */
function useStoreTicket() {
  const qc = useQueryClient();
  return (t: TicketDetails | null | undefined) => {
    if (t) {
      qc.setQueryData(ticketKeys.ticket(t.id), t);
      qc.setQueryData(ticketKeys.ticket(t.number), t);
    }
    void qc.invalidateQueries({
      predicate: (q) =>
        q.queryKey[0] === 'tickets' &&
        !(q.queryKey[1] === 'ticket' && q.queryKey.length === 3 && (q.queryKey[2] === t?.id || q.queryKey[2] === t?.number)),
    });
  };
}

function useInvalidateTickets() {
  const qc = useQueryClient();
  return () => void qc.invalidateQueries({ queryKey: ticketKeys.all });
}

export function useCreateTicket() {
  const store = useStoreTicket();
  return useMutation({
    mutationFn: ({ input, key }: { input: CreateTicketInput; key: string }) => ticketsApi.create(input, key),
    onSuccess: store,
  });
}

export function useUpdateTicket() {
  const store = useStoreTicket();
  return useMutation({
    mutationFn: ({ ticket, input }: { ticket: Ticket; input: UpdateTicketInput }) => ticketsApi.update(ticket, input),
    onSuccess: store,
  });
}

export function useTransitionTicket() {
  const store = useStoreTicket();
  return useMutation({
    mutationFn: ({ ticket, input }: { ticket: Ticket; input: TransitionInput }) => ticketsApi.transition(ticket, input),
    onSuccess: store,
  });
}

export function useTakeTicket() {
  const store = useStoreTicket();
  return useMutation({ mutationFn: (ticket: Ticket) => ticketsApi.take(ticket), onSuccess: store });
}

/** Возвращает null, если в очереди нечего взять */
export function useTakeNext() {
  const store = useStoreTicket();
  return useMutation({ mutationFn: (groupId?: string) => ticketsApi.takeNext(groupId), onSuccess: store });
}

export function useEscalateTicket() {
  const store = useStoreTicket();
  return useMutation({
    mutationFn: ({ ticket, ...input }: { ticket: Ticket; kind: EscalationKind; groupId?: string; reason: string }) =>
      ticketsApi.escalate(ticket, input),
    onSuccess: store,
  });
}

export function useIdentityCheck() {
  const store = useStoreTicket();
  return useMutation({
    mutationFn: ({ id, method, note }: { id: string; method: IdentityMethod; note?: string }) =>
      ticketsApi.identityCheck(id, { method, note }),
    onSuccess: store,
  });
}

/** Ответ или внутренняя заметка; при 422 secrets_detected используйте secretFindings(error) */
export function useAddComment() {
  const invalidate = useInvalidateTickets();
  return useMutation({
    mutationFn: ({ id, input }: { id: string; input: CommentInput }) => ticketsApi.comment(id, input),
    onSuccess: invalidate,
  });
}

export function useLinkTicket() {
  const invalidate = useInvalidateTickets();
  return useMutation({
    mutationFn: ({ id, targetId, kind }: { id: string; targetId: string; kind: LinkKind }) =>
      ticketsApi.link(id, { targetId, kind }),
    onSuccess: invalidate,
  });
}

export function useUnlinkTicket() {
  const invalidate = useInvalidateTickets();
  return useMutation({
    mutationFn: ({ id, linkId }: { id: string; linkId: string }) => ticketsApi.unlink(id, linkId),
    onSuccess: invalidate,
  });
}

export function useAddSubtask() {
  const invalidate = useInvalidateTickets();
  return useMutation({
    mutationFn: ({ id, input }: { id: string; input: SubtaskInput }) => ticketsApi.addSubtask(id, input),
    onSuccess: invalidate,
  });
}

export function useWatch() {
  const invalidate = useInvalidateTickets();
  return useMutation({
    mutationFn: ({ id, user = 'me', watch }: { id: string; user?: string; watch: boolean }) =>
      watch ? ticketsApi.watch(id, user) : ticketsApi.unwatch(id, user),
    onSuccess: invalidate,
  });
}

/** Сначала preview: true, чтобы показать, что будет закрыто, затем выполнение */
export function useMergeTickets() {
  const invalidate = useInvalidateTickets();
  return useMutation({
    mutationFn: ({ targetId, sourceIds, preview }: { targetId: string; sourceIds: string[]; preview?: boolean }) =>
      ticketsApi.merge(targetId, sourceIds, preview),
    onSuccess: (r) => {
      if (!r.preview) invalidate();
    },
  });
}

export function useBulkTickets() {
  const invalidate = useInvalidateTickets();
  return useMutation({
    mutationFn: (input: BulkInput) => ticketsApi.bulk(input),
    onSuccess: (r) => {
      if (!r.preview) invalidate();
    },
  });
}

export function useUndoBulk() {
  const invalidate = useInvalidateTickets();
  return useMutation({ mutationFn: (operationId: string) => ticketsApi.undoBulk(operationId), onSuccess: invalidate });
}

/** Проверка текста до отправки, чтобы предупредить заранее */
export function useSecretScan() {
  return useMutation({ mutationFn: (text: string) => ticketsApi.secretScan(text) });
}

export function useTicketViews() {
  return useQuery({ queryKey: ticketKeys.views, queryFn: ({ signal }) => ticketsApi.views(signal) });
}

export function useSaveTicketView() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ view, input }: { view?: SavedTicketView; input: SavedTicketViewInput }) =>
      view ? ticketsApi.updateView(view, input) : ticketsApi.createView(input),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ticketKeys.views }),
  });
}

export function useDeleteTicketView() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (view: SavedTicketView) => ticketsApi.deleteView(view),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ticketKeys.views }),
  });
}

export function useResponseTemplates(q?: string) {
  return useQuery({
    queryKey: ticketKeys.templates(q),
    queryFn: ({ signal }) => ticketsApi.templates(q, signal),
    placeholderData: keepPreviousData,
  });
}

export function useSaveResponseTemplate() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ template, input }: { template?: ResponseTemplate; input: ResponseTemplateInput }) =>
      template ? ticketsApi.updateTemplate(template, input) : ticketsApi.createTemplate(input),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['tickets', 'templates'] }),
  });
}

export function useDeleteResponseTemplate() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (template: ResponseTemplate) => ticketsApi.deleteTemplate(template),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['tickets', 'templates'] }),
  });
}

/** Подставляет {{ticket.*}} в готовый ответ */
export function renderTemplate(body: string, ticket: Pick<Ticket, 'number' | 'title'> & Partial<Ticket>): string {
  return body.replace(/\{\{\s*ticket\.(\w+)\s*\}\}/g, (match, field: string) => {
    const value = (ticket as Record<string, unknown>)[field];
    return value === undefined || value === null ? match : String(value);
  });
}
