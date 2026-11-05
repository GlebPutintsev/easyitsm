import { ApiError } from '@/shared/api/client';
import { isOpen, priorityOf, scanSecrets, slaRemainingMs } from '../domain';
import type {
  Activity,
  AvailableTransition,
  BulkAction,
  BulkPreviewItem,
  Directory,
  Priority,
  SavedView,
  SlaTimer,
  StatusCategory,
  Ticket,
  TicketFilter,
  TicketQuery,
  TicketSummary,
  TicketPermissions,
  TransitionRequest,
} from '../model';
import type { TicketsApi } from '../ticketsApi';
import {
  agents,
  buildSeed,
  cancelReasons,
  categories,
  groups,
  me,
  pendingReasons,
  requesters,
  resolutionCodes,
  seedActivity,
  services,
  STATUS_NAMES,
  templates,
} from './seed';

/**
 * Работающий в памяти API заявок для разработки интерфейса до готовности модуля tickets.
 * Повторяет правила сервера, которые видит пользователь: матрица приоритета, переходы
 * со статусами 5.1, пауза SLA в «Ожидании», ETag-конфликты, отмена массовой операции.
 */

const MIN = 60_000;
const HOUR = 60 * MIN;
/** Сроки SLA по умолчанию (5.3), упрощённо по графику 24×7. */
const SLA_TARGETS: Record<Priority, { response: number; resolution: number }> = {
  P1: { response: 15 * MIN, resolution: 4 * HOUR },
  P2: { response: 30 * MIN, resolution: 8 * HOUR },
  P3: { response: 4 * HOUR, resolution: 16 * HOUR },
  P4: { response: 8 * HOUR, resolution: 40 * HOUR },
};

/** Цикл инцидента по умолчанию (5.1). В моках код статуса совпадает с категорией. */
const tr = (to: StatusCategory, name: string, requiredFields: AvailableTransition['requiredFields'] = []): AvailableTransition => ({
  to,
  name,
  category: to,
  requiredFields,
});
const TRANSITIONS: Record<StatusCategory, AvailableTransition[]> = {
  draft: [tr('new', 'Отправить')],
  new: [tr('in_progress', 'В работу'), tr('cancelled', 'Отменить', ['reason_code'])],
  approval: [tr('cancelled', 'Отменить', ['reason_code'])],
  assigned: [tr('in_progress', 'В работу'), tr('cancelled', 'Отменить', ['reason_code'])],
  in_progress: [tr('pending', 'Приостановить', ['reason_code', 'pending_until']), tr('resolved', 'Решить', ['resolution'])],
  pending: [tr('in_progress', 'Возобновить'), tr('resolved', 'Решить', ['resolution'])],
  resolved: [tr('in_progress', 'Возобновить', ['comment']), tr('closed', 'Закрыть')],
  closed: [],
  cancelled: [],
};

const PERMISSIONS: TicketPermissions = { edit: true, assign: true, comment: true, internalNotes: true, merge: true, setConfidential: true };

interface Row extends Ticket {
  createdMs: number;
  /** Момент начала паузы SLA, если заявка в «Ожидании». */
  pausedAtMs: number | null;
  /** Накопленное время пауз. */
  pausedTotalMs: number;
}

interface State {
  rows: Row[];
  activity: Map<string, Activity[]>;
  views: SavedView[];
  operations: Map<string, { until: number; snapshot: Row[] }>;
  seq: number;
}

const VIEWS_KEY = 'easyitsm.mock.views';

function status(category: StatusCategory) {
  return { code: category, category, name: STATUS_NAMES[category] };
}

function timersOf(row: Row, now: number): SlaTimer[] {
  const target = SLA_TARGETS[row.priority];
  const paused = row.pausedAtMs !== null;
  const pausedMs = row.pausedTotalMs + (paused ? now - (row.pausedAtMs ?? now) : 0);
  const responseDue = row.createdMs + target.response;
  const resolutionDue = row.createdMs + target.resolution + pausedMs;
  const responded = row.status.category !== 'new' && row.status.category !== 'assigned';
  const resolved = !isOpen(row.status.category);
  const timer = (kind: SlaTimer['kind'], due: number, done: boolean): SlaTimer => {
    if (done) return { kind, state: 'met', dueAt: new Date(due).toISOString() };
    if (paused && kind === 'resolution') {
      return { kind, state: 'paused', dueAt: new Date(due).toISOString(), remainingMs: due - now };
    }
    return { kind, state: due < now ? 'breached' : 'running', dueAt: new Date(due).toISOString() };
  };
  return [timer('response', responseDue, responded), timer('resolution', resolutionDue, resolved)];
}

function refresh(row: Row, now = Date.now()): Row {
  row.slaTimers = timersOf(row, now);
  row.sla = row.slaTimers.find((t) => t.state !== 'met') ?? null;
  row.transitions = TRANSITIONS[row.status.category];
  return row;
}

function summary(row: Row): TicketSummary {
  const {
    id, number, type, title, priority, status: st, requester, assignee, group, service, category,
    channel, confidential, sla, createdAt, updatedAt, awaitingAgent,
  } = row;
  return { id, number, type, title, priority, status: st, requester, assignee, group, service, category, channel, confidential, sla, createdAt, updatedAt, awaitingAgent };
}

function loadViews(): SavedView[] {
  try {
    return JSON.parse(localStorage.getItem(VIEWS_KEY) ?? '[]') as SavedView[];
  } catch {
    return [];
  }
}

function storeViews(views: SavedView[]) {
  try {
    localStorage.setItem(VIEWS_KEY, JSON.stringify(views));
  } catch {
    // хранилище недоступно: представление живёт до перезагрузки
  }
}

const myGroupIds = agents.find((a) => a.id === me.id)?.groupIds ?? [];

function createState(): State {
  const now = Date.now();
  const activity = new Map<string, Activity[]>();
  const rows = buildSeed().map((seed, i) => {
    const createdMs = now - seed.ageMin * MIN;
    const { scenario } = seed;
    const id = `01930000-0000-7000-9000-${(0x1000 + i).toString(16).padStart(12, '0')}`;
    const service = services[scenario.serviceIdx] ?? null;
    const category = categories[scenario.categoryIdx] ?? null;
    const acts = seedActivity(seed, id, createdMs).map((a) => ({
      ...a,
      createdAt: new Date(Math.min(new Date(a.createdAt).getTime(), now - (i % 5) * MIN)).toISOString(),
    }));
    activity.set(id, acts);
    const pending = seed.status === 'pending';
    const lastAct = acts[acts.length - 1];
    const row: Row = {
      id,
      number: `${scenario.type === 'request' ? 'REQ' : 'INC'}-${String(seed.number).padStart(6, '0')}`,
      type: scenario.type,
      title: scenario.title,
      description: scenario.description,
      priority: priorityOf(scenario.impact, scenario.urgency),
      impact: scenario.impact,
      urgency: scenario.urgency,
      status: status(seed.status),
      requester: { id: seed.requester.id, name: seed.requester.name, email: seed.requester.email },
      assignee: seed.assignee,
      group: seed.group,
      service,
      category,
      channel: scenario.channel,
      confidential: scenario.groupIdx === 4,
      sla: null,
      slaTimers: [],
      createdAt: new Date(createdMs).toISOString(),
      updatedAt: lastAct?.createdAt ?? new Date(createdMs).toISOString(),
      awaitingAgent: lastAct?.kind === 'reply' && lastAct.fromRequester,
      watchers: [],
      requesterInfo: { department: seed.requester.department, phone: seed.requester.phone, vip: seed.requester.vip },
      resolutionCode: seed.status === 'resolved' || seed.status === 'closed' ? 'fixed' : null,
      resolutionText: seed.status === 'resolved' || seed.status === 'closed' ? 'Перезапущена служба, проверено с пользователем.' : null,
      pendingReason: pending ? 'requester' : null,
      pendingUntil: pending ? new Date(now + 26 * HOUR).toISOString() : null,
      priorityManual: false,
      reopenCount: 0,
      escalationLevel: 0,
      links: [],
      transitions: [],
      permissions: PERMISSIONS,
      version: 1,
      createdMs,
      pausedAtMs: pending ? now - 40 * MIN : null,
      pausedTotalMs: 0,
    };
    return refresh(row, now);
  });
  return { rows, activity, views: loadViews(), operations: new Map(), seq: rows.length };
}

let state = createState();
const undoActivity = new Map<string, Map<string, Activity[]>>();

/** Сбрасывает данные моков (для тестов). */
export function resetMockState() {
  state = createState();
}

const delay = (ms = 120 + Math.random() * 180) => new Promise((r) => setTimeout(r, import.meta.env.MODE === 'test' ? 0 : ms));

function notFound(): never {
  throw new ApiError({ status: 404, code: 'not_found', detail: 'Заявка не найдена', trace_id: mockTrace() });
}

function mockTrace() {
  return Math.random().toString(16).slice(2, 18);
}

function rowById(id: string): Row {
  return state.rows.find((r) => r.id === id) ?? notFound();
}

function checkVersion(row: Row, version: number) {
  if (row.version !== version) {
    throw new ApiError({
      status: 412,
      code: 'version_conflict',
      detail: 'Запись изменена другим пользователем. Обновите и повторите',
      trace_id: mockTrace(),
    });
  }
}

function matches(row: Row, f: TicketFilter, now: number): boolean {
  if (f.status?.length && !f.status.includes(row.status.category)) return false;
  if (f.priority?.length && !f.priority.includes(row.priority)) return false;
  if (f.type?.length && !f.type.includes(row.type)) return false;
  if (f.groupId?.length && !(row.group && f.groupId.includes(row.group.id))) return false;
  if (f.serviceId?.length && !(row.service && f.serviceId.includes(row.service.id))) return false;
  if (f.assignee?.length) {
    const ok = f.assignee.some((a) =>
      a === 'none' ? row.assignee === null : a === 'me' ? row.assignee?.id === me.id : row.assignee?.id === a,
    );
    if (!ok) return false;
  }
  if (f.sla?.length) {
    const timer = row.sla;
    if (!timer) return false;
    const left = slaRemainingMs(timer, now);
    const ok = f.sla.some((s) => (s === 'breached' ? timer.state === 'breached' : timer.state === 'running' && left < HOUR));
    if (!ok) return false;
  }
  if (f.q?.trim()) {
    const q = f.q.trim().toLowerCase();
    const hay = `${row.number} ${row.title} ${row.description} ${row.requester.name}`.toLowerCase();
    if (!q.split(/\s+/).every((w) => hay.includes(w))) return false;
  }
  return true;
}

const PRIORITY_RANK: Record<Priority, number> = { P1: 1, P2: 2, P3: 3, P4: 4 };

function compare(query: TicketQuery, now: number) {
  const dir = query.sort.dir === 'asc' ? 1 : -1;
  return (a: Row, b: Row) => {
    let d = 0;
    switch (query.sort.field) {
      case 'sla': {
        const ra = a.sla ? slaRemainingMs(a.sla, now) : Number.POSITIVE_INFINITY;
        const rb = b.sla ? slaRemainingMs(b.sla, now) : Number.POSITIVE_INFINITY;
        d = ra === rb ? 0 : ra < rb ? -1 : 1;
        break;
      }
      case 'priority':
        d = PRIORITY_RANK[a.priority] - PRIORITY_RANK[b.priority];
        break;
      case 'number':
        d = a.number.localeCompare(b.number);
        break;
      case 'createdAt':
        d = a.createdMs - b.createdMs;
        break;
      case 'updatedAt':
        d = a.updatedAt.localeCompare(b.updatedAt);
        break;
    }
    return d * dir || b.number.localeCompare(a.number);
  };
}

function addActivity(row: Row, act: Omit<Activity, 'id' | 'createdAt'>, at = Date.now()) {
  const list = state.activity.get(row.id) ?? [];
  const full: Activity = { ...act, id: `${row.id}-a${list.length}-${at}`, createdAt: new Date(at).toISOString() };
  list.push(full);
  state.activity.set(row.id, list);
  return full;
}

function fieldChange(row: Row, field: string, from: string | null, to: string | null) {
  if (from === to) return;
  addActivity(row, { kind: 'change', visibility: 'internal', author: me, fromRequester: false, body: '', changes: [{ field, from, to }] });
}

function note(row: Row, body: string) {
  addActivity(row, { kind: 'note', visibility: 'internal', author: me, fromRequester: false, body });
}

function systemEvent(row: Row, code: string, params: Record<string, unknown> = {}) {
  addActivity(row, { kind: 'system', visibility: 'internal', author: me, fromRequester: false, body: '', event: { code, ...params } });
}

function fail(status: number, code: string, detail: string, extra: Record<string, unknown> = {}): never {
  throw Object.assign(new ApiError({ status, code, detail, trace_id: mockTrace() }), extra);
}

function touch(row: Row) {
  row.version += 1;
  row.updatedAt = new Date().toISOString();
  refresh(row);
}

function applyTransition(row: Row, req: TransitionRequest) {
  const transition = row.transitions.find((t) => t.to === req.to);
  if (!transition) fail(409, 'transition_not_allowed', 'Переход недоступен из текущего статуса');
  const need = transition.requiredFields;
  if (need.includes('resolution') && (!req.resolutionCode || !req.resolutionText?.trim())) {
    fail(400, 'field_required', 'Нужны код и описание решения', { field: 'resolution' });
  }
  if (need.includes('reason_code') && !req.reasonCode) fail(400, 'field_required', 'Нужна причина', { field: 'reason_code' });
  if (need.includes('comment') && !req.comment?.trim()) fail(400, 'field_required', 'Нужен комментарий', { field: 'comment' });
  const now = Date.now();
  const from = row.status.name;
  const fromCategory = row.status.category;
  if (fromCategory === 'pending' && row.pausedAtMs !== null) {
    row.pausedTotalMs += now - row.pausedAtMs;
    row.pausedAtMs = null;
    row.pendingReason = null;
    row.pendingUntil = null;
  }
  if (transition.category === 'in_progress' && !row.assignee) row.assignee = me;
  if (transition.category === 'in_progress' && fromCategory === 'resolved') row.reopenCount += 1;
  if (transition.category === 'pending') {
    row.pausedAtMs = now;
    row.pendingReason = req.reasonCode ?? null;
    row.pendingUntil = req.pendingUntil ?? null;
  }
  if (transition.category === 'resolved') {
    row.resolutionCode = req.resolutionCode ?? null;
    row.resolutionText = req.resolutionText ?? null;
  }
  row.status = status(transition.category);
  fieldChange(row, 'status', from, row.status.name);
  // Комментарий перехода по контракту пишется в ленту публично.
  if (req.comment?.trim()) addActivity(row, { kind: 'reply', visibility: 'public', author: me, fromRequester: false, body: req.comment.trim() });
  row.awaitingAgent = false;
  touch(row);
}

function nameOf(list: { id: string; name: string }[], id: string | null | undefined) {
  return id ? (list.find((x) => x.id === id)?.name ?? null) : null;
}

function bulkItem(row: Row, action: BulkAction): BulkPreviewItem {
  const ticket = { id: row.id, number: row.number, title: row.title };
  switch (action.kind) {
    case 'assign': {
      const changes = [];
      if (action.groupId !== undefined && action.groupId !== row.group?.id) {
        changes.push({ field: 'group', from: row.group?.name ?? null, to: nameOf(groups, action.groupId) });
      }
      if (action.assigneeId !== undefined && action.assigneeId !== row.assignee?.id) {
        changes.push({ field: 'assignee', from: row.assignee?.name ?? null, to: nameOf(agents, action.assigneeId) });
      }
      return { ticket, changes };
    }
    case 'priority':
      return { ticket, changes: row.priority === action.priority ? [] : [{ field: 'priority', from: row.priority, to: action.priority }] };
    case 'transition': {
      const target = row.transitions.find((x) => x.to === action.to);
      if (!target) return { ticket, changes: [], skipReason: 'transition_not_allowed' };
      return { ticket, changes: [{ field: 'status', from: row.status.name, to: STATUS_NAMES[target.category] }] };
    }
    case 'merge':
      if (row.id === action.targetId) return { ticket, changes: [], skipReason: 'merge_target' };
      if (!isOpen(row.status.category)) return { ticket, changes: [], skipReason: 'not_open' };
      return { ticket, changes: [{ field: 'status', from: row.status.name, to: STATUS_NAMES.closed }] };
  }
}

function applyBulk(row: Row, action: BulkAction) {
  switch (action.kind) {
    case 'assign': {
      if (action.groupId !== undefined) {
        const from = row.group?.name ?? null;
        row.group = groups.find((g) => g.id === action.groupId) ?? null;
        fieldChange(row, 'group', from, row.group?.name ?? null);
      }
      if (action.assigneeId !== undefined) {
        const from = row.assignee?.name ?? null;
        const a = agents.find((x) => x.id === action.assigneeId);
        row.assignee = a ? { id: a.id, name: a.name, email: a.email } : null;
        fieldChange(row, 'assignee', from, row.assignee?.name ?? null);
        if (row.assignee && row.status.category === 'new') row.status = status('assigned');
      }
      touch(row);
      return;
    }
    case 'priority':
      fieldChange(row, 'priority', row.priority, action.priority);
      row.priority = action.priority;
      row.priorityManual = true;
      if (action.reason) note(row, action.reason);
      touch(row);
      return;
    case 'transition':
      applyTransition(row, {
        to: action.to,
        comment: action.comment,
        reasonCode: action.reasonCode,
        resolutionCode: action.resolutionCode,
        resolutionText: action.resolutionText,
      });
      return;
    case 'merge': {
      const target = rowById(action.targetId);
      const from = row.status.name;
      row.status = status('closed');
      row.resolutionCode = 'duplicate';
      row.resolutionText = target.number;
      row.links = [...row.links, { id: `${row.id}-l${row.links.length}`, kind: 'duplicate_of', ticket: { id: target.id, number: target.number, title: target.title, status: target.status } }];
      target.links = [...target.links, { id: `${target.id}-l${target.links.length}`, kind: 'duplicated_by', ticket: { id: row.id, number: row.number, title: row.title, status: row.status } }];
      if (!target.watchers.some((w) => w.id === row.requester.id) && target.requester.id !== row.requester.id) {
        target.watchers = [...target.watchers, row.requester];
      }
      fieldChange(row, 'status', from, row.status.name);
      systemEvent(target, 'merged', { source: row.number });
      systemEvent(row, 'merged_into', { target: target.number });
      touch(row);
      touch(target);
    }
  }
}

/** Новая заявка раз в 40 секунд, чтобы было видно плашку «Показать N новых». */
function maybeSpawnTicket() {
  const now = Date.now();
  const last = state.rows.reduce((m, r) => Math.max(m, r.createdMs), 0);
  if (now - last < 40_000) return;
  state.seq += 1;
  const template = state.rows[state.seq % state.rows.length] as Row;
  const requester = requesters[state.seq % requesters.length] as (typeof requesters)[number];
  const number = 1180 + state.seq;
  const row: Row = {
    ...structuredClone(template),
    id: `01930000-0000-7000-9000-${(0x1000 + state.seq).toString(16).padStart(12, '0')}`,
    number: `${template.type === 'request' ? 'REQ' : 'INC'}-${String(number).padStart(6, '0')}`,
    status: status('new'),
    assignee: null,
    requester: { id: requester.id, name: requester.name, email: requester.email },
    requesterInfo: { department: requester.department, phone: requester.phone, vip: requester.vip },
    createdMs: now,
    createdAt: new Date(now).toISOString(),
    updatedAt: new Date(now).toISOString(),
    awaitingAgent: false,
    pausedAtMs: null,
    pausedTotalMs: 0,
    resolutionCode: null,
    resolutionText: null,
    pendingReason: null,
    pendingUntil: null,
    priorityManual: false,
    reopenCount: 0,
    escalationLevel: 0,
    links: [],
    watchers: [],
    version: 1,
  };
  state.rows.push(refresh(row, now));
  state.activity.set(row.id, [
    { id: `${row.id}-a0`, kind: 'system', visibility: 'public', author: null, fromRequester: false, body: '', event: { code: 'created', channel: row.channel }, createdAt: row.createdAt },
  ]);
}

export const mockTicketsApi: TicketsApi = {
  async list(query) {
    await delay();
    maybeSpawnTicket();
    const now = Date.now();
    const all = state.rows.map((r) => refresh(r, now)).filter((r) => matches(r, query.filter, now)).sort(compare(query, now));
    const offset = Number(query.cursor ?? 0);
    const limit = query.limit ?? 50;
    const page = all.slice(offset, offset + limit);
    return { items: page.map(summary), nextCursor: offset + limit < all.length ? String(offset + limit) : null, total: all.length };
  },

  async countChangedSince(query, since) {
    await delay(60);
    maybeSpawnTicket();
    const now = Date.now();
    return state.rows.filter((r) => r.createdAt > since && matches(r, query.filter, now)).length;
  },

  async get(number) {
    await delay();
    const row = state.rows.find((r) => r.number === number || r.id === number) ?? notFound();
    return structuredClone(refresh(row));
  },

  async update(id, patch, version) {
    await delay();
    const row = rowById(id);
    checkVersion(row, version);
    if (patch.impact || patch.urgency) {
      const impact = patch.impact ?? row.impact;
      const urgency = patch.urgency ?? row.urgency;
      if (impact !== row.impact) fieldChange(row, 'impact', row.impact, impact);
      if (urgency !== row.urgency) fieldChange(row, 'urgency', row.urgency, urgency);
      row.impact = impact;
      row.urgency = urgency;
      const p = priorityOf(impact, urgency);
      fieldChange(row, 'priority', row.priority, p);
      row.priority = p;
    }
    if (patch.priority && patch.priority !== row.priority) {
      fieldChange(row, 'priority', row.priority, patch.priority);
      row.priority = patch.priority;
      row.priorityManual = true;
      if (patch.priorityReason) note(row, patch.priorityReason);
    }
    if (patch.groupId !== undefined) {
      const g = groups.find((x) => x.id === patch.groupId) ?? null;
      fieldChange(row, 'group', row.group?.name ?? null, g?.name ?? null);
      row.group = g;
    }
    if (patch.assigneeId !== undefined) {
      const a = agents.find((x) => x.id === patch.assigneeId);
      const next = a ? { id: a.id, name: a.name, email: a.email } : null;
      fieldChange(row, 'assignee', row.assignee?.name ?? null, next?.name ?? null);
      row.assignee = next;
      if (next && row.status.category === 'new') row.status = status('assigned');
    }
    if (patch.serviceId !== undefined) {
      const s = services.find((x) => x.id === patch.serviceId) ?? null;
      fieldChange(row, 'service', row.service?.name ?? null, s?.name ?? null);
      row.service = s;
    }
    if (patch.categoryId !== undefined) {
      const c = categories.find((x) => x.id === patch.categoryId) ?? null;
      fieldChange(row, 'category', row.category?.name ?? null, c?.name ?? null);
      row.category = c;
    }
    if (patch.title !== undefined && patch.title.trim() && patch.title !== row.title) {
      fieldChange(row, 'title', row.title, patch.title.trim());
      row.title = patch.title.trim();
    }
    touch(row);
    return structuredClone(row);
  },

  async transition(id, request, version) {
    await delay();
    const row = rowById(id);
    checkVersion(row, version);
    applyTransition(row, request);
    return structuredClone(row);
  },

  async take(id, version) {
    await delay();
    const row = rowById(id);
    checkVersion(row, version);
    if (row.assignee?.id !== me.id) {
      fieldChange(row, 'assignee', row.assignee?.name ?? null, me.name);
      row.assignee = me;
    }
    if (row.status.category !== 'in_progress') applyTransition(refresh(row), { to: 'in_progress' });
    else touch(row);
    return structuredClone(row);
  },

  async escalate(id, request, version) {
    await delay();
    const row = rowById(id);
    checkVersion(row, version);
    row.escalationLevel += 1;
    if (request.kind === 'functional' && request.groupId) {
      const g = groups.find((x) => x.id === request.groupId) ?? null;
      fieldChange(row, 'group', row.group?.name ?? null, g?.name ?? null);
      row.group = g;
      if (row.assignee) fieldChange(row, 'assignee', row.assignee.name, null);
      row.assignee = null;
    }
    systemEvent(row, 'escalated', { kind: request.kind, level: row.escalationLevel });
    note(row, request.reason);
    touch(row);
    return structuredClone(row);
  },

  async secretScan(text) {
    await delay(60);
    return scanSecrets(text);
  },

  async activity(id) {
    await delay(80);
    return structuredClone(state.activity.get(id) ?? []);
  },

  async addActivity(id, activity) {
    await delay();
    const row = rowById(id);
    if (row.status.category === 'closed') fail(409, 'ticket_closed', 'Заявка закрыта, создайте новую связанную');
    const scan = scanSecrets(activity.body);
    if (scan.findings.length && (activity.secrets ?? 'warn') === 'warn') {
      fail(422, 'secrets_detected', 'В тексте найдены секреты', { findings: scan.findings });
    }
    const created = addActivity(row, {
      kind: activity.kind,
      visibility: activity.kind === 'reply' ? 'public' : 'internal',
      author: me,
      fromRequester: false,
      body: activity.secrets === 'mask' ? scan.masked : activity.body,
    });
    if (activity.kind === 'reply') row.awaitingAgent = false;
    touch(row);
    return created;
  },

  async takeNext() {
    await delay();
    const now = Date.now();
    const candidates = state.rows
      .map((r) => refresh(r, now))
      .filter((r) => r.assignee === null && (r.status.category === 'new' || r.status.category === 'assigned') && r.group && myGroupIds.includes(r.group.id))
      .sort(compare({ filter: {}, sort: { field: 'sla', dir: 'asc' } }, now));
    const next = candidates[0];
    if (!next) return null;
    next.assignee = me;
    fieldChange(next, 'assignee', null, me.name);
    applyTransition(next, { to: 'in_progress' });
    return structuredClone(next);
  },

  async requesterTickets(requesterId, excludeId) {
    await delay(100);
    return state.rows
      .filter((r) => r.requester.id === requesterId && r.id !== excludeId)
      .sort((a, b) => b.createdMs - a.createdMs)
      .slice(0, 5)
      .map(summary);
  },

  async bulkPreview(ids, action) {
    await delay();
    return { items: ids.map((id) => bulkItem(refresh(rowById(id)), action)) };
  },

  async bulkApply(ids, action) {
    await delay();
    const rows = ids.map(rowById);
    const snapshot = rows.map((r) => structuredClone(r));
    const activitySnapshot = new Map(rows.map((r) => [r.id, [...(state.activity.get(r.id) ?? [])]]));
    if (action.kind === 'merge') {
      const target = rowById(action.targetId);
      snapshot.push(structuredClone(target));
      activitySnapshot.set(target.id, [...(state.activity.get(target.id) ?? [])]);
    }
    let applied = 0;
    let skipped = 0;
    for (const row of rows) {
      const item = bulkItem(row, action);
      if (item.skipReason || item.changes.length === 0) {
        skipped += 1;
        continue;
      }
      applyBulk(row, action);
      applied += 1;
    }
    const operationId = `op-${Date.now()}`;
    const until = Date.now() + 10_000;
    state.operations.set(operationId, { until: until + 2_000, snapshot });
    undoActivity.set(operationId, activitySnapshot);
    return { operationId, applied, skipped, undoUntil: new Date(until).toISOString() };
  },

  async bulkUndo(operationId) {
    await delay();
    const op = state.operations.get(operationId);
    if (!op || Date.now() > op.until) {
      throw new ApiError({ status: 410, code: 'undo_expired', detail: 'Время отмены истекло', trace_id: mockTrace() });
    }
    for (const saved of op.snapshot) {
      const idx = state.rows.findIndex((r) => r.id === saved.id);
      if (idx >= 0) state.rows[idx] = { ...saved, version: (state.rows[idx] as Row).version + 1 };
    }
    for (const [id, acts] of undoActivity.get(operationId) ?? []) state.activity.set(id, acts);
    state.operations.delete(operationId);
  },

  async views() {
    await delay(60);
    return structuredClone(state.views);
  },

  async saveView(view) {
    await delay();
    const saved: SavedView = { ...view, id: `view-${Date.now()}`, builtIn: false };
    state.views.push(saved);
    storeViews(state.views);
    return saved;
  },

  async deleteView(id) {
    await delay();
    state.views = state.views.filter((v) => v.id !== id);
    storeViews(state.views);
  },

  async directory(): Promise<Directory> {
    await delay(60);
    return { me, groups, agents, services, categories, resolutionCodes, pendingReasons, cancelReasons, users: requesters };
  },

  async templates() {
    await delay(60);
    return templates;
  },
};

