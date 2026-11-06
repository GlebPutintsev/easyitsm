import { keepPreviousData, useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, etagOf, newIdempotencyKey, type CursorPage } from '@/shared/api/client';

/** CMDB (docs/api/cmdb-contract.md): КЕ, связи, влияние, карта, история, сверка, качество. */

export type CiStatus = 'planned' | 'active' | 'maintenance' | 'retired';
export type CiLayer = 'business_service' | 'technical_service' | 'application' | 'infrastructure';
export type CiCriticality = 'low' | 'medium' | 'high' | 'critical';

export const CI_STATUSES: CiStatus[] = ['planned', 'active', 'maintenance', 'retired'];
export const CI_LAYERS: CiLayer[] = ['business_service', 'technical_service', 'application', 'infrastructure'];
export const CI_CRITICALITIES: CiCriticality[] = ['low', 'medium', 'high', 'critical'];

export interface AttributeDef {
  code: string;
  name: string;
  type: 'string' | 'text' | 'number' | 'boolean' | 'date' | 'ip' | 'enum';
  required: boolean;
  options: string[];
}

export interface CiClass {
  id: string;
  parentId: string | null;
  code: string;
  name: string;
  layer: CiLayer;
  isAbstract: boolean;
  system: boolean;
  active: boolean;
  attributes: AttributeDef[];
  ownAttributes: AttributeDef[];
  identificationRules: string[][];
  version: number;
}

export interface RelationType {
  id: string;
  code: string;
  name: string;
  inverseName: string;
  impactDirection: 'target_to_source' | 'source_to_target' | 'none';
  system: boolean;
  active: boolean;
}

export interface Ci {
  id: string;
  name: string;
  classCode: string;
  className: string;
  layer: CiLayer;
  status: CiStatus;
  environment: string | null;
  criticality: CiCriticality;
  ownerGroupId: string | null;
  description: string;
  attributes: Record<string, unknown>;
  attributeSources: Record<string, string>;
  lastSeenAt: string | null;
  createdAt: string;
  createdBy: string | null;
  updatedAt: string;
  updatedBy: string | null;
  version: number;
  mergedInto: string | null;
}

export interface CiRef {
  id: string;
  name: string;
  classCode: string;
  className: string;
  layer: CiLayer;
  status: CiStatus;
  environment: string | null;
  criticality: CiCriticality;
  ownerGroupId: string | null;
}

export interface Relation {
  id: string;
  typeCode: string;
  label: string;
  direction: 'outgoing' | 'incoming';
  impactDirection: RelationType['impactDirection'];
  other: { id: string; name: string; classCode: string; className: string; layer: CiLayer; status: CiStatus };
  origin: string;
  validFrom: string;
}

export interface Impacted {
  ci: CiRef;
  depth: number;
  path: string[];
}

export interface ImpactTicket {
  id: string;
  number: string;
  title: string;
  type: string;
  priority: string;
  statusCategory: string;
  ciId: string | null;
  serviceId: string | null;
}

export interface Impact {
  roots: CiRef[];
  impacted: Impacted[];
  services: Impacted[];
  tickets: ImpactTicket[];
  hiddenTickets: number;
  depth: number;
  truncated: boolean;
}

export interface HistoryEntry {
  id: string;
  field: string;
  oldValue: unknown;
  newValue: unknown;
  source: string;
  actorId: string | null;
  at: string;
}

export interface MapNode {
  ci: CiRef;
  depth: number;
  openIncidents: number;
  openChanges: number;
}

export interface MapEdge {
  id: string;
  sourceId: string;
  targetId: string;
  typeCode: string;
  name: string;
  impactDirection: RelationType['impactDirection'];
}

export interface CiMap {
  rootId: string;
  nodes: MapNode[];
  edges: MapEdge[];
  depth: number;
  truncated: boolean;
}

export interface QualitySummary {
  total: number;
  incomplete: number;
  stale: number;
  orphans: number;
  duplicateGroups: number;
  duplicates: number;
  score: number;
}

export type QualityIssue = 'incomplete' | 'stale' | 'orphan';

export interface DuplicateGroup {
  key: string;
  value: string;
  cis: CiRef[];
}

export type SourceKind = 'import' | 'api' | 'discovery' | 'kubernetes' | 'cloud';
export const SOURCE_KINDS: SourceKind[] = ['discovery', 'import', 'api', 'kubernetes', 'cloud'];

export interface Source {
  id: string;
  code: string;
  name: string;
  kind: SourceKind;
  priority: number;
  staleDays: number;
  active: boolean;
  records: number;
  lastBatchAt: string | null;
  /** Последние символы токена агента; null — токена нет. */
  tokenHint: string | null;
  tokenIssuedAt: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
}

/** Токен источника виден один раз, в ответе на выпуск. */
export interface IssuedToken {
  code: string;
  token: string;
  tokenHint: string;
  tokenIssuedAt: string;
}

export interface Batch {
  batchId: string;
  runId: string | null;
  final: boolean;
  received: number;
  created: number;
  updated: number;
  unchanged: number;
  conflicts: number;
  errors: number;
  removed: number;
  createdAt: string;
}

export type ConflictStatus = 'open' | 'accepted' | 'ignored' | 'merged' | 'dismissed';

export interface Conflict {
  id: string;
  kind: 'attribute' | 'duplicate';
  status: ConflictStatus;
  source: { code: string; name: string };
  externalId: string;
  ci: CiRef | null;
  attribute: string | null;
  currentValue: unknown;
  proposedValue: unknown;
  currentSource: string | null;
  candidates: CiRef[];
  itemName: string | null;
  itemClassCode: string | null;
  createdAt: string;
  updatedAt: string;
  resolvedAt: string | null;
  resolvedBy: string | null;
  version: number;
}

export interface CiFilter {
  q?: string;
  classCode?: string;
  layer?: CiLayer;
  status?: CiStatus;
  criticality?: CiCriticality;
  includeRetired?: boolean;
}

export interface CiFields {
  name?: string;
  classCode?: string;
  status?: CiStatus;
  environment?: string | null;
  criticality?: CiCriticality;
  ownerGroupId?: string | null;
  description?: string;
  attributes?: Record<string, unknown>;
}

export const cmdbKeys = {
  all: ['cmdb'] as const,
  classes: ['cmdb', 'classes'] as const,
  relationTypes: ['cmdb', 'relation-types'] as const,
  list: (f: CiFilter) => ['cmdb', 'cis', 'list', f] as const,
  one: (id: string) => ['cmdb', 'cis', id] as const,
  relations: (id: string) => ['cmdb', 'cis', id, 'relations'] as const,
  impact: (id: string, depth: number) => ['cmdb', 'cis', id, 'impact', depth] as const,
  history: (id: string) => ['cmdb', 'cis', id, 'history'] as const,
  map: (id: string, depth: number) => ['cmdb', 'cis', id, 'map', depth] as const,
  quality: ['cmdb', 'quality'] as const,
  sources: ['cmdb', 'sources'] as const,
  conflicts: (status: string) => ['cmdb', 'conflicts', status] as const,
};

export function useClasses() {
  return useQuery({
    queryKey: cmdbKeys.classes,
    queryFn: ({ signal }) => api.get<CiClass[]>('/cmdb/classes', { signal }),
    staleTime: 5 * 60_000,
  });
}

export function useRelationTypes() {
  return useQuery({
    queryKey: cmdbKeys.relationTypes,
    queryFn: ({ signal }) => api.get<RelationType[]>('/cmdb/relation-types', { signal }),
    staleTime: 5 * 60_000,
  });
}

export function useCis(filter: CiFilter) {
  return useInfiniteQuery({
    queryKey: cmdbKeys.list(filter),
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) =>
      api.get<CursorPage<Ci>>('/cmdb/cis', {
        query: { ...filter, q: filter.q || undefined, includeRetired: filter.includeRetired || undefined, cursor: pageParam, limit: 50 },
        signal,
      }),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    placeholderData: keepPreviousData,
  });
}

export function useCi(id: string | undefined) {
  return useQuery({
    queryKey: cmdbKeys.one(id ?? ''),
    queryFn: ({ signal }) => api.get<Ci>(`/cmdb/cis/${id}`, { signal }),
    enabled: !!id,
  });
}

export function useRelations(id: string) {
  return useQuery({
    queryKey: cmdbKeys.relations(id),
    queryFn: ({ signal }) => api.get<Relation[]>(`/cmdb/cis/${id}/relations`, { signal }),
  });
}

export function useImpact(id: string, depth: number, enabled = true) {
  return useQuery({
    queryKey: cmdbKeys.impact(id, depth),
    queryFn: ({ signal }) => api.get<Impact>(`/cmdb/cis/${id}/impact`, { query: { depth }, signal }),
    enabled,
  });
}

export function useHistory(id: string, enabled = true) {
  return useInfiniteQuery({
    queryKey: cmdbKeys.history(id),
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) =>
      api.get<CursorPage<HistoryEntry>>(`/cmdb/cis/${id}/history`, { query: { cursor: pageParam, limit: 50 }, signal }),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    enabled,
  });
}

export function useCiMap(id: string, depth: number, enabled = true) {
  return useQuery({
    queryKey: cmdbKeys.map(id, depth),
    queryFn: ({ signal }) => api.get<CiMap>(`/cmdb/cis/${id}/map`, { query: { depth }, signal }),
    enabled,
  });
}

export function useCreateCi() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (fields: CiFields) => api.post<Ci>('/cmdb/cis', fields, { idempotencyKey: newIdempotencyKey() }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: cmdbKeys.all }),
  });
}

export function useUpdateCi(ci: Ci) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (fields: CiFields) => api.patch<Ci>(`/cmdb/cis/${ci.id}`, fields, { ifMatch: etagOf(ci.version) }),
    onSuccess: (saved) => {
      qc.setQueryData(cmdbKeys.one(saved.id), saved);
      void qc.invalidateQueries({ queryKey: cmdbKeys.all });
    },
  });
}

export function useAddRelation(ciId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { typeCode: string; targetId: string; direction: 'outgoing' | 'incoming' }) =>
      api.post<Relation>(`/cmdb/cis/${ciId}/relations`, body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: cmdbKeys.all }),
  });
}

export function useRemoveRelation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (relationId: string) => api.delete(`/cmdb/relations/${relationId}`),
    onSuccess: () => void qc.invalidateQueries({ queryKey: cmdbKeys.all }),
  });
}

export function useQuality() {
  return useQuery({
    queryKey: cmdbKeys.quality,
    queryFn: ({ signal }) => api.get<QualitySummary>('/cmdb/quality', { signal }),
  });
}

export function useQualityIssues(kind: QualityIssue) {
  return useQuery({
    queryKey: [...cmdbKeys.quality, kind],
    queryFn: ({ signal }) => api.get<CiRef[]>('/cmdb/quality/issues', { query: { kind }, signal }),
  });
}

export function useDuplicates() {
  return useQuery({
    queryKey: [...cmdbKeys.quality, 'duplicates'],
    queryFn: ({ signal }) => api.get<DuplicateGroup[]>('/cmdb/quality/duplicates', { signal }),
  });
}

export function useMerge() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ keepId, mergedId }: { keepId: string; mergedId: string }) =>
      api.post<Ci>(`/cmdb/cis/${keepId}/merge`, { mergedId }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: cmdbKeys.all }),
  });
}

export function useSources() {
  return useQuery({
    queryKey: cmdbKeys.sources,
    queryFn: ({ signal }) => api.get<Source[]>('/cmdb/sources', { signal }),
  });
}

export function useBatches(code: string | null) {
  return useQuery({
    queryKey: [...cmdbKeys.sources, code, 'batches'],
    queryFn: ({ signal }) => api.get<Batch[]>(`/cmdb/sources/${code}/batches`, { query: { limit: 10 }, signal }),
    enabled: !!code,
  });
}

export function useIssueSourceToken() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (code: string) => api.post<IssuedToken>(`/cmdb/sources/${code}/token`, {}),
    onSuccess: () => void qc.invalidateQueries({ queryKey: cmdbKeys.sources }),
  });
}

export function useRevokeSourceToken() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (code: string) => api.delete<Source>(`/cmdb/sources/${code}/token`),
    onSuccess: () => void qc.invalidateQueries({ queryKey: cmdbKeys.sources }),
  });
}

export function useSaveSource(source: Source | null) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: Partial<Pick<Source, 'code' | 'name' | 'kind' | 'priority' | 'staleDays' | 'active'>>) =>
      source
        ? api.patch<Source>(`/cmdb/sources/${source.code}`, body, { ifMatch: etagOf(source.version) })
        : api.post<Source>('/cmdb/sources', body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: cmdbKeys.sources }),
  });
}

export function useConflicts(status: ConflictStatus | 'all') {
  return useInfiniteQuery({
    queryKey: cmdbKeys.conflicts(status),
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) =>
      api.get<CursorPage<Conflict>>('/cmdb/conflicts', { query: { status, cursor: pageParam, limit: 50 }, signal }),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    placeholderData: keepPreviousData,
  });
}

export function useResolveConflict() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, action, ciId }: { id: string; action: 'accept' | 'ignore' | 'merge'; ciId?: string }) =>
      api.post<Conflict>(`/cmdb/conflicts/${id}/resolve`, { action, ciId }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: cmdbKeys.all }),
  });
}

/** Значение атрибута или истории для показа: объекты и списки — JSON, пустое — «—». */
export function formatValue(value: unknown): string {
  if (value === null || value === undefined || value === '') return '—';
  if (typeof value === 'boolean') return value ? '✓' : '✗';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

export interface GroupRef {
  id: string;
  name: string;
}

/** Группы для выбора владельца КЕ; читает любой сотрудник поддержки. */
export function useGroups() {
  return useQuery({
    queryKey: ['cmdb', 'groups'],
    queryFn: ({ signal }) => api.get<CursorPage<GroupRef>>('/groups', { query: { limit: 200 }, signal }).then((p) => p.items),
    staleTime: 5 * 60_000,
  });
}
