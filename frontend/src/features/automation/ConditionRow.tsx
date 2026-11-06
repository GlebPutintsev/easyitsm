import { ActionIcon, MultiSelect, NumberInput, Select, TagsInput, TextInput } from '@mantine/core';
import { IconTrash } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { PersonPicker } from '@/shared/ui/PersonPicker';
import { type Condition, useGroups, useStatuses } from './api';
import { type FieldDef, type Op, type Trigger, EVENT_FIELDS, TICKET_FIELDS, coerceValue, fieldDef, multiValue, needsValue, opsFor } from './model';
import css from './automation.module.css';

interface Props {
  trigger: Trigger;
  condition: Condition;
  index: number;
  onChange: (c: Condition) => void;
  onRemove: () => void;
}

/** Одна строка условия: поле, операция, значение. Поля события показаны отдельной группой. */
export function ConditionRow({ trigger, condition, index, onChange, onRemove }: Props) {
  const { t } = useTranslation();
  const field = fieldDef(trigger, condition.field);
  const ops = opsFor(field);
  const label = (f: FieldDef) => t(`automation.fields.${f.key}`);
  const fieldData = [
    ...(EVENT_FIELDS[trigger].length
      ? [{ group: t('automation.triggers.' + trigger), items: EVENT_FIELDS[trigger].map((f) => ({ value: f.key, label: label(f) })) }]
      : []),
    { group: t('automation.columns.ticket'), items: TICKET_FIELDS.map((f) => ({ value: f.key, label: label(f) })) },
  ];

  const setField = (key: string | null) => {
    if (!key) return;
    const next = fieldDef(trigger, key);
    const nextOps = opsFor(next);
    const op = nextOps.includes(condition.op) ? condition.op : (nextOps[0] ?? 'eq');
    onChange({ field: key, op, value: coerceValue(op, undefined) });
  };
  const setOp = (op: string | null) => {
    if (!op) return;
    onChange({ ...condition, op: op as Op, value: coerceValue(op as Op, condition.value) });
  };

  return (
    <div className={css.row} data-testid={`condition-${index}`}>
      <Select
        label={t('automation.editor.field')}
        size="sm"
        searchable
        data={fieldData}
        value={condition.field}
        onChange={setField}
        allowDeselect={false}
      />
      <Select
        label={t('automation.editor.op')}
        size="sm"
        data={ops.map((o) => ({ value: o, label: t(`automation.ops.${o}`) }))}
        value={condition.op}
        onChange={setOp}
        allowDeselect={false}
      />
      {needsValue(condition.op) && field ? (
        <ValueInput field={field} op={condition.op} value={condition.value} onChange={(value) => onChange({ ...condition, value })} />
      ) : (
        <div />
      )}
      <ActionIcon variant="subtle" color="gray" className={css.removeCondition} aria-label={t('automation.editor.remove')} onClick={onRemove}>
        <IconTrash size={16} />
      </ActionIcon>
    </div>
  );
}

function ValueInput({ field, op, value, onChange }: { field: FieldDef; op: Op; value: unknown; onChange: (v: unknown) => void }) {
  const { t } = useTranslation();
  const groups = useGroups();
  const statuses = useStatuses();
  const multi = multiValue(op);
  const label = multi ? t('automation.editor.values') : t('automation.editor.value');
  const list = Array.isArray(value) ? value.map(String) : [];
  const single = Array.isArray(value) ? '' : value == null ? '' : String(value);

  const options = (): { value: string; label: string }[] | null => {
    switch (field.kind) {
      case 'enum':
        return (field.values ?? []).map((v) => ({
          value: v,
          label: field.options ? t(`automation.values.${field.options}.${v}`) : v.toUpperCase(),
        }));
      case 'status':
        return (statuses.data ?? []).map((s) => ({ value: s.code, label: `${s.name} · ${s.code}` }));
      case 'group':
        return (groups.data ?? []).map((g) => ({ value: g.id, label: g.name }));
      default:
        return null;
    }
  };
  const data = options();

  if (data) {
    // Значение, которого нет в справочнике (группу удалили), всё равно показывается.
    const known = new Set(data.map((d) => d.value));
    const extra = (multi ? list : single ? [single] : []).filter((v) => !known.has(v)).map((v) => ({ value: v, label: v }));
    return multi ? (
      <MultiSelect label={label} size="sm" searchable data={[...data, ...extra]} value={list} onChange={onChange} />
    ) : (
      <Select label={label} size="sm" searchable data={[...data, ...extra]} value={single || null} onChange={(v) => onChange(v ?? '')} />
    );
  }
  if (multi) {
    return <TagsInput label={label} size="sm" value={list} onChange={onChange} />;
  }
  switch (field.kind) {
    case 'person':
      return <PersonPicker label={label} value={single || null} onChange={(id) => onChange(id ?? '')} />;
    case 'number':
      return (
        <NumberInput label={label} size="sm" value={single === '' ? '' : Number(single)} onChange={(v) => onChange(v === '' ? '' : Number(v))} />
      );
    default:
      return <TextInput label={label} size="sm" value={single} maxLength={500} onChange={(e) => onChange(e.currentTarget.value)} />;
  }
}
