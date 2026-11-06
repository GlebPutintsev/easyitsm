import { useQuery } from '@tanstack/react-query';
import { api, downloadFile, type CursorPage } from '@/shared/api/client';

/**
 * Клиент модуля отчётов (ru.easyitsm.reporting). Форма ответов — ReportViews.java. Подписи дашбордов,
 * виджетов, колонок и строк приходят с сервера на языке интерфейса: те же подписи попадают в выгрузки.
 */

export type ColumnType = 'text' | 'date' | 'number' | 'percent' | 'duration' | 'score';

export interface ReportColumn {
  key: string;
  label: string;
  type: ColumnType;
  /** Показатель для списка заявок по клику на значение колонки */
  drill: string | null;
}

export type DrillParams = Record<string, string>;

export interface ReportRow {
  key: string;
  label?: string;
  /** Для показателей (kpi): значение и его тип */
  value?: number | null;
  type?: ColumnType;
  drill: DrillParams | null;
  [column: string]: unknown;
}

export interface Widget {
  id: string;
  kind: 'kpi' | 'line' | 'bar' | 'table';
  title: string;
  columns: ReportColumn[];
  rows: ReportRow[];
}

export interface Dashboard {
  id: string;
  title: string;
  from: string;
  to: string;
  timezone: string;
  granularity: 'day' | 'week';
  allTickets: boolean;
  canDrillDown: boolean;
  generatedAt: string;
  widgets: Widget[];
}

export interface DashboardRef {
  id: string;
  title: string;
}

export interface TicketRow {
  id: string;
  number: string;
  type: string;
  title: string;
  statusCode: string;
  statusCategory: string;
  priority: 'P1' | 'P2' | 'P3' | 'P4';
  groupId: string | null;
  groupName: string;
  serviceId: string | null;
  serviceName: string;
  assigneeId: string | null;
  createdAt: string;
  firstResponseAt: string | null;
  resolvedAt: string | null;
  slaBreached: boolean;
  csatScore: number | null;
}

/** Период и фильтры дашборда: применяются ко всем виджетам (10.15). */
export interface ReportFilters {
  from: string;
  to: string;
  groupId?: string;
  type?: string;
  priority?: string;
  serviceId?: string;
}

export type ExportFormat = 'csv' | 'xlsx' | 'pdf';

export interface Ref {
  id: string;
  name: string;
}

function timezone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  } catch {
    return 'UTC';
  }
}

function query(filters: ReportFilters, lang: string, extra: DrillParams = {}) {
  return { ...filters, tz: timezone(), lang: lang.startsWith('en') ? 'en' : 'ru', ...extra };
}

export const reportsApi = {
  dashboards: (lang: string, signal?: AbortSignal) =>
    api.get<DashboardRef[]>('/reports/dashboards', { query: { lang }, signal }),
  dashboard: (id: string, filters: ReportFilters, lang: string, signal?: AbortSignal) =>
    api.get<Dashboard>(`/reports/dashboards/${id}`, { query: query(filters, lang), signal }),
  tickets: (filters: ReportFilters, drill: DrillParams, lang: string, cursor?: string, signal?: AbortSignal) =>
    api.get<CursorPage<TicketRow>>('/reports/tickets', {
      query: { ...query(filters, lang, drill), cursor, limit: 50 },
      signal,
    }),
  exportDashboard: (id: string, filters: ReportFilters, lang: string, format: ExportFormat) =>
    downloadFile(`/reports/dashboards/${id}/export`, { ...query(filters, lang), format }, `easyitsm-${id}.${format}`),
  exportTickets: (filters: ReportFilters, drill: DrillParams, lang: string, format: ExportFormat, reason?: string) =>
    downloadFile('/reports/tickets/export', { ...query(filters, lang, drill), format, reason }, `easyitsm-tickets.${format}`),
  /** Группы и услуги для фильтров: тот же справочник, что у рабочего места агента. */
  directory: (signal?: AbortSignal) =>
    api.get<{ groups: Ref[]; services: Ref[] }>('/agent-workspace/directory', { signal }),
};

export function useDashboard(id: string, filters: ReportFilters, lang: string) {
  return useQuery({
    queryKey: ['reports', 'dashboard', id, filters, lang],
    queryFn: ({ signal }) => reportsApi.dashboard(id, filters, lang, signal),
    staleTime: 60_000,
    placeholderData: (prev) => (prev?.id === id ? prev : undefined),
  });
}

export function useReportDirectory() {
  return useQuery({
    queryKey: ['reports', 'directory'],
    queryFn: ({ signal }) => reportsApi.directory(signal),
    staleTime: 10 * 60_000,
  });
}
