/**
 * Модель конструктора правил: события, поля условий, операции и значения. Повторяет проверки сервера
 * (RuleValidator), чтобы конструктор не предлагал того, что сервер отклонит.
 */

export const TRIGGERS = [
  'ticket_created',
  'status_changed',
  'priority_changed',
  'assignment_changed',
  'ticket_updated',
  'comment_added',
  'escalated',
  'sla_warning',
  'sla_breached',
] as const;
export type Trigger = (typeof TRIGGERS)[number];

export const OPS = ['eq', 'neq', 'in', 'not_in', 'empty', 'not_empty', 'contains', 'not_contains', 'gte', 'lte'] as const;
export type Op = (typeof OPS)[number];

/** Как вводится значение поля в конструкторе. */
export type ValueKind = 'enum' | 'status' | 'group' | 'person' | 'text' | 'uuid' | 'number' | 'list';

export interface FieldDef {
  key: string;
  kind: ValueKind;
  /** Набор значений для kind = enum: ключ перевода automation.values.<options> */
  options?: string;
  values?: string[];
}

const LEVELS = ['high', 'medium', 'low'];
const CATEGORIES = ['draft', 'new', 'approval', 'assigned', 'in_progress', 'pending', 'resolved', 'closed', 'cancelled'];
const PRIORITIES = ['p1', 'p2', 'p3', 'p4'];
const BOOL = ['true', 'false'];

export const TICKET_FIELDS: FieldDef[] = [
  { key: 'type', kind: 'enum', options: 'type', values: ['incident', 'request', 'problem', 'change', 'task'] },
  { key: 'priority', kind: 'enum', values: PRIORITIES },
  { key: 'impact', kind: 'enum', options: 'impact', values: LEVELS },
  { key: 'urgency', kind: 'enum', options: 'impact', values: LEVELS },
  { key: 'statusCategory', kind: 'enum', options: 'statusCategory', values: CATEGORIES },
  { key: 'statusCode', kind: 'status' },
  { key: 'groupId', kind: 'group' },
  { key: 'assigneeId', kind: 'person' },
  { key: 'channel', kind: 'enum', options: 'channel', values: ['portal', 'email', 'api', 'chat', 'monitoring', 'agent'] },
  { key: 'confidential', kind: 'enum', options: 'boolean', values: BOOL },
  { key: 'title', kind: 'text' },
  { key: 'requesterId', kind: 'person' },
  { key: 'serviceId', kind: 'uuid' },
  { key: 'categoryId', kind: 'uuid' },
  { key: 'catalogItemId', kind: 'uuid' },
  { key: 'organizationId', kind: 'uuid' },
];

export const EVENT_FIELDS: Record<Trigger, FieldDef[]> = {
  ticket_created: [],
  status_changed: [
    { key: 'fromStatusCategory', kind: 'enum', options: 'statusCategory', values: CATEGORIES },
    { key: 'fromStatusCode', kind: 'status' },
    { key: 'transitionTrigger', kind: 'text' },
  ],
  priority_changed: [{ key: 'fromPriority', kind: 'enum', values: PRIORITIES }],
  assignment_changed: [
    { key: 'fromGroupId', kind: 'group' },
    { key: 'fromAssigneeId', kind: 'person' },
  ],
  ticket_updated: [{ key: 'changedFields', kind: 'list' }],
  comment_added: [
    { key: 'visibility', kind: 'enum', options: 'visibility', values: ['public', 'internal'] },
    { key: 'fromRequester', kind: 'enum', options: 'boolean', values: BOOL },
  ],
  escalated: [
    { key: 'escalationKind', kind: 'enum', options: 'escalationKind', values: ['functional', 'hierarchical'] },
    { key: 'escalationLevel', kind: 'number' },
  ],
  sla_warning: [
    { key: 'slaKind', kind: 'enum', options: 'slaKind', values: ['sla', 'ola', 'uc'] },
    { key: 'slaMetric', kind: 'enum', options: 'slaMetric', values: ['response', 'resolution'] },
    { key: 'slaPercent', kind: 'number' },
  ],
  sla_breached: [
    { key: 'slaKind', kind: 'enum', options: 'slaKind', values: ['sla', 'ola', 'uc'] },
    { key: 'slaMetric', kind: 'enum', options: 'slaMetric', values: ['response', 'resolution'] },
  ],
};

export function fieldsFor(trigger: Trigger): FieldDef[] {
  return [...EVENT_FIELDS[trigger], ...TICKET_FIELDS];
}

export function fieldDef(trigger: Trigger, key: string): FieldDef | undefined {
  return fieldsFor(trigger).find((f) => f.key === key);
}

export function isEventField(trigger: Trigger, key: string): boolean {
  return EVENT_FIELDS[trigger].some((f) => f.key === key);
}

/** Операции, которые сервер принимает для поля. */
export function opsFor(field: FieldDef | undefined): Op[] {
  if (!field) return ['eq'];
  switch (field.kind) {
    case 'number':
      return ['eq', 'neq', 'gte', 'lte', 'empty', 'not_empty'];
    case 'list':
      return ['contains', 'not_contains', 'empty', 'not_empty'];
    case 'text':
      return field.key === 'title' ? ['contains', 'not_contains', 'eq', 'neq', 'empty', 'not_empty'] : ['eq', 'neq', 'in', 'not_in', 'empty', 'not_empty'];
    default:
      return ['eq', 'neq', 'in', 'not_in', 'empty', 'not_empty'];
  }
}

export const needsValue = (op: Op) => op !== 'empty' && op !== 'not_empty';
export const multiValue = (op: Op) => op === 'in' || op === 'not_in';

/** Значение условия при смене поля или операции: список для in/not_in, иначе одно значение. */
export function coerceValue(op: Op, value: unknown): unknown {
  if (!needsValue(op)) return undefined;
  if (multiValue(op)) {
    if (Array.isArray(value)) return value;
    return value === undefined || value === null || value === '' ? [] : [String(value)];
  }
  if (Array.isArray(value)) return value[0] ?? '';
  return value ?? '';
}

/** Условие заполнено настолько, что сервер его примет. */
export function conditionComplete(c: { op: Op; value?: unknown }): boolean {
  if (!needsValue(c.op)) return true;
  if (Array.isArray(c.value)) return c.value.length > 0 && c.value.every((v) => String(v).trim() !== '');
  return c.value !== undefined && c.value !== null && String(c.value).trim() !== '';
}
