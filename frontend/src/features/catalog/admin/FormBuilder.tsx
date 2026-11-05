import {
  ActionIcon,
  Badge,
  Button,
  Card,
  Checkbox,
  Grid,
  Group,
  NumberInput,
  Paper,
  SegmentedControl,
  Select,
  Stack,
  Text,
  TextInput,
  Textarea,
  Title,
  Tooltip,
} from '@mantine/core';
import { IconArrowDown, IconArrowUp, IconCopy, IconPlus, IconTrash } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { DynamicForm } from '../form/DynamicForm';
import { conditionFields } from '../form/conditions';
import { validateForm } from '../form/validate';
import { FIELD_TYPES, type FieldErrors, type FieldType, type FormField, type FormSchema, type FormValues } from '../form/types';
import { ConditionEditor } from './ConditionEditor';

const KEY_RE = /^[a-z][a-z0-9_]{0,39}$/;

/** Проблемы схемы, которые видно до сохранения. Сервер проверяет то же самое и даже больше. */
export function schemaProblems(schema: FormSchema): string[] {
  const out: string[] = [];
  const seen = new Set<string>();
  schema.fields.forEach((f, i) => {
    const at = `#${i + 1}`;
    if (!KEY_RE.test(f.key)) out.push(`key:${at}`);
    if (seen.has(f.key)) out.push(`duplicate:${f.key}`);
    if (!f.label.trim()) out.push(`label:${at}`);
    if ((f.type === 'select' || f.type === 'multiselect') && !(f.options ?? []).length) out.push(`options:${f.key}`);
    for (const ref of [...conditionFields(f.visibleWhen), ...conditionFields(f.requiredWhen)]) {
      if (!seen.has(ref)) out.push(`condition:${f.key}`);
    }
    seen.add(f.key);
  });
  return out;
}

function newField(type: FieldType, existing: FormField[]): FormField {
  let n = existing.length + 1;
  while (existing.some((f) => f.key === `field_${n}`)) n++;
  const base: FormField = { key: `field_${n}`, type, label: '', required: false };
  if (type === 'select' || type === 'multiselect') base.options = [{ value: 'option_1', label: '' }];
  return base;
}

/**
 * Конструктор формы предложения (REQ-02): поля, порядок, проверки, условия показа и обязательности,
 * предпросмотр с той же логикой, что увидит заявитель.
 */
export function FormBuilder({ value, onChange }: { value: FormSchema; onChange: (s: FormSchema) => void }) {
  const { t } = useTranslation();
  const [selected, setSelected] = useState<number | null>(value.fields.length ? 0 : null);
  const [mode, setMode] = useState<'edit' | 'preview'>('edit');
  const fields = value.fields;
  const set = (next: FormField[]) => onChange({ fields: next });
  const move = (i: number, d: -1 | 1) => {
    const j = i + d;
    if (j < 0 || j >= fields.length) return;
    const next = [...fields];
    const [moved] = next.splice(i, 1);
    if (moved) next.splice(j, 0, moved);
    set(next);
    setSelected(j);
  };
  const add = (type: FieldType) => {
    set([...fields, newField(type, fields)]);
    setSelected(fields.length);
  };
  const problems = schemaProblems(value);

  return (
    <Stack gap="sm">
      <Group justify="space-between">
        <SegmentedControl
          value={mode}
          onChange={(v) => setMode(v as 'edit' | 'preview')}
          data={[
            { value: 'edit', label: t('admin.catalog.builder.edit') },
            { value: 'preview', label: t('admin.catalog.builder.preview') },
          ]}
        />
        {problems.length > 0 && (
          <Badge color="orange" variant="light">
            {t('admin.catalog.builder.problems', { count: problems.length })}
          </Badge>
        )}
      </Group>

      {mode === 'preview' ? (
        <Preview schema={value} />
      ) : (
        <Grid gap="md">
          <Grid.Col span={{ base: 12, md: 5 }}>
            <Stack gap={6}>
              {fields.map((f, i) => (
                <Paper
                  key={i}
                  withBorder
                  p="xs"
                  radius="sm"
                  role="button"
                  tabIndex={0}
                  aria-pressed={selected === i}
                  onClick={() => setSelected(i)}
                  onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && setSelected(i)}
                  style={{ cursor: 'pointer', borderColor: selected === i ? 'var(--mantine-primary-color-filled)' : undefined }}
                >
                  <Group justify="space-between" wrap="nowrap" gap={4}>
                    <Stack gap={0} style={{ minWidth: 0 }}>
                      <Text size="sm" fw={500} truncate>
                        {f.label || t('admin.catalog.builder.untitled')}
                        {f.required ? ' *' : ''}
                      </Text>
                      <Text size="xs" c="dimmed" ff="monospace">
                        {f.key} · {t(`admin.catalog.types.${f.type}`)}
                        {f.visibleWhen ? ` · ${t('admin.catalog.builder.conditional')}` : ''}
                      </Text>
                    </Stack>
                    <Group gap={0} wrap="nowrap">
                      <ActionIcon size="sm" variant="subtle" aria-label={t('admin.catalog.up')} onClick={(e) => { e.stopPropagation(); move(i, -1); }} disabled={i === 0}>
                        <IconArrowUp size={14} />
                      </ActionIcon>
                      <ActionIcon size="sm" variant="subtle" aria-label={t('admin.catalog.down')} onClick={(e) => { e.stopPropagation(); move(i, 1); }} disabled={i === fields.length - 1}>
                        <IconArrowDown size={14} />
                      </ActionIcon>
                      <ActionIcon
                        size="sm"
                        variant="subtle"
                        aria-label={t('admin.catalog.duplicate')}
                        onClick={(e) => {
                          e.stopPropagation();
                          const copy = { ...f, key: newField(f.type, fields).key };
                          set([...fields.slice(0, i + 1), copy, ...fields.slice(i + 1)]);
                          setSelected(i + 1);
                        }}
                      >
                        <IconCopy size={14} />
                      </ActionIcon>
                      <ActionIcon
                        size="sm"
                        variant="subtle"
                        color="red"
                        aria-label={t('admin.catalog.remove')}
                        onClick={(e) => {
                          e.stopPropagation();
                          set(fields.filter((_, j) => j !== i));
                          setSelected(null);
                        }}
                      >
                        <IconTrash size={14} />
                      </ActionIcon>
                    </Group>
                  </Group>
                </Paper>
              ))}
              {!fields.length && (
                <Text size="sm" c="dimmed">
                  {t('admin.catalog.builder.empty')}
                </Text>
              )}
              <Select
                placeholder={t('admin.catalog.builder.add')}
                aria-label={t('admin.catalog.builder.add')}
                leftSection={<IconPlus size={16} />}
                value={null}
                data={FIELD_TYPES.map((type) => ({ value: type, label: t(`admin.catalog.types.${type}`) }))}
                onChange={(v) => v && add(v as FieldType)}
              />
            </Stack>
          </Grid.Col>
          <Grid.Col span={{ base: 12, md: 7 }}>
            {selected !== null && fields[selected] ? (
              <FieldEditor
                key={selected}
                field={fields[selected]}
                above={fields.slice(0, selected)}
                onChange={(f) => set(fields.map((x, j) => (j === selected ? f : x)))}
              />
            ) : (
              <Text size="sm" c="dimmed">
                {t('admin.catalog.builder.select')}
              </Text>
            )}
          </Grid.Col>
        </Grid>
      )}
    </Stack>
  );
}

function FieldEditor({ field: f, above, onChange }: { field: FormField; above: FormField[]; onChange: (f: FormField) => void }) {
  const { t } = useTranslation();
  const set = (patch: Partial<FormField>) => onChange({ ...f, ...patch });
  const hasOptions = f.type === 'select' || f.type === 'multiselect';
  const textual = f.type === 'text' || f.type === 'textarea' || f.type === 'email';
  const ranged = textual || f.type === 'number' || f.type === 'multiselect';
  const keyInvalid = !KEY_RE.test(f.key);

  return (
    <Card withBorder padding="md">
      <Stack gap="sm">
        <Title order={5}>{t('admin.catalog.builder.field')}</Title>
        <Group grow align="flex-start">
          <TextInput label={t('admin.catalog.builder.label')} required value={f.label} onChange={(e) => set({ label: e.currentTarget.value })} />
          <Select
            label={t('admin.catalog.builder.type')}
            value={f.type}
            data={FIELD_TYPES.map((type) => ({ value: type, label: t(`admin.catalog.types.${type}`) }))}
            onChange={(v) => {
              const type = (v as FieldType) ?? 'text';
              const opts = type === 'select' || type === 'multiselect' ? f.options?.length ? f.options : [{ value: 'option_1', label: '' }] : null;
              set({ type, options: opts });
            }}
          />
        </Group>
        <TextInput
          label={t('admin.catalog.builder.key')}
          description={t('admin.catalog.builder.keyHint')}
          ff="monospace"
          value={f.key}
          error={keyInvalid ? t('admin.catalog.builder.keyInvalid') : undefined}
          onChange={(e) => set({ key: e.currentTarget.value.toLowerCase() })}
        />
        {f.type !== 'section' && (
          <>
            <TextInput label={t('admin.catalog.builder.help')} value={f.help ?? ''} onChange={(e) => set({ help: e.currentTarget.value || null })} />
            {f.type !== 'checkbox' && (
              <TextInput label={t('admin.catalog.builder.placeholder')} value={f.placeholder ?? ''} onChange={(e) => set({ placeholder: e.currentTarget.value || null })} />
            )}
            <Checkbox label={t('admin.catalog.builder.required')} checked={!!f.required} onChange={(e) => set({ required: e.currentTarget.checked })} />
          </>
        )}
        {f.type === 'section' && (
          <Textarea label={t('admin.catalog.builder.sectionText')} autosize value={f.help ?? ''} onChange={(e) => set({ help: e.currentTarget.value || null })} />
        )}

        {hasOptions && <OptionsEditor options={f.options ?? []} onChange={(options) => set({ options })} />}

        {ranged && (
          <Group grow>
            <NumberInput
              label={t(f.type === 'number' ? 'admin.catalog.builder.minValue' : f.type === 'multiselect' ? 'admin.catalog.builder.minItems' : 'admin.catalog.builder.minLength')}
              value={f.min ?? ''}
              onChange={(v) => set({ min: v === '' ? null : Number(v) })}
            />
            <NumberInput
              label={t(f.type === 'number' ? 'admin.catalog.builder.maxValue' : f.type === 'multiselect' ? 'admin.catalog.builder.maxItems' : 'admin.catalog.builder.maxLength')}
              value={f.max ?? ''}
              onChange={(v) => set({ max: v === '' ? null : Number(v) })}
            />
          </Group>
        )}
        {f.type === 'text' && (
          <Group grow align="flex-start">
            <TextInput
              label={t('admin.catalog.builder.pattern')}
              description={t('admin.catalog.builder.patternHint')}
              ff="monospace"
              value={f.pattern ?? ''}
              onChange={(e) => set({ pattern: e.currentTarget.value || null })}
            />
            <TextInput label={t('admin.catalog.builder.patternMessage')} value={f.patternMessage ?? ''} onChange={(e) => set({ patternMessage: e.currentTarget.value || null })} />
          </Group>
        )}

        <ConditionEditor
          label={t('admin.catalog.builder.visibleWhen')}
          fields={above}
          value={f.visibleWhen}
          onChange={(c) => set({ visibleWhen: c })}
        />
        {f.type !== 'section' && (
          <ConditionEditor
            label={t('admin.catalog.builder.requiredWhen')}
            fields={above}
            value={f.requiredWhen}
            onChange={(c) => set({ requiredWhen: c })}
          />
        )}
        {!above.length && (
          <Text size="xs" c="dimmed">
            {t('admin.catalog.builder.conditionsHint')}
          </Text>
        )}
      </Stack>
    </Card>
  );
}

function OptionsEditor({ options, onChange }: { options: { value: string; label: string }[]; onChange: (o: { value: string; label: string }[]) => void }) {
  const { t } = useTranslation();
  return (
    <Stack gap={6}>
      <Text size="sm" fw={500}>
        {t('admin.catalog.builder.options')}
      </Text>
      {options.map((o, i) => (
        <Group key={i} gap="xs" wrap="nowrap">
          <TextInput
            size="xs"
            style={{ flex: 2 }}
            placeholder={t('admin.catalog.builder.optionLabel')}
            aria-label={t('admin.catalog.builder.optionLabel')}
            value={o.label}
            onChange={(e) => onChange(options.map((x, j) => (j === i ? { ...x, label: e.currentTarget.value } : x)))}
          />
          <TextInput
            size="xs"
            style={{ flex: 1 }}
            ff="monospace"
            placeholder={t('admin.catalog.builder.optionValue')}
            aria-label={t('admin.catalog.builder.optionValue')}
            value={o.value}
            onChange={(e) => onChange(options.map((x, j) => (j === i ? { ...x, value: e.currentTarget.value } : x)))}
          />
          <Tooltip label={t('admin.catalog.remove')}>
            <ActionIcon variant="subtle" color="red" aria-label={t('admin.catalog.remove')} onClick={() => onChange(options.filter((_, j) => j !== i))}>
              <IconTrash size={14} />
            </ActionIcon>
          </Tooltip>
        </Group>
      ))}
      <Group>
        <Button
          size="xs"
          variant="subtle"
          leftSection={<IconPlus size={14} />}
          onClick={() => onChange([...options, { value: `option_${options.length + 1}`, label: '' }])}
        >
          {t('admin.catalog.builder.addOption')}
        </Button>
      </Group>
    </Stack>
  );
}

function Preview({ schema }: { schema: FormSchema }) {
  const { t } = useTranslation();
  const [values, setValues] = useState<FormValues>({});
  const [errors, setErrors] = useState<FieldErrors>({});
  return (
    <Card withBorder padding="md">
      <Stack gap="md">
        <DynamicForm schema={schema} values={values} errors={errors} onChange={(k, v) => setValues((x) => ({ ...x, [k]: v }))} />
        {!schema.fields.length && (
          <Text size="sm" c="dimmed">
            {t('admin.catalog.builder.empty')}
          </Text>
        )}
        <Group>
          <Button variant="light" onClick={() => setErrors(validateForm(schema, values).errors)}>
            {t('admin.catalog.builder.check')}
          </Button>
          <Button variant="subtle" onClick={() => { setValues({}); setErrors({}); }}>
            {t('admin.catalog.builder.reset')}
          </Button>
        </Group>
      </Stack>
    </Card>
  );
}
