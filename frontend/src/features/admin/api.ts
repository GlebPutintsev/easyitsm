import './i18n';
import { keepPreviousData, useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, etagOf, newIdempotencyKey, type CursorPage } from '@/shared/api/client';

/**
 * Клиент «Управления». Свои настройки модуля — /api/v1/admin/*. Пользователи и группы — REST identity,
 * графики — REST sla, категории, статусы и матрица — REST tickets. После сохранения чужой настройки экран
 * записывает её версию в /admin/config-versions (ADM-06).
 */

// ---------------------------------------------------------------- версии настроек

export interface VersionSummary {
  id: string;
  entity: string;
  entityId: string;
  label: string;
  versionNo: number;
  action: 'create' | 'update' | 'delete' | 'restore' | 'import' | 'rollback' | 'setup';
  comment: string;
  source: 'server' | 'ui';
  authorId: string | null;
  createdAt: string;
}

export interface Version extends VersionSummary {
  snapshot: unknown;
}

export interface Change {
  path: string;
  kind: 'added' | 'removed' | 'changed';
  before: unknown;
  after: unknown;
}

export interface Diff {
  from: VersionSummary | null;
  to: VersionSummary;
  changes: Change[];
}

export interface JournalFilter {
  entity?: string;
  entityId?: string;
  action?: string;
  author?: string;
}

export const adminKeys = {
  versions: (f: JournalFilter) => ['admin', 'versions', f] as const,
  setup: ['admin', 'setup'] as const,
  dictionaries: ['admin', 'dictionaries'] as const,
  orgUnits: ['admin', 'org-units'] as const,
  imports: ['admin', 'imports'] as const,
};

export function useVersions(filter: JournalFilter, enabled = true) {
  return useInfiniteQuery({
    queryKey: adminKeys.versions(filter),
    enabled,
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) =>
      api.get<CursorPage<VersionSummary>>('/admin/config-versions', {
        query: { ...filter, cursor: pageParam, limit: 50 },
        signal,
      }),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    placeholderData: keepPreviousData,
  });
}

export function useDiff(id: string | null) {
  return useQuery({
    queryKey: ['admin', 'diff', id],
    enabled: !!id,
    queryFn: ({ signal }) => api.get<Diff>(`/admin/config-versions/${id}/diff`, { signal }),
  });
}

export function getVersion(id: string) {
  return api.get<Version>(`/admin/config-versions/${id}`);
}

/** Версия настройки другого модуля после успешного сохранения. Ошибка записи версии не отменяет сохранение. */
export async function recordVersion(
  entity: string,
  entityId: string,
  label: string,
  action: 'create' | 'update' | 'delete' | 'restore',
  snapshot: unknown,
): Promise<void> {
  try {
    await api.post('/admin/config-versions', { entity, entityId, label, action, snapshot });
  } catch (e) {
    console.warn('Версия настройки не записана', e);
  }
}

// ---------------------------------------------------------------- мастер

export type StepState = 'pending' | 'done' | 'skipped';
export const SETUP_STEPS = ['organization', 'login', 'mail', 'groups', 'services', 'sla', 'demo'] as const;
export type SetupStep = (typeof SETUP_STEPS)[number];

export interface SetupState {
  organization: { name: string; timezone: string; locale: string };
  steps: Record<SetupStep, StepState>;
  nextStep: SetupStep | null;
  completedAt: string | null;
  demoLoadedAt: string | null;
  version: number;
}

export interface CheckReport {
  configured: boolean;
  ok: boolean;
  results: { target: string; ok: boolean; code: string; detail: string; latencyMs: number }[];
}

export interface DemoLoaded {
  departments: number;
  groups: number;
  users: number;
  slaInstalled: boolean;
  tickets: string[];
}

export function useSetup(enabled = true) {
  return useQuery({
    queryKey: adminKeys.setup,
    enabled,
    queryFn: ({ signal }) => api.get<SetupState>('/admin/setup', { signal }),
  });
}

export function useSetupMutations() {
  const qc = useQueryClient();
  const set = (s: SetupState) => qc.setQueryData(adminKeys.setup, s);
  return {
    organization: useMutation({
      mutationFn: (v: { org: SetupState['organization']; version: number }) =>
        api.put<SetupState>('/admin/setup/organization', v.org, { ifMatch: etagOf(v.version) }),
      onSuccess: set,
    }),
    step: useMutation({
      mutationFn: (v: { step: SetupStep; status: StepState }) =>
        api.put<SetupState>(`/admin/setup/steps/${v.step}`, { status: v.status }),
      onSuccess: set,
    }),
    complete: useMutation({
      mutationFn: () => api.post<SetupState>('/admin/setup/complete', undefined),
      onSuccess: set,
    }),
    demo: useMutation({
      mutationFn: () => api.post<DemoLoaded>('/admin/setup/demo-data', undefined),
      onSuccess: () => void qc.invalidateQueries({ queryKey: ['admin'] }),
    }),
    checkMail: useMutation({ mutationFn: () => api.post<CheckReport>('/admin/setup/checks/mail', undefined) }),
    checkSso: useMutation({ mutationFn: () => api.post<CheckReport>('/admin/setup/checks/sso', undefined) }),
  };
}

// ---------------------------------------------------------------- справочники кодов

export type DictionaryKind = 'resolution' | 'pending' | 'cancel';
export interface DictionaryItem {
  code: string;
  name: string;
  active: boolean;
}
export interface Dictionary {
  kind: DictionaryKind;
  items: DictionaryItem[];
  version: number;
}

export function useDictionaries() {
  return useQuery({
    queryKey: adminKeys.dictionaries,
    queryFn: ({ signal }) => api.get<Dictionary[]>('/admin/dictionaries', { signal }),
  });
}

export function useSaveDictionary() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (d: Dictionary) =>
      api.put<Dictionary>(`/admin/dictionaries/${d.kind}`, { items: d.items }, { ifMatch: etagOf(d.version) }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: adminKeys.dictionaries }),
  });
}

// ---------------------------------------------------------------- подразделения

export interface OrgUnit {
  id: string;
  kind: 'organization' | 'department';
  name: string;
  code: string | null;
  parentId: string | null;
  active: boolean;
  version: number;
}

export function useOrgUnits() {
  return useQuery({
    queryKey: adminKeys.orgUnits,
    queryFn: ({ signal }) => api.get<OrgUnit[]>('/admin/org-units', { signal }),
  });
}

export function useSaveOrgUnit() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (u: Partial<OrgUnit> & { name: string; kind: OrgUnit['kind'] }) =>
      u.id
        ? api.patch<OrgUnit>(
            `/admin/org-units/${u.id}`,
            { name: u.name, code: u.code ?? '', parentId: u.parentId ?? undefined, clearParent: !u.parentId, active: u.active },
            { ifMatch: etagOf(u.version ?? 0) },
          )
        : api.post<OrgUnit>(
            '/admin/org-units',
            { kind: u.kind, name: u.name, code: u.code || undefined, parentId: u.parentId || undefined },
            { idempotencyKey: newIdempotencyKey() },
          ),
    onSuccess: () => void qc.invalidateQueries({ queryKey: adminKeys.orgUnits }),
  });
}

// ---------------------------------------------------------------- дежурства

export interface DutyShift {
  id: string;
  groupId: string;
  userId: string;
  startsAt: string;
  endsAt: string;
  note: string;
}

export function useDutyShifts(groupId: string | null, from: string, to: string) {
  return useQuery({
    queryKey: ['admin', 'duty', groupId, from, to],
    enabled: !!groupId,
    queryFn: ({ signal }) =>
      api.get<DutyShift[]>('/admin/duty-shifts', { query: { groupId: groupId!, from, to }, signal }),
  });
}

export function useDutyMutations() {
  const qc = useQueryClient();
  const refresh = () => void qc.invalidateQueries({ queryKey: ['admin', 'duty'] });
  return {
    create: useMutation({
      mutationFn: (s: Omit<DutyShift, 'id'>) =>
        api.post<DutyShift>('/admin/duty-shifts', s, { idempotencyKey: newIdempotencyKey() }),
      onSuccess: refresh,
    }),
    remove: useMutation({
      mutationFn: (id: string) => api.delete(`/admin/duty-shifts/${id}`),
      onSuccess: refresh,
    }),
  };
}

// ---------------------------------------------------------------- импорт

export type ImportKind = 'users' | 'groups' | 'departments';

export interface ImportRow {
  line: number;
  values: Record<string, string>;
  action: 'create' | 'skip' | 'error';
  errors: string[];
  warnings: string[];
}

export interface ImportJob {
  id: string;
  kind: ImportKind;
  status: 'preview' | 'applied' | 'rolled_back';
  fileName: string;
  totalRows: number;
  validRows: number;
  errorRows: number;
  skipRows: number;
  rows: ImportRow[];
  columns: string[];
  appliedAt: string | null;
  rolledBackAt: string | null;
  createdAt: string;
  result: Record<string, number>;
}

export interface ImportSummary {
  id: string;
  kind: ImportKind;
  status: ImportJob['status'];
  fileName: string;
  totalRows: number;
  validRows: number;
  createdAt: string;
  appliedAt: string | null;
  rolledBackAt: string | null;
}

export function useImports() {
  return useQuery({
    queryKey: adminKeys.imports,
    queryFn: ({ signal }) => api.get<CursorPage<ImportSummary>>('/admin/imports', { query: { limit: 20 }, signal }),
  });
}

export function useImportMutations() {
  const qc = useQueryClient();
  const refresh = () => void qc.invalidateQueries({ queryKey: adminKeys.imports });
  return {
    upload: useMutation({
      mutationFn: (v: { kind: ImportKind; fileName: string; content: string }) =>
        api.post<ImportJob>('/admin/imports', v, { idempotencyKey: newIdempotencyKey() }),
      onSuccess: refresh,
    }),
    apply: useMutation({
      mutationFn: (v: { id: string; skipInvalid: boolean }) =>
        api.post<ImportJob>(`/admin/imports/${v.id}/apply`, undefined, { query: { skipInvalid: v.skipInvalid } }),
      onSuccess: () => {
        refresh();
        void qc.invalidateQueries({ queryKey: ['admin', 'users'] });
      },
    }),
    rollback: useMutation({
      mutationFn: (id: string) => api.post<ImportJob>(`/admin/imports/${id}/rollback`, undefined),
      onSuccess: refresh,
    }),
  };
}

export function getImport(id: string) {
  return api.get<ImportJob>(`/admin/imports/${id}`);
}
