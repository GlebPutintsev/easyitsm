import { filled, resolveFields } from './conditions';
import type { FieldErrors, FormSchema, FormValues } from './types';

const EMAIL = /^[^@\s]{1,64}@[^@\s]{1,255}\.[^@\s]{2,}$/;

/**
 * Проверка ответов в браузере теми же правилами, что на сервере. Коды ошибок совпадают с серверными
 * (required, min, max, pattern, email, option, type), поэтому тексты ошибок одни и те же.
 * Возвращает значения только видимых полей.
 */
export function validateForm(schema: FormSchema, values: FormValues): { values: FormValues; errors: FieldErrors } {
  const errors: FieldErrors = {};
  const clean: FormValues = {};
  for (const { field: f, required } of resolveFields(schema.fields, values)) {
    if (f.type === 'section') continue;
    const v = typeof values[f.key] === 'string' ? (values[f.key] as string).trim() : values[f.key];
    if (!filled(v)) {
      if (required) errors[f.key] = 'required';
      continue;
    }
    const error = check(f, v);
    if (error) errors[f.key] = error;
    else clean[f.key] = f.type === 'number' ? Number(v) : v;
  }
  return { values: clean, errors };
}

function check(f: FormSchema['fields'][number], v: unknown): string | null {
  switch (f.type) {
    case 'text':
    case 'textarea':
    case 'email': {
      const s = String(v);
      const hardMax = f.type === 'textarea' ? 10_000 : 1_000;
      if (s.length > hardMax || (f.max != null && s.length > f.max)) return 'max';
      if (f.min != null && s.length < f.min) return 'min';
      if (f.type === 'email' && !EMAIL.test(s)) return 'email';
      if (f.pattern) {
        try {
          if (!new RegExp(`^(?:${f.pattern})$`).test(s)) return 'pattern';
        } catch {
          return null; // синтаксис регулярных выражений Java и JS немного различается — решит сервер
        }
      }
      return null;
    }
    case 'number': {
      const n = Number(v);
      if (!Number.isFinite(n)) return 'type';
      if (f.min != null && n < f.min) return 'min';
      if (f.max != null && n > f.max) return 'max';
      return null;
    }
    case 'select':
      return f.options?.some((o) => o.value === v) ? null : 'option';
    case 'multiselect': {
      if (!Array.isArray(v)) return 'type';
      if (!v.every((x) => f.options?.some((o) => o.value === x))) return 'option';
      if (f.min != null && v.length < f.min) return 'min';
      if (f.max != null && v.length > f.max) return 'max';
      return null;
    }
    case 'checkbox':
      return typeof v === 'boolean' ? null : 'type';
    case 'date':
      return /^\d{4}-\d{2}-\d{2}$/.test(String(v)) ? null : 'type';
    default:
      return null;
  }
}

/** Начальные значения формы: значения по умолчанию из схемы. */
export function initialValues(schema: FormSchema): FormValues {
  const out: FormValues = {};
  for (const f of schema.fields) {
    if (f.defaultValue !== undefined && f.defaultValue !== null) out[f.key] = f.defaultValue;
  }
  return out;
}
