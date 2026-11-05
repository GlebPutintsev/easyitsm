import { ActionIcon, Button, Group, Select, Stack, Switch, TagsInput, Text, TextInput } from '@mantine/core';
import { IconPlus, IconTrash } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { CONDITION_OPS, type Condition, type ConditionOp, type FormField } from '../form/types';

interface Row {
  field: string;
  op: ConditionOp;
  value: unknown;
}

/** Плоское представление условия: одна строка или несколько через «все» / «любое». */
function toRows(c: Condition | null | undefined): { mode: 'all' | 'any'; rows: Row[] } {
  if (!c) return { mode: 'all', rows: [] };
  const list = c.all ?? c.any;
  const mode = c.any ? 'any' : 'all';
  const leaves = list ?? [c];
  return {
    mode,
    rows: leaves.filter((x) => x.field).map((x) => ({ field: x.field!, op: x.op ?? 'eq', value: x.value })),
  };
}

function fromRows(mode: 'all' | 'any', rows: Row[]): Condition | null {
  const leaves: Condition[] = rows.map((r) => ({
    field: r.field,
    op: r.op,
    ...(r.op === 'filled' || r.op === 'empty' ? {} : { value: r.value }),
  }));
  if (!leaves.length) return null;
  if (leaves.length === 1) return leaves[0] ?? null;
  return mode === 'any' ? { any: leaves } : { all: leaves };
}

interface Props {
  label: string;
  value: Condition | null | undefined;
  onChange: (c: Condition | null) => void;
  /** Поля, на которые можно ссылаться. Пусто — код поля вводится вручную (маршруты согласования). */
  fields: FormField[];
}

/**
 * Редактор условия показа, обязательности или включения шага (REQ-02, REQ-03).
 * Вложенные группы условий задаются через API; в редакторе — один уровень, этого хватает для форм каталога.
 */
export function ConditionEditor({ label, value, onChange, fields }: Props) {
  const { t } = useTranslation();
  const { mode, rows } = toRows(value);
  const enabled = rows.length > 0;
  const fieldOf = (key: string) => fields.find((f) => f.key === key);
  const update = (next: Row[], m = mode) => onChange(fromRows(m, next));
  const firstField = fields[0]?.key ?? '';

  return (
    <Stack gap="xs">
      <Switch
        label={label}
        checked={enabled}
        onChange={(e) => update(e.currentTarget.checked ? [{ field: firstField, op: 'eq', value: '' }] : [])}
      />
      {enabled && (
        <Stack gap="xs" pl="md" style={{ borderLeft: '2px solid var(--mantine-color-default-border)' }}>
          {rows.length > 1 && (
            <Select
              size="xs"
              w={220}
              aria-label={t('admin.catalog.condition.mode')}
              value={mode}
              onChange={(m) => update(rows, (m as 'all' | 'any') ?? 'all')}
              data={[
                { value: 'all', label: t('admin.catalog.condition.all') },
                { value: 'any', label: t('admin.catalog.condition.any') },
              ]}
            />
          )}
          {rows.map((r, i) => {
            const f = fieldOf(r.field);
            const set = (patch: Partial<Row>) => update(rows.map((x, j) => (j === i ? { ...x, ...patch } : x)));
            const noValue = r.op === 'filled' || r.op === 'empty';
            const multi = r.op === 'in' || r.op === 'notIn';
            const options = f?.options?.map((o) => ({ value: o.value, label: o.label }));
            return (
              <Group key={i} gap="xs" wrap="wrap" align="flex-end">
                {fields.length ? (
                  <Select
                    size="xs"
                    w={180}
                    aria-label={t('admin.catalog.condition.field')}
                    value={r.field}
                    data={fields.filter((x) => x.type !== 'section').map((x) => ({ value: x.key, label: x.label || x.key }))}
                    onChange={(v) => set({ field: v ?? '', value: '' })}
                  />
                ) : (
                  <TextInput
                    size="xs"
                    w={180}
                    aria-label={t('admin.catalog.condition.field')}
                    placeholder={t('admin.catalog.condition.fieldCode')}
                    value={r.field}
                    onChange={(e) => set({ field: e.currentTarget.value })}
                  />
                )}
                <Select
                  size="xs"
                  w={170}
                  aria-label={t('admin.catalog.condition.op')}
                  value={r.op}
                  data={CONDITION_OPS.map((op) => ({ value: op, label: t(`admin.catalog.ops.${op}`) }))}
                  onChange={(v) => set({ op: (v as ConditionOp) ?? 'eq', value: v === 'in' || v === 'notIn' ? [] : '' })}
                />
                {!noValue &&
                  (multi ? (
                    <TagsInput
                      size="xs"
                      w={240}
                      aria-label={t('admin.catalog.condition.value')}
                      data={options?.map((o) => o.value)}
                      value={Array.isArray(r.value) ? (r.value as string[]) : []}
                      onChange={(v) => set({ value: v })}
                    />
                  ) : options && f?.type !== 'multiselect' ? (
                    <Select size="xs" w={200} aria-label={t('admin.catalog.condition.value')} data={options} value={(r.value as string) ?? null} onChange={(v) => set({ value: v ?? '' })} />
                  ) : f?.type === 'checkbox' ? (
                    <Select
                      size="xs"
                      w={120}
                      aria-label={t('admin.catalog.condition.value')}
                      data={[
                        { value: 'true', label: t('admin.catalog.yes') },
                        { value: 'false', label: t('admin.catalog.no') },
                      ]}
                      value={String(r.value === true)}
                      onChange={(v) => set({ value: v === 'true' })}
                    />
                  ) : (
                    <TextInput
                      size="xs"
                      w={200}
                      aria-label={t('admin.catalog.condition.value')}
                      value={String(r.value ?? '')}
                      onChange={(e) => {
                        const raw = e.currentTarget.value;
                        set({ value: f?.type === 'number' && raw.trim() !== '' && !Number.isNaN(Number(raw)) ? Number(raw) : raw });
                      }}
                    />
                  ))}
                <ActionIcon variant="subtle" color="red" aria-label={t('admin.catalog.remove')} onClick={() => update(rows.filter((_, j) => j !== i))}>
                  <IconTrash size={16} />
                </ActionIcon>
              </Group>
            );
          })}
          <Group>
            <Button size="xs" variant="subtle" leftSection={<IconPlus size={14} />} onClick={() => update([...rows, { field: firstField, op: 'eq', value: '' }])}>
              {t('admin.catalog.condition.add')}
            </Button>
          </Group>
          {!fields.length && rows.length === 0 && (
            <Text size="xs" c="dimmed">
              {t('admin.catalog.condition.noFields')}
            </Text>
          )}
        </Stack>
      )}
    </Stack>
  );
}
