import { Button, Group, Modal, NumberInput, Select, SimpleGrid, Stack, TextInput, Textarea } from '@mantine/core';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@/shared/api/client';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import {
  CI_CRITICALITIES,
  CI_STATUSES,
  type AttributeDef,
  type Ci,
  type CiClass,
  type CiCriticality,
  type CiFields,
  type CiStatus,
  useClasses,
  useCreateCi,
  useGroups,
  useUpdateCi,
} from './api';
import { SectionLabel } from './ui';

interface Props {
  opened: boolean;
  onClose: () => void;
  /** Изменяемая КЕ; без неё форма создаёт новую */
  ci?: Ci;
  onSaved?: (ci: Ci) => void;
}

/** Создание и изменение КЕ: общие поля и атрибуты по схеме класса (CFG-01). */
export function CiForm({ opened, onClose, ci, onSaved }: Props) {
  const { t } = useTranslation();
  return (
    <Modal opened={opened} onClose={onClose} size="lg" title={t(ci ? 'cmdb.form.editTitle' : 'cmdb.form.createTitle')}>
      {opened && <Body ci={ci} onClose={onClose} onSaved={onSaved} />}
    </Modal>
  );
}

type AttrValue = string | number | boolean | '';

function Body({ ci, onClose, onSaved }: Omit<Props, 'opened'>) {
  const { t } = useTranslation();
  const classes = useClasses();
  const groups = useGroups();
  const create = useCreateCi();
  const update = useUpdateCi(ci ?? ({ id: '', version: 0 } as Ci));
  const [name, setName] = useState(ci?.name ?? '');
  const [classCode, setClassCode] = useState(ci?.classCode ?? '');
  const [status, setStatus] = useState<CiStatus>(ci?.status ?? 'active');
  const [environment, setEnvironment] = useState(ci?.environment ?? '');
  const [criticality, setCriticality] = useState<CiCriticality>(ci?.criticality ?? 'medium');
  const [ownerGroupId, setOwnerGroupId] = useState(ci?.ownerGroupId ?? '');
  const [description, setDescription] = useState(ci?.description ?? '');
  const [attrs, setAttrs] = useState<Record<string, AttrValue>>(() => {
    const out: Record<string, AttrValue> = {};
    for (const [k, v] of Object.entries(ci?.attributes ?? {})) {
      out[k] = typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean' ? v : JSON.stringify(v);
    }
    return out;
  });

  const all = classes.data ?? [];
  const cls = all.find((c) => c.code === classCode);
  const schema = cls?.attributes ?? [];
  const mutation = ci ? update : create;
  const error = mutation.error;
  const fieldError = (field: string) =>
    error instanceof ApiError && error.problem.field === field ? error.message : undefined;

  const submit = () => {
    const attributes: Record<string, unknown> = {};
    for (const def of schema) {
      const value = attrs[def.code];
      const before = ci?.attributes[def.code];
      const empty = value === '' || value === undefined;
      if (empty) {
        if (ci && before !== undefined) attributes[def.code] = null;
      } else if (!ci || before !== value) {
        attributes[def.code] = value;
      }
    }
    const fields: CiFields = ci
      ? diff(ci, { name, classCode, status, environment: environment || null, criticality, ownerGroupId: ownerGroupId || null, description })
      : {
          name,
          classCode,
          status,
          environment: environment || undefined,
          criticality,
          ownerGroupId: ownerGroupId || undefined,
          description: description || undefined,
        };
    if (Object.keys(attributes).length) fields.attributes = attributes;
    mutation.mutate(fields, {
      onSuccess: (saved) => {
        onSaved?.(saved);
        onClose();
      },
    });
  };

  return (
    <Stack gap="sm">
      <TextInput label={t('cmdb.field.name')} required value={name} onChange={(e) => setName(e.currentTarget.value)} error={fieldError('name')} data-autofocus />
      <Select
        label={t('cmdb.field.class')}
        description={t('cmdb.field.classHint')}
        required
        searchable
        value={classCode || null}
        onChange={(v) => setClassCode(v ?? '')}
        data={classOptions(all, t)}
        error={fieldError('classCode')}
      />
      <SimpleGrid cols={{ base: 1, sm: 3 }}>
        <Select
          label={t('cmdb.field.status')}
          value={status}
          onChange={(v) => v && setStatus(v as CiStatus)}
          data={CI_STATUSES.map((s) => ({ value: s, label: t(`cmdb.status.${s}`) }))}
          allowDeselect={false}
        />
        <TextInput
          label={t('cmdb.field.environment')}
          description={t('cmdb.field.environmentHint')}
          value={environment}
          onChange={(e) => setEnvironment(e.currentTarget.value)}
          error={fieldError('environment')}
        />
        <Select
          label={t('cmdb.field.criticality')}
          value={criticality}
          onChange={(v) => v && setCriticality(v as CiCriticality)}
          data={CI_CRITICALITIES.map((c) => ({ value: c, label: t(`cmdb.criticality.${c}`) }))}
          allowDeselect={false}
        />
      </SimpleGrid>
      <Select
        label={t('cmdb.field.ownerGroup')}
        searchable
        clearable
        value={ownerGroupId || null}
        onChange={(v) => setOwnerGroupId(v ?? '')}
        data={(groups.data ?? []).map((g) => ({ value: g.id, label: g.name }))}
        error={fieldError('ownerGroupId')}
      />
      <Textarea
        label={t('cmdb.field.description')}
        description={t('cmdb.field.descriptionHint')}
        autosize
        minRows={2}
        value={description}
        onChange={(e) => setDescription(e.currentTarget.value)}
        error={fieldError('description')}
      />
      {schema.length > 0 && (
        <Stack gap="xs" mt="xs">
          <SectionLabel>{t('cmdb.field.attributes')}</SectionLabel>
          <SimpleGrid cols={{ base: 1, sm: 2 }}>
            {schema.map((def) => (
              <AttributeInput
                key={def.code}
                def={def}
                value={attrs[def.code] ?? ''}
                onChange={(v) => setAttrs({ ...attrs, [def.code]: v })}
                error={fieldError(def.code) ?? fieldError(`attributes.${def.code}`)}
              />
            ))}
          </SimpleGrid>
        </Stack>
      )}
      <ErrorLine error={error && !(error instanceof ApiError && error.problem.field) ? error : null} />
      <Group justify="flex-end" mt="sm">
        <Button variant="default" onClick={onClose}>
          {t('cmdb.form.cancel')}
        </Button>
        <Button onClick={submit} loading={mutation.isPending} disabled={!name.trim() || !classCode}>
          {t(ci ? 'cmdb.form.save' : 'cmdb.form.create')}
        </Button>
      </Group>
    </Stack>
  );
}

function AttributeInput({ def, value, onChange, error }: { def: AttributeDef; value: AttrValue; onChange: (v: AttrValue) => void; error?: string }) {
  const { t } = useTranslation();
  const common = { label: def.name, required: def.required, error };
  switch (def.type) {
    case 'number':
      return <NumberInput {...common} value={value === '' ? '' : Number(value)} onChange={(v) => onChange(v === '' ? '' : Number(v))} />;
    case 'boolean':
      return (
        <Select
          {...common}
          clearable
          value={value === '' ? null : String(value)}
          onChange={(v) => onChange(v === null ? '' : v === 'true')}
          data={[
            { value: 'true', label: t('cmdb.field.yes') },
            { value: 'false', label: t('cmdb.field.no') },
          ]}
        />
      );
    case 'enum':
      return <Select {...common} clearable value={value === '' ? null : String(value)} onChange={(v) => onChange(v ?? '')} data={def.options} />;
    case 'text':
      return <Textarea {...common} autosize minRows={2} value={String(value)} onChange={(e) => onChange(e.currentTarget.value)} />;
    case 'date':
      return <TextInput {...common} type="date" value={String(value)} onChange={(e) => onChange(e.currentTarget.value)} />;
    default:
      return <TextInput {...common} value={String(value)} onChange={(e) => onChange(e.currentTarget.value)} />;
  }
}

/** Классы, в которых можно создать КЕ, сгруппированные по родителю верхнего уровня. */
function classOptions(all: CiClass[], t: (key: string) => string) {
  const byId = new Map(all.map((c) => [c.id, c]));
  const groups = new Map<string, { value: string; label: string }[]>();
  for (const c of all) {
    if (c.isAbstract || !c.active) continue;
    const group = t(`cmdb.layer.${c.layer}`);
    const parent = c.parentId ? byId.get(c.parentId) : undefined;
    const label = parent && !parent.isAbstract ? `${parent.name} › ${c.name}` : c.name;
    groups.set(group, [...(groups.get(group) ?? []), { value: c.code, label }]);
  }
  return [...groups.entries()].map(([group, items]) => ({ group, items: items.sort((a, b) => a.label.localeCompare(b.label)) }));
}

/** Merge Patch: только изменённые поля, очищенная среда или группа — null. */
function diff(ci: Ci, next: Required<Omit<CiFields, 'attributes'>>): CiFields {
  const out: CiFields = {};
  if (next.name !== ci.name) out.name = next.name;
  if (next.classCode !== ci.classCode) out.classCode = next.classCode;
  if (next.status !== ci.status) out.status = next.status;
  if ((next.environment ?? null) !== (ci.environment ?? null)) out.environment = next.environment;
  if (next.criticality !== ci.criticality) out.criticality = next.criticality;
  if ((next.ownerGroupId ?? null) !== (ci.ownerGroupId ?? null)) out.ownerGroupId = next.ownerGroupId;
  if (next.description !== ci.description) out.description = next.description;
  return out;
}
