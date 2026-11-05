import { api, ApiError, type CursorPage } from '@/shared/api/client';
import type {
  Activity,
  AvailableTransition,
  BulkPreviewItem,
  Directory,
  Level,
  Priority,
  Ref,
  SavedView,
  SlaTimer,
  StatusRef,
  Ticket,
  TicketFilter,
  TicketLink,
  TicketPermissions,
  TicketQuery,
  TicketSummary,
  TicketType,
  Channel,
  UserRef,
} from './model';
import type { TicketsApi } from './ticketsApi';

/**
 * Адаптер к REST /api/v1 модуля tickets (docs/api/tickets.yaml).
 *
 * API отдаёт идентификаторы (requesterId, groupId…), экрану нужны имена — их даёт справочник
 * из модулей identity и catalog. Когда модуль tickets положит сгенерированные типы
 * в features/tickets/api.ts, Raw* ниже заменяются импортом оттуда.
 */

interface RawTicket {
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
  groupId: string | null;
  assigneeId: string | null;
  serviceId: string | null;
  categoryId: string | null;
  channel: Channel;
  confidential: boolean;
  pendingReason: string | null;
  pendingUntil: string | null;
  resolutionCode: string | null;
  resolutionText: string | null;
  reopenCount: number;
  escalationLevel: number;
  slaDueAt?: string | null;
  awaitingAgent?: boolean;
  createdAt: string;
  updatedAt: string;
  version: number;
}

interface RawTicketDetails extends RawTicket {
  description: string;
  transitions: AvailableTransition[];
  permissions: TicketPermissions;
}

interface RawActivity {
  id: string;
  kind: Activity['kind'];
  visibility: Activity['visibility'];
  authorId: string | null;
  fromRequester?: boolean;
  body: string;
  meta: { changes?: { field: string; from: unknown; to: unknown }[]; event?: string; [k: string]: unknown };
  createdAt: string;
}

interface RawBulkResult {
  preview: boolean;
  operationId: string | null;
  undoUntil: string | null;
  items: { ticketId: string; number?: string; ok: boolean; code?: string | null; changes?: { field: string; from: unknown; to: unknown }[] }[];
}

const etag = (version: number) => `"${version}"`;
const str = (v: unknown) => (v === null || v === undefined ? null : String(v));

// ——— справочник имён ———

let directoryCache: Promise<Directory> | null = null;

function loadDirectory(): Promise<Directory> {
  // Справочник нужен почти каждому ответу, поэтому кэшируется на сессию страницы.
  directoryCache ??= api.get<Directory>('/agent-workspace/directory').catch((e: unknown) => {
    directoryCache = null;
    throw e;
  });
  return directoryCache;
}

function userRef(dir: Directory, id: string | null): UserRef | null {
  if (!id) return null;
  return dir.agents.find((a) => a.id === id) ?? dir.users?.find((u) => u.id === id) ?? { id, name: id.slice(0, 8) };
}

function ref(list: Ref[], id: string | null): Ref | null {
  if (!id) return null;
  return list.find((x) => x.id === id) ?? { id, name: id.slice(0, 8) };
}

function toSummary(raw: RawTicket, dir: Directory): TicketSummary {
  const sla: SlaTimer | null = raw.slaDueAt
    ? { kind: 'resolution', state: new Date(raw.slaDueAt).getTime() < Date.now() ? 'breached' : 'running', dueAt: raw.slaDueAt }
    : null;
  return {
    id: raw.id,
    number: raw.number,
    type: raw.type,
    title: raw.title,
    priority: raw.priority,
    status: raw.status,
    requester: userRef(dir, raw.requesterId) ?? { id: raw.requesterId, name: '' },
    assignee: userRef(dir, raw.assigneeId),
    group: ref(dir.groups, raw.groupId),
    service: ref(dir.services, raw.serviceId),
    category: ref(dir.categories, raw.categoryId),
    channel: raw.channel,
    confidential: raw.confidential,
    sla: raw.status.category === 'pending' && sla ? { ...sla, state: 'paused', remainingMs: new Date(sla.dueAt).getTime() - Date.now() } : sla,
    createdAt: raw.createdAt,
    updatedAt: raw.updatedAt,
    awaitingAgent: raw.awaitingAgent ?? false,
  };
}

async function toTicket(raw: RawTicketDetails): Promise<Ticket> {
  const dir = await loadDirectory();
  const summary = toSummary(raw, dir);
  // Связи и наблюдатели приходят отдельными запросами; карточка показывает их, когда загрузятся.
  const [links, watchers] = await Promise.all([
    api.get<{ id: string; kind: TicketLink['kind']; ticket: TicketLink['ticket'] }[]>(`/tickets/${raw.id}/links`).catch(() => []),
    api.get<{ userId: string }[]>(`/tickets/${raw.id}/watchers`).catch(() => []),
  ]);
  return {
    ...summary,
    description: raw.description,
    impact: raw.impact,
    urgency: raw.urgency,
    watchers: watchers.map((w) => userRef(dir, w.userId)).filter((u): u is UserRef => u !== null),
    requesterInfo: { department: null, phone: null, vip: false },
    slaTimers: summary.sla ? [summary.sla] : [],
    resolutionCode: raw.resolutionCode,
    resolutionText: raw.resolutionText,
    pendingReason: raw.pendingReason,
    pendingUntil: raw.pendingUntil,
    priorityManual: raw.priorityManual,
    reopenCount: raw.reopenCount,
    escalationLevel: raw.escalationLevel,
    links,
    transitions: raw.transitions,
    permissions: raw.permissions,
    version: raw.version,
  };
}

function toActivity(raw: RawActivity, dir: Directory): Activity {
  return {
    id: raw.id,
    kind: raw.kind,
    visibility: raw.visibility,
    author: userRef(dir, raw.authorId),
    fromRequester: raw.fromRequester ?? false,
    body: raw.body,
    changes: raw.meta.changes?.map((c) => ({ field: c.field, from: str(c.from), to: str(c.to) })),
    event: raw.meta.event ? { ...raw.meta, code: raw.meta.event } : undefined,
    createdAt: raw.createdAt,
  };
}

// ——— представления ———

interface RawView {
  id?: string;
  name: string;
  visibility: SavedView['visibility'];
  filter: Record<string, string>;
  sort?: string;
}

const SORT_BACK: Record<string, SavedView['sort']> = {
  due: { field: 'sla', dir: 'asc' },
  priority: { field: 'priority', dir: 'asc' },
  '-updated': { field: 'updatedAt', dir: 'desc' },
  '-created': { field: 'createdAt', dir: 'desc' },
  created: { field: 'createdAt', dir: 'asc' },
};

function fromRawView(raw: RawView): SavedView {
  const f = raw.filter;
  const split = (v?: string) => (v ? v.split(',') : undefined);
  const filter: TicketFilter = {
    q: f.q,
    status: split(f.statusCategory) as TicketFilter['status'],
    priority: split(f.priority) as TicketFilter['priority'],
    type: split(f.type) as TicketFilter['type'],
    groupId: split(f.groupId),
    assignee: split(f.assigneeId),
    serviceId: split(f.serviceId),
  };
  return { id: raw.id ?? '', name: raw.name, visibility: raw.visibility, builtIn: false, filter, sort: SORT_BACK[raw.sort ?? 'due'] ?? { field: 'sla', dir: 'asc' } };
}

// ——— параметры ———

const SORT: Record<string, string> = {
  'sla:asc': 'due',
  'priority:asc': 'priority',
  'updatedAt:desc': '-updated',
  'createdAt:desc': '-created',
  'createdAt:asc': 'created',
};

/** Фильтр экрана в параметры GET /tickets: списки через запятую, «я» и «без исполнителя» как в контракте. */
export function toQueryParams(query: TicketQuery, meId?: string): Record<string, string | number | undefined> {
  const f: TicketFilter = query.filter;
  const list = (v?: string[]) => (v && v.length > 0 ? v.join(',') : undefined);
  const assignee = f.assignee?.map((a) => (a === 'me' ? (meId ?? a) : a));
  return {
    q: f.q?.trim() || undefined,
    statusCategory: list(f.status),
    priority: list(f.priority),
    type: list(f.type),
    groupId: list(f.groupId),
    assigneeId: list(assignee),
    serviceId: list(f.serviceId),
    // Просрочено — срок SLA раньше текущего момента, «под угрозой» — в пределах часа (параметр dueBefore).
    dueBefore: f.sla?.includes('at_risk')
      ? new Date(Date.now() + 60 * 60_000).toISOString()
      : f.sla?.includes('breached')
        ? new Date().toISOString()
        : undefined,
    sort: SORT[`${query.sort.field}:${query.sort.dir}`] ?? 'due',
    cursor: query.cursor,
    limit: query.limit ?? 50,
  };
}

function bulkParams(action: Parameters<TicketsApi['bulkPreview']>[1]): { action: string; params: Record<string, unknown> } {
  const { kind, ...params } = action;
  return { action: kind, params };
}

function toPreviewItem(item: RawBulkResult['items'][number], titles: Map<string, string>): BulkPreviewItem {
  return {
    ticket: { id: item.ticketId, number: item.number ?? '', title: titles.get(item.ticketId) ?? '' },
    changes: (item.changes ?? []).map((c) => ({ field: c.field, from: str(c.from), to: str(c.to) })),
    skipReason: item.ok ? undefined : (item.code ?? 'failed'),
  };
}

export const httpTicketsApi: TicketsApi = {
  async list(query, signal) {
    const dir = await loadDirectory();
    const params = toQueryParams(query, dir.me.id);
    const [page, count] = await Promise.all([
      api.get<CursorPage<RawTicket>>('/tickets', { query: params, signal }),
      query.cursor
        ? Promise.resolve(null)
        : api.get<{ count: number }>('/tickets/count', { query: { ...params, cursor: undefined, limit: undefined, sort: undefined }, signal }),
    ]);
    return { items: page.items.map((t) => toSummary(t, dir)), nextCursor: page.nextCursor, total: count?.count ?? -1 };
  },

  async countChangedSince(query, since, signal) {
    const dir = await loadDirectory();
    const params = toQueryParams(query, dir.me.id);
    const { count } = await api.get<{ count: number }>('/tickets/count', {
      query: { ...params, cursor: undefined, limit: undefined, sort: undefined, createdFrom: since },
      signal,
    });
    return count;
  },

  get: async (number, signal) => toTicket(await api.get<RawTicketDetails>(`/tickets/${encodeURIComponent(number)}`, { signal })),
  update: async (id, patch, version) => {
    const { groupId, assigneeId, serviceId, categoryId, ...rest } = patch;
    const clear: string[] = [];
    const body: Record<string, unknown> = { ...rest };
    for (const [key, value] of Object.entries({ groupId, assigneeId, serviceId, categoryId })) {
      if (value === null) clear.push(key);
      else if (value !== undefined) body[key] = value;
    }
    if (clear.length) body.clear = clear;
    return toTicket(await api.patch<RawTicketDetails>(`/tickets/${id}`, body, { ifMatch: etag(version) }));
  },
  transition: async (id, request, version) =>
    toTicket(await api.post<RawTicketDetails>(`/tickets/${id}/transitions`, request, { ifMatch: etag(version) })),
  take: async (id, version) => toTicket(await api.post<RawTicketDetails>(`/tickets/${id}/take`, {}, { ifMatch: etag(version) })),
  escalate: async (id, request, version) =>
    toTicket(await api.post<RawTicketDetails>(`/tickets/${id}/escalate`, request, { ifMatch: etag(version) })),
  secretScan: (text) => api.post('/tickets/secret-scan', { text }),

  async activity(id, signal) {
    const dir = await loadDirectory();
    const page = await api.get<CursorPage<RawActivity>>(`/tickets/${id}/activity`, { query: { limit: 200 }, signal });
    return page.items.map((a) => toActivity(a, dir));
  },
  async addActivity(id, activity, idempotencyKey) {
    const dir = await loadDirectory();
    const raw = await api.post<RawActivity>(
      `/tickets/${id}/activity`,
      { body: activity.body, visibility: activity.kind === 'reply' ? 'public' : 'internal', mentions: activity.mentions, secrets: activity.secrets },
      { idempotencyKey },
    );
    return toActivity(raw, dir);
  },

  async takeNext() {
    // 204 — в очередях групп нет подходящей заявки.
    const raw = await api.post<RawTicketDetails | undefined>('/tickets/take-next', {});
    return raw ? toTicket(raw) : null;
  },

  async requesterTickets(requesterId, excludeId, signal) {
    const dir = await loadDirectory();
    const page = await api.get<CursorPage<RawTicket>>('/tickets', { query: { requesterId, sort: '-created', limit: 6 }, signal });
    return page.items.filter((t) => t.id !== excludeId).slice(0, 5).map((t) => toSummary(t, dir));
  },

  async bulkPreview(ids, action) {
    if (action.kind === 'merge') {
      const res = await api.post<{ sources: { id: string; number: string; ok: boolean; code: string | null }[] }>(
        `/tickets/${action.targetId}/merge`,
        { sourceIds: ids.filter((x) => x !== action.targetId), preview: true },
      );
      return {
        items: res.sources.map((s) => ({
          ticket: { id: s.id, number: s.number, title: '' },
          changes: s.ok ? [{ field: 'status', from: null, to: 'closed' }] : [],
          skipReason: s.ok ? undefined : (s.code ?? 'merge_invalid'),
        })),
      };
    }
    const res = await api.post<RawBulkResult>('/tickets/bulk', { ticketIds: ids, ...bulkParams(action), preview: true });
    return { items: res.items.map((i) => toPreviewItem(i, new Map())) };
  },

  async bulkApply(ids, action, idempotencyKey) {
    if (action.kind === 'merge') {
      const res = await api.post<{ sources: { ok: boolean }[] }>(
        `/tickets/${action.targetId}/merge`,
        { sourceIds: ids.filter((x) => x !== action.targetId) },
        { idempotencyKey },
      );
      const applied = res.sources.filter((s) => s.ok).length;
      // Слияние сервер не отменяет: история дубликатов сохраняется, отмены нет.
      return { operationId: '', applied, skipped: res.sources.length - applied, undoUntil: new Date().toISOString() };
    }
    const res = await api.post<RawBulkResult>('/tickets/bulk', { ticketIds: ids, ...bulkParams(action) }, { idempotencyKey });
    const applied = res.items.filter((i) => i.ok).length;
    if (!res.operationId) throw new ApiError({ status: 500, code: 'bulk_no_operation' });
    return { operationId: res.operationId, applied, skipped: res.items.length - applied, undoUntil: res.undoUntil ?? new Date().toISOString() };
  },

  bulkUndo: async (operationId) => {
    await api.post(`/tickets/bulk/${operationId}/undo`, {});
  },

  async views(signal) {
    const raw = await api.get<RawView[]>('/ticket-views', { signal });
    return raw.map(fromRawView);
  },
  async saveView(view, idempotencyKey) {
    const body: RawView = {
      name: view.name,
      visibility: view.visibility,
      filter: Object.fromEntries(
        Object.entries(toQueryParams({ filter: view.filter, sort: view.sort })).filter(
          ([k, v]) => v !== undefined && k !== 'sort' && k !== 'limit' && k !== 'cursor',
        ),
      ) as Record<string, string>,
      sort: SORT[`${view.sort.field}:${view.sort.dir}`] ?? 'due',
    };
    return fromRawView(await api.post<RawView>('/ticket-views', body, { idempotencyKey }));
  },
  deleteView: (id) => api.delete(`/ticket-views/${id}`),

  directory: () => loadDirectory(),
  templates: (signal) => api.get<{ id: string; name: string; body: string }[]>('/response-templates', { signal }),
};
