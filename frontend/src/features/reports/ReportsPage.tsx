import { Alert, Group, Select, SimpleGrid, Stack, Tabs, Text, TextInput, Title } from '@mantine/core';
import { IconInfoCircle } from '@tabler/icons-react';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate, useParams, useSearchParams } from 'react-router';
import { ListState } from '@/shared/ui/ListState';
import { ApiError } from '@/shared/api/client';
import { reportsApi, useDashboard, useReportDirectory, type ReportFilters, type Widget } from './api';
import { DrillDrawer, type DrillTarget } from './DrillDrawer';
import { ExportMenu } from './ExportMenu';
import { presetOf, presetRange, type PeriodPreset } from './format';
import { WidgetCard } from './widgets';
import './reports.css';

export const DASHBOARDS = ['overview', 'queue', 'sla', 'groups', 'services', 'satisfaction'] as const;
const PRESETS: PeriodPreset[] = ['last7', 'last30', 'last90', 'thisMonth', 'prevMonth', 'last365'];
const TYPES = ['incident', 'request', 'problem', 'change', 'task'];
const PRIORITIES = ['P1', 'P2', 'P3', 'P4'];

/**
 * Отчёты (10.15, RPT-01): готовые дашборды с периодом и фильтрами на все виджеты, провал в заявки и выгрузка.
 * Состояние фильтров — в адресе страницы, чтобы ссылкой на отчёт можно было поделиться.
 */
export function ReportsPage() {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const { dashboard: param } = useParams();
  const dashboard = DASHBOARDS.includes(param as (typeof DASHBOARDS)[number]) ? param! : 'overview';
  const [search, setSearch] = useSearchParams();
  const [drill, setDrill] = useState<DrillTarget | null>(null);
  const directory = useReportDirectory();

  const filters = useMemo<ReportFilters>(() => {
    const def = presetRange('last30');
    const f: ReportFilters = { from: search.get('from') ?? def.from, to: search.get('to') ?? def.to };
    for (const key of ['groupId', 'type', 'priority', 'serviceId'] as const) {
      const v = search.get(key);
      if (v) f[key] = v;
    }
    return f;
  }, [search]);

  const update = (patch: Partial<ReportFilters>) => {
    const next = new URLSearchParams(search);
    for (const [k, v] of Object.entries(patch)) {
      if (v) next.set(k, v);
      else next.delete(k);
    }
    setSearch(next, { replace: true });
  };

  const preset = presetOf(filters.from, filters.to);
  const [custom, setCustom] = useState(preset === 'custom');
  const query = useDashboard(dashboard, filters, i18n.language);
  const data = query.data;
  const groups = directory.data?.groups ?? [];
  const services = directory.data?.services ?? [];
  const none = t('reports.filters.none');

  return (
    <Stack gap="md" className="ei-reports">
      <Group justify="space-between" align="flex-end" wrap="wrap" gap="sm">
        <div>
          <Title order={2}>{t('reports.title')}</Title>
          {data && (
            <Text size="sm" c="var(--ei-text-3)">
              {data.allTickets ? t('reports.scope.all') : t('reports.scope.own')}
            </Text>
          )}
        </div>
        <ExportMenu
          disabled={!data}
          onExport={(format) => reportsApi.exportDashboard(dashboard, filters, i18n.language, format)}
        />
      </Group>

      <Tabs
        value={dashboard}
        onChange={(v) => v && navigate({ pathname: `/agent/reports/${v}`, search: search.toString() })}
        keepMounted={false}
      >
        <Tabs.List>
          {DASHBOARDS.map((id) => (
            <Tabs.Tab key={id} value={id}>
              {t(`reports.dashboards.${id}`)}
            </Tabs.Tab>
          ))}
        </Tabs.List>
      </Tabs>

      <Group gap="sm" align="flex-end" wrap="wrap" className="ei-report-filters" role="search" aria-label={t('reports.filters.label')}>
        <Select
          label={t('reports.filters.period')}
          size="sm"
          w={190}
          allowDeselect={false}
          value={custom ? 'custom' : preset}
          data={[...PRESETS, 'custom'].map((p) => ({ value: p, label: t(`reports.period.${p}`) }))}
          onChange={(v) => {
            if (v === 'custom') {
              setCustom(true);
            } else if (v) {
              setCustom(false);
              update(presetRange(v as PeriodPreset));
            }
          }}
        />
        {(custom || preset === 'custom') && (
          <>
            <TextInput
              type="date"
              size="sm"
              label={t('reports.filters.from')}
              value={filters.from}
              max={filters.to}
              onChange={(e) => e.currentTarget.value && update({ from: e.currentTarget.value })}
            />
            <TextInput
              type="date"
              size="sm"
              label={t('reports.filters.to')}
              value={filters.to}
              min={filters.from}
              onChange={(e) => e.currentTarget.value && update({ to: e.currentTarget.value })}
            />
          </>
        )}
        <Select
          label={t('reports.filters.group')}
          size="sm"
          w={200}
          clearable
          searchable
          placeholder={t('reports.filters.all')}
          value={filters.groupId ?? null}
          data={[{ value: 'none', label: none }, ...groups.map((g) => ({ value: g.id, label: g.name }))]}
          onChange={(v) => update({ groupId: v ?? undefined })}
        />
        <Select
          label={t('reports.filters.service')}
          size="sm"
          w={200}
          clearable
          searchable
          placeholder={t('reports.filters.all')}
          value={filters.serviceId ?? null}
          data={[{ value: 'none', label: none }, ...services.map((s) => ({ value: s.id, label: s.name }))]}
          onChange={(v) => update({ serviceId: v ?? undefined })}
        />
        <Select
          label={t('reports.filters.type')}
          size="sm"
          w={150}
          clearable
          placeholder={t('reports.filters.all')}
          value={filters.type ?? null}
          data={TYPES.map((v) => ({ value: v, label: t(`workspace.type.${v}`) }))}
          onChange={(v) => update({ type: v ?? undefined })}
        />
        <Select
          label={t('reports.filters.priority')}
          size="sm"
          w={150}
          clearable
          placeholder={t('reports.filters.all')}
          value={filters.priority ?? null}
          data={PRIORITIES.map((v) => ({ value: v, label: `${v} ${t(`ticket.priority.${v}`)}` }))}
          onChange={(v) => update({ priority: v ?? undefined })}
        />
      </Group>

      {dashboard === 'queue' && (
        <Text size="xs" c="var(--ei-text-3)">
          {t('reports.queueNote')}
        </Text>
      )}

      {query.isPending ? (
        <ListState state="loading" rows={8} />
      ) : query.isError ? (
        <ListState
          state={query.error instanceof ApiError && query.error.status === 403 ? 'forbidden' : 'error'}
          error={query.error}
          onRetry={() => query.refetch()}
        />
      ) : (
        <DashboardGrid widgets={data!.widgets} onDrill={data!.canDrillDown ? (params, label) => setDrill({ params, label }) : undefined} />
      )}

      {data && !data.canDrillDown && (
        <Alert variant="light" color="gray" icon={<IconInfoCircle size={16} />}>
          {t('reports.drill.unavailable')}
        </Alert>
      )}

      <DrillDrawer target={drill} filters={filters} onClose={() => setDrill(null)} />
    </Stack>
  );
}

/** Во всю ширину показатели, графики по времени и таблицы; столбчатые — по два в ряд (непарный — во всю ширину). */
export function spans(widgets: Widget[]): boolean[] {
  const out = widgets.map((w) => w.kind !== 'bar');
  let run: number[] = [];
  const flush = () => {
    const last = run[run.length - 1];
    if (run.length % 2 === 1 && last !== undefined) out[last] = true;
    run = [];
  };
  widgets.forEach((w, i) => {
    if (w.kind === 'bar') run.push(i);
    else flush();
  });
  flush();
  return out;
}

function DashboardGrid({ widgets, onDrill }: { widgets: Widget[]; onDrill?: (p: Record<string, string>, l: string) => void }) {
  const wide = spans(widgets);
  return (
    <SimpleGrid cols={{ base: 1, lg: 2 }} spacing="md" className="ei-report-grid">
      {widgets.map((w, i) => (
        <WidgetCard key={w.id} widget={w} onDrill={onDrill} span={wide[i]} />
      ))}
    </SimpleGrid>
  );
}
