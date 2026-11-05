import { api, ApiError } from '@/shared/api/client';
import {
  ticketsApi as raw,
  type ActivityEntry,
  type BulkItem,
  type CommentInput,
  type SavedTicketView,
  type SavedTicketViewInput,
  type Ticket as RawTicket,
  type TicketDetails as RawTicketDetails,
  type TicketFilter as RawFilter,
  type TicketSort,
  type UpdateTicketInput,
} from '@/features/tickets/api';
import type {
  Activity,
  BulkPreviewItem,
  Directory,
  FieldChange,
  Ref,
  SavedView,
  SlaTimer,
  Ticket,
  TicketFilter,
  TicketQuery,
  TicketSummary,
  UserRef,
} from './model';
import type { TicketsApi } from './ticketsApi';

/**
 * Адаптер рабочего места к модулю заявок: сырые вызовы и типы берутся из features/tickets/api.ts
 * (там же If-Match из version), здесь только перевод в UI-модель.
 *
 * API отдаёт идентификаторы (requesterId, groupId…), экрану нужны имена — их даёт справочник
 * из модулей identity и catalog.
 */

const str = (v: unknown) => (v === null || v === undefined ? null : String(v));
const changesOf = (list: { field: string; from: unknown; to: unknown }[] | undefined): FieldChange[] =>
  (list ?? []).map((c) => ({ field: c.field, from: str(c.from), to: str(c.to) }));

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

function toSummary(t: RawTicket, dir: Directory): TicketSummary {
  // Пока модуль sla не отдаёт таймеры отдельно, ближайший срок — один таймер решения.
  const due = t.slaDueAt ?? t.dueAt;
  let sla: SlaTimer | null = due
    ? { kind: 'resolution', state: new Date(due).getTime() < Date.now() ? 'breached' : 'running', dueAt: due }
    : null;
  if (sla && t.status.category === 'pending') sla = { ...sla, state: 'paused', remainingMs: new Date(sla.dueAt).getTime() - Date.now() };
  return {
    id: t.id,
    number: t.number,
    type: t.type,
    title: t.title,
    priority: t.priority,
    status: t.status,
    requester: userRef(dir, t.requesterId) ?? { id: t.requesterId, name: '' },
    assignee: userRef(dir, t.assigneeId),
    group: ref(dir.groups, t.groupId),
    service: ref(dir.services, t.serviceId),
    category: ref(dir.categories, t.categoryId),
    channel: t.channel,
    confidential: t.confidential,
    sla,
    createdAt: t.createdAt,
    updatedAt: t.updatedAt,
    awaitingAgent: t.awaitingAgent,
  };
}

async function toTicket(t: RawTicketDetails): Promise<Ticket> {
  const dir = await loadDirectory();
  const summary = toSummary(t, dir);
  // Связи и наблюдатели приходят отдельными запросами; без них карточка всё равно открывается.
  const [links, watchers] = await Promise.all([raw.links(t.id).catch(() => []), raw.watchers(t.id).catch(() => [])]);
  return {
    ...summary,
    description: t.description ?? '',
    impact: t.impact,
    urgency: t.urgency,
    watchers: watchers.map((w) => userRef(dir, w.userId)).filter((u): u is UserRef => u !== null),
    requesterInfo: { department: null, phone: null, vip: false },
    slaTimers: summary.sla ? [summary.sla] : [],
    resolutionCode: t.resolutionCode,
    resolutionText: t.resolutionText,
    pendingReason: t.pendingReason,
    pendingUntil: t.pendingUntil,
    priorityManual: t.priorityManual,
    reopenCount: t.reopenCount,
    escalationLevel: t.escalationLevel,
    links: links.map((l) => ({
      id: l.id,
      kind: l.kind,
      ticket: { id: l.ticket.id, number: l.ticket.number, title: l.ticket.title ?? '', status: l.ticket.status },
    })),
    transitions: t.transitions,
    permissions: t.permissions,
    version: t.version,
  };
}

function toActivity(a: ActivityEntry, dir: Directory): Activity {
  const meta = a.meta as { changes?: { field: string; from: unknown; to: unknown }[]; event?: string };
  return {
    id: a.id,
    kind: a.kind,
    visibility: a.visibility,
    author: userRef(dir, a.authorId),
    fromRequester: a.fromRequester,
    body: a.body ?? '',
    changes: meta.changes ? changesOf(meta.changes) : undefined,
    event: meta.event ? { ...a.meta, code: meta.event } : undefined,
    createdAt: a.createdAt,
  };
}

// ——— фильтр и сортировка ———

const SORT: Record<string, TicketSort> = {
  'sla:asc': 'due',
  'priority:asc': 'priority',
  'updatedAt:desc': '-updated',
  'createdAt:desc': '-created',
  'createdAt:asc': 'created',
};

const SORT_BACK: Record<TicketSort, SavedView['sort']> = {
  due: { field: 'sla', dir: 'asc' },
  priority: { field: 'priority', dir: 'asc' },
  '-updated': { field: 'updatedAt', dir: 'desc' },
  '-created': { field: 'createdAt', dir: 'desc' },
  created: { field: 'createdAt', dir: 'asc' },
};

const sortOf = (s: TicketQuery['sort']): TicketSort => SORT[`${s.field}:${s.dir}`] ?? 'due';

/** Фильтр экрана в параметры GET /tickets: списки через запятую, «мои» и «без исполнителя» как в контракте. */
export function toRawFilter(f: TicketFilter, now = Date.now()): RawFilter {
  const list = (v?: string[]) => (v && v.length > 0 ? v.join(',') : undefined);
  // «Мои» — scope контракта, тогда серверу не нужен id текущего пользователя.
  const onlyMe = f.assignee?.length === 1 && f.assignee[0] === 'me';
  return {
    scope: onlyMe ? 'assigned_to_me' : undefined,
    q: f.q?.trim() || undefined,
    statusCategory: list(f.status),
    priority: list(f.priority),
    type: list(f.type),
    groupId: list(f.groupId),
    assigneeId: onlyMe ? undefined : list(f.assignee),
    serviceId: list(f.serviceId),
    // Просрочено — ближайший срок раньше текущего момента, «под угрозой» — в пределах часа.
    dueBefore: f.sla?.includes('at_risk')
      ? new Date(now + 60 * 60_000).toISOString()
      : f.sla?.includes('breached')
        ? new Date(now).toISOString()
        : undefined,
  };
}

/** «Мои» с id текущего пользователя в списке: сервер понимает только конкретные id или none. */
function withMe(filter: RawFilter, f: TicketFilter, meId: string): RawFilter {
  if (filter.scope || !f.assignee?.includes('me')) return filter;
  return { ...filter, assigneeId: f.assignee.map((a) => (a === 'me' ? meId : a)).join(',') };
}

function fromRawView(v: SavedTicketView): SavedView {
  const f = v.filter;
  const split = (s?: string) => (s ? s.split(',') : undefined);
  const filter: TicketFilter = {
    q: f.q,
    status: split(f.statusCategory) as TicketFilter['status'],
    priority: split(f.priority) as TicketFilter['priority'],
    type: split(f.type) as TicketFilter['type'],
    groupId: split(f.groupId),
    assignee: f.scope === 'assigned_to_me' ? ['me'] : split(f.assigneeId),
    serviceId: split(f.serviceId),
  };
  return { id: v.id, name: v.name, visibility: v.visibility, builtIn: false, filter, sort: SORT_BACK[v.sort ?? 'due'] };
}

// ——— массовые действия ———

function bulkParams(action: Parameters<TicketsApi['bulkPreview']>[1]) {
  const { kind, ...params } = action;
  return { action: kind as 'assign' | 'priority' | 'transition', params };
}

function toPreviewItem(item: BulkItem): BulkPreviewItem {
  return {
    ticket: { id: item.ticketId, number: item.number ?? '', title: '' },
    changes: changesOf(item.changes),
    skipReason: item.ok ? undefined : (item.code ?? 'failed'),
  };
}

export const httpTicketsApi: TicketsApi = {
  async list(query, signal) {
    const dir = await loadDirectory();
    const filter = withMe(toRawFilter(query.filter), query.filter, dir.me.id);
    const [page, count] = await Promise.all([
      raw.list(filter, { sort: sortOf(query.sort), cursor: query.cursor, limit: query.limit ?? 50 }, signal),
      query.cursor ? Promise.resolve(null) : raw.count(filter, signal),
    ]);
    return { items: page.items.map((t) => toSummary(t, dir)), nextCursor: page.nextCursor, total: count?.count ?? -1 };
  },

  async countChangedSince(query, since, signal) {
    const dir = await loadDirectory();
    const filter = withMe(toRawFilter(query.filter), query.filter, dir.me.id);
    return (await raw.count({ ...filter, createdFrom: since }, signal)).count;
  },

  get: async (number, signal) => toTicket(await raw.get(number, signal)),
  update: async (id, patch, version) => {
    const { groupId, assigneeId, serviceId, categoryId, ...rest } = patch;
    const input: UpdateTicketInput = { ...rest };
    const clear: NonNullable<UpdateTicketInput['clear']> = [];
    for (const [key, value] of Object.entries({ groupId, assigneeId, serviceId, categoryId }) as [
      'groupId' | 'assigneeId' | 'serviceId' | 'categoryId',
      string | null | undefined,
    ][]) {
      if (value === null) clear.push(key);
      else if (value !== undefined) input[key] = value;
    }
    if (clear.length) input.clear = clear;
    return toTicket(await raw.update({ id, version }, input));
  },
  transition: async (id, request, version) => toTicket(await raw.transition({ id, version }, request)),
  take: async (id, version) => toTicket(await raw.take({ id, version })),
  escalate: async (id, request, version) => toTicket(await raw.escalate({ id, version }, request)),
  secretScan: (text) => raw.secretScan(text),

  async activity(id, signal) {
    const dir = await loadDirectory();
    const page = await raw.activity(id, { limit: 200 }, signal);
    return page.items.map((a) => toActivity(a, dir));
  },
  async addActivity(id, activity, idempotencyKey) {
    const dir = await loadDirectory();
    const input: CommentInput = {
      body: activity.body,
      visibility: activity.kind === 'reply' ? 'public' : 'internal',
      mentions: activity.mentions,
      secrets: activity.secrets,
    };
    // Контракт принимает Idempotency-Key на ответ, чтобы двойная отправка не дала двух писем заявителю.
    return toActivity(await api.post<ActivityEntry>(`/tickets/${id}/activity`, input, { idempotencyKey }), dir);
  },

  async takeNext() {
    const t = await raw.takeNext();
    return t ? toTicket(t) : null;
  },

  async requesterTickets(requesterId, excludeId, signal) {
    const dir = await loadDirectory();
    const page = await raw.list({ requesterId }, { sort: '-created', limit: 6 }, signal);
    return page.items.filter((t) => t.id !== excludeId).slice(0, 5).map((t) => toSummary(t, dir));
  },

  async bulkPreview(ids, action) {
    if (action.kind === 'merge') {
      const res = await raw.merge(action.targetId, ids.filter((x) => x !== action.targetId), true);
      return {
        items: res.sources.map((s) => ({
          ticket: { id: s.id, number: s.number, title: '' },
          changes: s.ok ? [{ field: 'status', from: null, to: 'closed' }] : [],
          skipReason: s.ok ? undefined : (s.code ?? 'merge_invalid'),
        })),
      };
    }
    const res = await raw.bulk({ ticketIds: ids, ...bulkParams(action), preview: true });
    return { items: res.items.map(toPreviewItem) };
  },

  async bulkApply(ids, action) {
    if (action.kind === 'merge') {
      const res = await raw.merge(action.targetId, ids.filter((x) => x !== action.targetId));
      const applied = res.sources.filter((s) => s.ok).length;
      // Слияние сервер не отменяет: история дубликатов сохраняется, отмены нет.
      return { operationId: '', applied, skipped: res.sources.length - applied, undoUntil: new Date().toISOString() };
    }
    const res = await raw.bulk({ ticketIds: ids, ...bulkParams(action) });
    const applied = res.items.filter((i) => i.ok).length;
    if (!res.operationId) throw new ApiError({ status: 500, code: 'bulk_no_operation' });
    return { operationId: res.operationId, applied, skipped: res.items.length - applied, undoUntil: res.undoUntil ?? new Date().toISOString() };
  },

  bulkUndo: async (operationId) => {
    await raw.undoBulk(operationId);
  },

  async views(signal) {
    return (await raw.views(signal)).map(fromRawView);
  },
  async saveView(view) {
    const input: SavedTicketViewInput = {
      name: view.name,
      visibility: view.visibility,
      filter: toRawFilter({ ...view.filter, sla: undefined }),
      sort: sortOf(view.sort),
    };
    return fromRawView(await raw.createView(input));
  },
  // DELETE /ticket-views/{id} в контракте без If-Match, версия не нужна.
  deleteView: (id) => api.delete(`/ticket-views/${id}`),

  directory: () => loadDirectory(),
  templates: (signal) => raw.templates(undefined, signal),
};
