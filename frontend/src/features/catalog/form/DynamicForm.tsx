import { Checkbox, MultiSelect, NumberInput, Select, Stack, Text, TextInput, Textarea, Title } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { resolveFields } from './conditions';
import type { FieldErrors, FormField, FormSchema, FormValues } from './types';

interface Props {
  schema: FormSchema;
  values: FormValues;
  errors: FieldErrors;
  onChange: (key: string, value: unknown) => void;
  disabled?: boolean;
}

/**
 * Поля услуги по схеме конструктора (10.5 «Поля услуги»). Поля появляются и становятся обязательными
 * по условиям сразу при вводе. Ошибка показывается у поля (UI-06).
 */
export function DynamicForm({ schema, values, errors, onChange, disabled }: Props) {
  const fields = resolveFields(schema.fields, values);
  return (
    <Stack gap="md">
      {fields.map(({ field, required }) => (
        <FieldInput
          key={field.key}
          field={field}
          required={required}
          value={values[field.key]}
          error={errors[field.key]}
          disabled={disabled}
          onChange={(v) => onChange(field.key, v)}
        />
      ))}
    </Stack>
  );
}

interface FieldProps {
  field: FormField;
  required: boolean;
  value: unknown;
  error?: string;
  disabled?: boolean;
  onChange: (value: unknown) => void;
}

export function FieldInput({ field: f, required, value, error, disabled, onChange }: FieldProps) {
  const { t } = useTranslation();
  const common = {
    label: f.label,
    description: f.help || undefined,
    placeholder: f.placeholder || undefined,
    required,
    disabled,
    error: error ? fieldError(t, f, error) : undefined,
  };
  const options = (f.options ?? []).map((o) => ({ value: o.value, label: o.label }));

  switch (f.type) {
    case 'section':
      return (
        <Stack gap={2} pt="xs">
          <Title order={4}>{f.label}</Title>
          {f.help && (
            <Text size="sm" c="dimmed">
              {f.help}
            </Text>
          )}
        </Stack>
      );
    case 'textarea':
      return (
        <Textarea
          {...common}
          autosize
          minRows={3}
          maxRows={12}
          value={(value as string) ?? ''}
          onChange={(e) => onChange(e.currentTarget.value)}
        />
      );
    case 'number':
      return (
        <NumberInput
          {...common}
          min={f.min ?? undefined}
          max={f.max ?? undefined}
          value={value === undefined || value === null ? '' : (value as number | string)}
          onChange={(v) => onChange(v === '' ? undefined : v)}
        />
      );
    case 'select':
      return (
        <Select
          {...common}
          data={options}
          searchable={options.length > 7}
          clearable={!required}
          value={(value as string) ?? null}
          onChange={(v) => onChange(v ?? undefined)}
        />
      );
    case 'multiselect':
      return (
        <MultiSelect
          {...common}
          data={options}
          searchable={options.length > 7}
          value={(value as string[]) ?? []}
          onChange={(v) => onChange(v)}
        />
      );
    case 'checkbox':
      return (
        <Checkbox
          label={f.label}
          description={common.description}
          disabled={disabled}
          error={common.error}
          required={required}
          checked={value === true}
          onChange={(e) => onChange(e.currentTarget.checked)}
        />
      );
    case 'date':
      return (
        <TextInput {...common} type="date" value={(value as string) ?? ''} onChange={(e) => onChange(e.currentTarget.value)} />
      );
    case 'email':
      return (
        <TextInput {...common} type="email" value={(value as string) ?? ''} onChange={(e) => onChange(e.currentTarget.value)} />
      );
    default:
      return <TextInput {...common} value={(value as string) ?? ''} onChange={(e) => onChange(e.currentTarget.value)} />;
  }
}

type TFn = (key: string, options?: Record<string, unknown>) => string;

export function fieldError(t: TFn, f: FormField, code: string): string {
  if (code === 'pattern' && f.patternMessage) return f.patternMessage;
  const isNumber = f.type === 'number';
  const isMulti = f.type === 'multiselect';
  if (code === 'min') {
    return t(isNumber ? 'catalog.form.errors.minValue' : isMulti ? 'catalog.form.errors.minItems' : 'catalog.form.errors.minLength', { n: f.min });
  }
  if (code === 'max') {
    return t(isNumber ? 'catalog.form.errors.maxValue' : isMulti ? 'catalog.form.errors.maxItems' : 'catalog.form.errors.maxLength', { n: f.max });
  }
  return t(`catalog.form.errors.${code}`, { defaultValue: t('catalog.form.errors.invalid') });
}
