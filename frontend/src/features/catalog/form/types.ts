/** Схема формы предложения каталога (REQ-02). Совпадает с FormSchema на сервере. */

export type FieldType = 'text' | 'textarea' | 'number' | 'select' | 'multiselect' | 'checkbox' | 'date' | 'email' | 'section';

export const FIELD_TYPES: FieldType[] = ['text', 'textarea', 'number', 'select', 'multiselect', 'checkbox', 'date', 'email', 'section'];

export type ConditionOp = 'eq' | 'neq' | 'in' | 'notIn' | 'filled' | 'empty' | 'gt' | 'lt' | 'contains';

export const CONDITION_OPS: ConditionOp[] = ['eq', 'neq', 'in', 'notIn', 'filled', 'empty', 'gt', 'lt', 'contains'];

export interface Condition {
  field?: string;
  op?: ConditionOp;
  value?: unknown;
  all?: Condition[];
  any?: Condition[];
}

export interface FieldOption {
  value: string;
  label: string;
}

export interface FormField {
  key: string;
  type: FieldType;
  label: string;
  help?: string | null;
  placeholder?: string | null;
  required?: boolean | null;
  options?: FieldOption[] | null;
  min?: number | null;
  max?: number | null;
  pattern?: string | null;
  patternMessage?: string | null;
  visibleWhen?: Condition | null;
  requiredWhen?: Condition | null;
  defaultValue?: unknown;
}

export interface FormSchema {
  fields: FormField[];
}

export type FormValues = Record<string, unknown>;
export type FieldErrors = Record<string, string>;
