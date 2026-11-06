/**
 * Модель заявки для экранов рабочего места агента.
 *
 * Следует контракту модуля tickets (docs/api/tickets.yaml), но с уже разрешёнными именами:
 * в API приходят requesterId, groupId и т. п., а экрану нужны имена. Сопоставление и
 * недостающие поля (SLA из модуля sla) — забота адаптера httpTicketsApi.ts, не компонентов.
 */

export type TicketType = 'incident' | 'request' | 'problem' | 'change' | 'task';
export type Priority = 'P1' | 'P2' | 'P3' | 'P4';
export type Level = 'high' | 'medium' | 'low';
export type Channel = 'portal' | 'email' | 'api' | 'chat' | 'monitoring' | 'agent';

/**
 * Статус приходит из настраиваемого жизненного цикла. Для логики UI важна только
 * категория, а название показывается как есть.
 */
export type StatusCategory =
  | 'draft'
  | 'new'
  | 'approval'
  | 'assigned'
  | 'in_progress'
  | 'pending'
  | 'resolved'
  | 'closed'
  | 'cancelled';

export interface StatusRef {
  /** Код статуса из настраиваемого цикла, например in_progress или fulfilled. */
  code: string;
  category: StatusCategory;
  name: string;
}

export interface UserRef {
  id: string;
  name: string;
  email?: string;
}

export interface GroupRef {
  id: string;
  name: string;
}

export interface Ref {
  id: string;
  name: string;
}

export type SlaKind = 'response' | 'resolution';
export type SlaState = 'running' | 'paused' | 'met' | 'breached';

export interface SlaTimer {
  kind: SlaKind;
  state: SlaState;
  /** Срок по графику. Для приостановленного таймера — срок на момент паузы. */
  dueAt: string;
  /** Для приостановленного таймера: сколько оставалось на момент паузы. */
  remainingMs?: number;
}

/** Строка очереди (10.6): только то, что нужно для таблицы. */
export interface TicketSummary {
  id: string;
  number: string;
  type: TicketType;
  title: string;
  priority: Priority;
  status: StatusRef;
  requester: UserRef;
  assignee: UserRef | null;
  group: GroupRef | null;
  service: Ref | null;
  category: Ref | null;
  channel: Channel;
  confidential: boolean;
  /** Ближайший активный таймер SLA, по нему сортируется срочность. */
  sla: SlaTimer | null;
  createdAt: string;
  updatedAt: string;
  /** Есть ответ заявителя, который агент ещё не видел. */
  awaitingAgent: boolean;
}

/** pending_reason — то же, что reason_code для паузы: модуль заявок может прислать любой из двух. */
export type RequiredField = 'comment' | 'reason_code' | 'pending_reason' | 'resolution' | 'pending_until' | 'group' | 'assignee';

/** Переход, доступный текущему пользователю (AvailableTransition контракта). */
export interface AvailableTransition {
  /** Код целевого статуса. */
  to: string;
  /** Название действия, например «Решить». */
  name: string;
  category: StatusCategory;
  requiredFields: RequiredField[];
}

/** Что может текущий пользователь. Только для интерфейса, сервер проверяет сам (ACC-01). */
export interface TicketPermissions {
  edit: boolean;
  assign: boolean;
  comment: boolean;
  internalNotes: boolean;
  merge: boolean;
  setConfidential: boolean;
}

export interface Ticket extends TicketSummary {
  description: string;
  impact: Level;
  urgency: Level;
  watchers: UserRef[];
  /** Карточка заявителя для правой панели (10.7). */
  requesterInfo: { department: string | null; phone: string | null; vip: boolean };
  slaTimers: SlaTimer[];
  resolutionCode: string | null;
  resolutionText: string | null;
  pendingReason: string | null;
  pendingUntil: string | null;
  priorityManual: boolean;
  reopenCount: number;
  escalationLevel: number;
  links: TicketLink[];
  transitions: AvailableTransition[];
  permissions: TicketPermissions;
  /** Версия записи: ETag = "\"<version>\"", передаётся в If-Match (API-05). */
  version: number;
}

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

export interface TicketLink {
  id: string;
  kind: LinkKind;
  /** Без прав на связанную заявку сервер отдаёт только номер: название пустое, статуса нет. */
  ticket: Pick<TicketSummary, 'id' | 'number' | 'title'> & { status: StatusRef | null };
}

export type ActivityKind = 'reply' | 'note' | 'system' | 'change';

export interface FieldChange {
  field: string;
  from: string | null;
  to: string | null;
}

export interface Activity {
  id: string;
  kind: ActivityKind;
  /** public — видит заявитель, internal — только агенты. */
  visibility: 'public' | 'internal';
  author: UserRef | null;
  fromRequester: boolean;
  /** Markdown без HTML: **жирный**, списки, ссылки, `код`. Рендерится без dangerouslySetInnerHTML. */
  body: string;
  /** Для kind=change: что изменилось. */
  changes?: FieldChange[];
  /** Для kind=system: код события (created, merged, escalated…) и его параметры. */
  event?: { code: string; [key: string]: unknown };
  createdAt: string;
}

// ——— Запросы ———

export type SortField = 'sla' | 'priority' | 'updatedAt' | 'createdAt' | 'number';
export type SortDir = 'asc' | 'desc';

export interface TicketFilter {
  /** Полнотекстовый поиск по номеру, теме и описанию. */
  q?: string;
  status?: StatusCategory[];
  priority?: Priority[];
  type?: TicketType[];
  groupId?: string[];
  /** 'me' — текущий пользователь, 'none' — без исполнителя, иначе id пользователя. */
  assignee?: string[];
  /** breached — просрочено, at_risk — осталось меньше 25 % времени. */
  sla?: ('breached' | 'at_risk')[];
  serviceId?: string[];
}

export interface TicketQuery {
  filter: TicketFilter;
  sort: { field: SortField; dir: SortDir };
  cursor?: string;
  limit?: number;
}

export type ViewVisibility = 'personal' | 'group' | 'shared';

export interface SavedView {
  id: string;
  name: string;
  visibility: ViewVisibility;
  /** Встроенные представления нельзя изменить или удалить. */
  builtIn: boolean;
  filter: TicketFilter;
  sort: { field: SortField; dir: SortDir };
}

export interface TicketPatch {
  impact?: Level;
  urgency?: Level;
  priority?: Priority;
  priorityReason?: string;
  groupId?: string | null;
  assigneeId?: string | null;
  serviceId?: string | null;
  categoryId?: string | null;
  title?: string;
}

export interface TransitionRequest {
  /** Код целевого статуса из AvailableTransition.to. */
  to: string;
  /** Пишется в ленту публично. */
  comment?: string;
  /** Причина ожидания или отмены. */
  reasonCode?: string;
  resolutionCode?: string;
  resolutionText?: string;
  pendingUntil?: string;
}

export type SecretsPolicy = 'warn' | 'mask' | 'allow';

export interface NewActivity {
  kind: 'reply' | 'note';
  body: string;
  mentions?: string[];
  /** warn — сервер вернёт 422 secrets_detected; mask — замаскирует; allow — сохранит как есть. */
  secrets?: SecretsPolicy;
}

export interface SecretFinding {
  kind: string;
  start: number;
  end: number;
  masked: string;
}

export interface EscalationRequest {
  kind: 'functional' | 'hierarchical';
  groupId?: string;
  reason: string;
}

// ——— Массовые операции (TCK-12) ———

export type BulkAction =
  | { kind: 'assign'; groupId?: string | null; assigneeId?: string | null }
  | { kind: 'priority'; priority: Priority; reason: string }
  | { kind: 'transition'; to: string; comment?: string; reasonCode?: string; resolutionCode?: string; resolutionText?: string }
  | { kind: 'merge'; targetId: string };

export interface BulkPreviewItem {
  ticket: Pick<TicketSummary, 'id' | 'number' | 'title'>;
  /** Что изменится; пусто — заявка пропускается. */
  changes: { field: string; from: string | null; to: string | null }[];
  /** Причина пропуска, например «нет права» или «переход недоступен». */
  skipReason?: string;
}

export interface BulkPreview {
  items: BulkPreviewItem[];
}

export interface BulkResult {
  /** Идентификатор операции для отмены в течение 10 секунд. */
  operationId: string;
  applied: number;
  skipped: number;
  undoUntil: string;
}

// ——— Справочники ———

export interface Directory {
  me: UserRef;
  groups: GroupRef[];
  /** Агенты с группами и текущей загрузкой, для назначения. */
  agents: (UserRef & { groupIds: string[]; openTickets: number; available: boolean })[];
  /** Прочие известные пользователи (заявители) — только в моках. На настоящем API адаптер берёт их имена из /people?ids=. */
  users?: UserRef[];
  services: Ref[];
  categories: Ref[];
  resolutionCodes: Ref[];
  pendingReasons: Ref[];
  cancelReasons: Ref[];
}

export interface ResponseTemplate {
  id: string;
  name: string;
  /** Текст с переменными {{requester.firstName}}, {{ticket.number}}, {{agent.name}}. */
  body: string;
}
