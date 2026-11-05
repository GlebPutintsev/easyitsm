import type { Condition, FormField, FormValues } from './types';

/**
 * Проверка условий формы — та же логика, что Condition на сервере.
 * Сервер всё равно перепроверяет форму: эта копия нужна, чтобы поля появлялись сразу при вводе.
 */
export function testCondition(c: Condition, values: FormValues): boolean {
  if (c.all) return c.all.every((x) => testCondition(x, values));
  if (c.any) return c.any.some((x) => testCondition(x, values));
  const actual = c.field ? values[c.field] : undefined;
  switch (c.op) {
    case 'eq':
      return same(actual, c.value);
    case 'neq':
      return !same(actual, c.value);
    case 'in':
      return Array.isArray(c.value) && c.value.some((v) => same(actual, v));
    case 'notIn':
      return !(Array.isArray(c.value) && c.value.some((v) => same(actual, v)));
    case 'filled':
      return filled(actual);
    case 'empty':
      return !filled(actual);
    case 'gt':
      return compare(actual, c.value) > 0;
    case 'lt':
      return compare(actual, c.value) < 0;
    case 'contains':
      return Array.isArray(actual) && actual.some((v) => same(v, c.value));
    default:
      return false;
  }
}

export function filled(v: unknown): boolean {
  if (v === null || v === undefined) return false;
  if (typeof v === 'string') return v.trim() !== '';
  if (Array.isArray(v)) return v.length > 0;
  if (typeof v === 'boolean') return v;
  return true;
}

function same(a: unknown, b: unknown): boolean {
  const x = toNumber(a);
  const y = toNumber(b);
  if (typeof a === 'number' && typeof b === 'number') return a === b;
  if (x !== null && y !== null && (typeof a === 'number' || typeof b === 'number')) return x === y;
  if (a === null || a === undefined || b === null || b === undefined) return a === b;
  return String(a) === String(b);
}

function compare(a: unknown, b: unknown): number {
  const x = toNumber(a);
  const y = toNumber(b);
  if (x === null || y === null) {
    return typeof a === 'string' && typeof b === 'string' ? a.localeCompare(b) : 0;
  }
  return x - y;
}

function toNumber(v: unknown): number | null {
  if (typeof v === 'number') return Number.isFinite(v) ? v : null;
  if (typeof v === 'string' && v.trim() !== '') {
    const n = Number(v);
    return Number.isFinite(n) ? n : null;
  }
  return null;
}

/**
 * Видимые поля и обязательность с учётом условий. Как на сервере, условия считаются по значениям
 * видимых полей выше: скрытое поле не влияет на нижние.
 */
export function resolveFields(fields: FormField[], values: FormValues) {
  const accepted: FormValues = {};
  const out: { field: FormField; required: boolean }[] = [];
  for (const f of fields) {
    if (f.visibleWhen && !testCondition(f.visibleWhen, accepted)) continue;
    const required = !!f.required || (!!f.requiredWhen && testCondition(f.requiredWhen, accepted));
    out.push({ field: f, required });
    if (f.type !== 'section' && filled(values[f.key])) accepted[f.key] = values[f.key];
  }
  return out;
}

/** Поля, на которые ссылается условие (для подсказок конструктора). */
export function conditionFields(c: Condition | null | undefined, out: string[] = []): string[] {
  if (!c) return out;
  if (c.all) c.all.forEach((x) => conditionFields(x, out));
  else if (c.any) c.any.forEach((x) => conditionFields(x, out));
  else if (c.field) out.push(c.field);
  return out;
}
