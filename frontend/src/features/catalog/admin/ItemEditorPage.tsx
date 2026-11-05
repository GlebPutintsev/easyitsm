import {
  Alert,
  Anchor,
  Button,
  Card,
  Checkbox,
  Code,
  Grid,
  Group,
  MultiSelect,
  NumberInput,
  Select,
  SegmentedControl,
  Skeleton,
  Stack,
  Text,
  TextInput,
  Textarea,
  Title,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconAlertCircle, IconArrowLeft } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams } from 'react-router';
import { ApiError } from '@/shared/api/client';
import { ListState } from '@/shared/ui/ListState';
import { type ItemDetail, type ItemInput, useCatalogItem, useCategories, useFlows, useSaveItem } from '../api';
import { SERVICE_ICONS, ServiceIcon } from '../icons';
import { FormBuilder, schemaProblems } from './FormBuilder';

/** Роли из раздела 3 ТЗ для ограничения видимости услуги. */
const ROLES = ['REQ', 'APR', 'AGT', 'LEAD', 'MIM', 'CHG', 'PRB', 'KNW', 'CFG', 'AST', 'ANL', 'ADM', 'SEC', 'AUD'];

const EMPTY: ItemInput = {
  categoryId: '',
  kind: 'request',
  name: '',
  summary: '',
  description: '',
  icon: null,
  team: null,
  keywords: '',
  form: { fields: [] },
  approvalFlowId: null,
  visibleRoles: [],
  allowOnBehalf: true,
  cost: null,
  currency: null,
  leadTimeHours: null,
  sortOrder: 0,
  active: true,
};

/** Редактор услуги каталога: описание, видимость, срок и цена, маршрут согласования, конструктор формы. */
export function ItemEditorPage() {
  const { id } = useParams();
  const isNew = !id || id === 'new';
  const item = useCatalogItem(isNew ? undefined : id);
  if (!isNew && item.isPending) return <Skeleton height={400} />;
  if (!isNew && item.error) return <ListState state="error" error={item.error} onRetry={() => void item.refetch()} />;
  return <Editor key={item.data?.id ?? 'new'} existing={isNew ? undefined : item.data} />;
}

function toInput(d: ItemDetail): ItemInput {
  return {
    categoryId: d.categoryId,
    kind: d.kind,
    name: d.name,
    summary: d.summary,
    description: d.description,
    icon: d.icon,
    team: d.team,
    keywords: d.keywords,
    form: d.form,
    approvalFlowId: d.approvalFlowId,
    visibleRoles: d.visibleRoles,
    allowOnBehalf: d.allowOnBehalf,
    cost: d.cost,
    currency: d.currency,
    leadTimeHours: d.leadTimeHours,
    sortOrder: d.sortOrder,
    active: d.active,
  };
}

function Editor({ existing }: { existing?: ItemDetail }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const categories = useCategories(true);
  const flows = useFlows();
  const save = useSaveItem();
  const [input, setInput] = useState<ItemInput>(existing ? toInput(existing) : EMPTY);
  const set = <K extends keyof ItemInput>(k: K, v: ItemInput[K]) => setInput((x) => ({ ...x, [k]: v }));
  const problems = schemaProblems(input.form);
  const error = save.error instanceof ApiError ? save.error : null;

  const submit = () =>
    save.mutate(
      { id: existing?.id, version: existing?.version, input },
      {
        onSuccess: (saved) => {
          notifications.show({ color: 'teal', message: t('admin.catalog.saved') });
          if (!existing) navigate(`/admin/catalog/items/${saved.id}`, { replace: true });
        },
      },
    );

  return (
    <Stack gap="md" maw={1200}>
      <Group justify="space-between">
        <Group gap="xs">
          <Anchor component={Link} to="/admin/catalog" aria-label={t('actions.back')}>
            <IconArrowLeft size={20} />
          </Anchor>
          <Title order={2}>{existing ? existing.name : t('admin.catalog.newItem')}</Title>
        </Group>
        <Group gap="xs">
          {existing && (
            <Button variant="default" component={Link} to={`/portal/catalog/${existing.id}`} target="_blank">
              {t('admin.catalog.openInPortal')}
            </Button>
          )}
          <Button onClick={submit} loading={save.isPending} disabled={!input.name.trim() || !input.categoryId || problems.length > 0}>
            {t('admin.catalog.save')}
          </Button>
        </Group>
      </Group>

      {error && (
        <Alert color="red" icon={<IconAlertCircle />} title={t(error.code === 'version_conflict' ? 'admin.catalog.conflict' : 'admin.catalog.saveFailed')}>
          <Text size="sm">{error.message}</Text>
          {error.traceId && (
            <Text size="xs" c="dimmed">
              {t('states.traceId')}: <Code>{error.traceId}</Code>
            </Text>
          )}
        </Alert>
      )}

      <Grid gap="md">
        <Grid.Col span={{ base: 12, lg: 5 }}>
          <Stack gap="md">
            <Card withBorder padding="md">
              <Stack gap="sm">
                <Title order={4}>{t('admin.catalog.sections.main')}</Title>
                <SegmentedControl
                  value={input.kind}
                  onChange={(v) => set('kind', v as ItemInput['kind'])}
                  data={[
                    { value: 'request', label: t('admin.catalog.kinds.request') },
                    { value: 'incident', label: t('admin.catalog.kinds.incident') },
                  ]}
                />
                <TextInput label={t('admin.catalog.name')} required maxLength={160} value={input.name} onChange={(e) => set('name', e.currentTarget.value)} />
                <Select
                  label={t('admin.catalog.category')}
                  required
                  searchable
                  value={input.categoryId || null}
                  data={(categories.data ?? []).map((c) => ({ value: c.id, label: c.name }))}
                  onChange={(v) => set('categoryId', v ?? '')}
                />
                <Textarea label={t('admin.catalog.summary')} description={t('admin.catalog.summaryHint')} maxLength={300} autosize value={input.summary} onChange={(e) => set('summary', e.currentTarget.value)} />
                <Textarea label={t('admin.catalog.description')} autosize minRows={3} value={input.description} onChange={(e) => set('description', e.currentTarget.value)} />
                <Group grow align="flex-end">
                  <Select
                    label={t('admin.catalog.icon')}
                    clearable
                    value={input.icon}
                    data={Object.keys(SERVICE_ICONS).map((k) => ({ value: k, label: k }))}
                    onChange={(v) => set('icon', v)}
                    leftSection={<ServiceIcon name={input.icon} kind={input.kind} size={22} />}
                  />
                  <TextInput label={t('admin.catalog.team')} value={input.team ?? ''} onChange={(e) => set('team', e.currentTarget.value || null)} />
                </Group>
                <TextInput label={t('admin.catalog.keywords')} description={t('admin.catalog.keywordsHint')} value={input.keywords} onChange={(e) => set('keywords', e.currentTarget.value)} />
              </Stack>
            </Card>

            <Card withBorder padding="md">
              <Stack gap="sm">
                <Title order={4}>{t('admin.catalog.sections.access')}</Title>
                <MultiSelect
                  label={t('admin.catalog.visibleRoles')}
                  description={t('admin.catalog.visibleRolesHint')}
                  data={ROLES}
                  value={input.visibleRoles}
                  onChange={(v) => set('visibleRoles', v)}
                  clearable
                />
                <Checkbox label={t('admin.catalog.allowOnBehalf')} checked={input.allowOnBehalf} onChange={(e) => set('allowOnBehalf', e.currentTarget.checked)} />
                <Checkbox label={t('admin.catalog.active')} checked={input.active} onChange={(e) => set('active', e.currentTarget.checked)} />
              </Stack>
            </Card>

            <Card withBorder padding="md">
              <Stack gap="sm">
                <Title order={4}>{t('admin.catalog.sections.terms')}</Title>
                <Group grow>
                  <NumberInput label={t('admin.catalog.leadTime')} min={1} max={8760} value={input.leadTimeHours ?? ''} onChange={(v) => set('leadTimeHours', v === '' ? null : Number(v))} />
                  <NumberInput label={t('admin.catalog.cost')} min={0} decimalScale={2} value={input.cost ?? ''} onChange={(v) => set('cost', v === '' ? null : Number(v))} />
                </Group>
                <Select
                  label={t('admin.catalog.flow')}
                  description={t('admin.catalog.flowHint')}
                  clearable
                  value={input.approvalFlowId}
                  data={(flows.data ?? []).map((f) => ({ value: f.id, label: f.active ? f.name : `${f.name} (${t('admin.catalog.inactive')})` }))}
                  onChange={(v) => set('approvalFlowId', v)}
                />
                <Anchor component={Link} to="/admin/catalog?tab=flows" size="sm">
                  {t('admin.catalog.manageFlows')}
                </Anchor>
              </Stack>
            </Card>
          </Stack>
        </Grid.Col>
        <Grid.Col span={{ base: 12, lg: 7 }}>
          <Card withBorder padding="md">
            <Stack gap="sm">
              <Title order={4}>{t('admin.catalog.sections.form')}</Title>
              <Text size="sm" c="dimmed">
                {t('admin.catalog.formHint')}
              </Text>
              <FormBuilder value={input.form} onChange={(form) => set('form', form)} />
            </Stack>
          </Card>
        </Grid.Col>
      </Grid>
    </Stack>
  );
}
