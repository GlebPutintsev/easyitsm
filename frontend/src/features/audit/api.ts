import { keepPreviousData, useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, saveBlob, type CursorPage } from '@/shared/api/client';

/** Журнал аудита (/api/v1/audit): SEC и AUD читают, проверяют целостность и выгружают. */

export type AuditCategory = 'auth' | 'access' | 'config' | 'data' | 'security' | 'audit';
export type AuditResult = 'success' | 'failure' | 'denied';

export interface AuditEvent {
  id: string;
  seq: number;
  at: string;
  category: AuditCategory;
  action: string;
  result: AuditResult;
  actorId: string | null;
  actorLogin: string | null;
  objectType: string | null;
  objectId: string | null;
  ip: string | null;
  userAgent: string | null;
  traceId: string | null;
  details: Record<string, unknown>;
  prevHash: string;
  hash: string;
}

export interface AuditFilter {
  from?: string;
  to?: string;
  category?: AuditCategory;
  result?: AuditResult;
  action?: string;
  q?: string;
}

export interface Verification {
  valid: boolean;
  checked: number;
  headSeq: number;
  headHash: string;
  brokenAtSeq: number | null;
  problem: 'hash_mismatch' | 'link_mismatch' | 'gap' | 'truncated' | null;
  verifiedAt: string;
}

export interface SiemStatus {
  enabled: boolean;
  transport: 'TLS' | 'TCP';
  format: 'CEF' | 'JSON';
  target: string | null;
  headSeq: number;
  deliveredSeq: number;
  lag: number;
  deliveredAt?: string | null;
  failures?: number;
  nextAttemptAt?: string | null;
  lastError?: string | null;
  lastErrorAt?: string | null;
}

export const auditKeys = {
  all: ['audit'] as const,
  events: (f: AuditFilter) => ['audit', 'events', f] as const,
  siem: ['audit', 'siem'] as const,
};

/** Дата из поля «с»/«по» (yyyy-mm-dd, местное время) → ISO для API. «По» включает весь день. */
export function dayBound(day: string | undefined, end = false): string | undefined {
  if (!day) return undefined;
  const d = new Date(`${day}T00:00:00`);
  if (Number.isNaN(d.getTime())) return undefined;
  if (end) d.setDate(d.getDate() + 1);
  return d.toISOString();
}

function query(f: AuditFilter) {
  return {
    from: dayBound(f.from),
    to: dayBound(f.to, true),
    category: f.category,
    result: f.result,
    action: f.action || undefined,
    q: f.q || undefined,
  };
}

export function useAuditEvents(filter: AuditFilter) {
  return useInfiniteQuery({
    queryKey: auditKeys.events(filter),
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) =>
      api.get<CursorPage<AuditEvent>>('/audit/events', { query: { ...query(filter), cursor: pageParam, limit: 50 }, signal }),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    placeholderData: keepPreviousData,
  });
}

export function useVerify() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api.post<Verification>('/audit/verify', {}),
    onSuccess: () => qc.invalidateQueries({ queryKey: auditKeys.all }),
  });
}

export function useExport() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ reason, filter }: { reason: string; filter: AuditFilter }) => {
      const blob = await api.download('/audit/export', { reason, ...query(filter) });
      const stamp = new Date().toISOString().slice(0, 19).replace(/[-:T]/g, '');
      saveBlob(blob, `audit-${stamp}.csv`);
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: auditKeys.all }),
  });
}

export function useSiemStatus(enabled: boolean) {
  return useQuery({
    queryKey: auditKeys.siem,
    queryFn: ({ signal }) => api.get<SiemStatus>('/audit/siem', { signal }),
    enabled,
    refetchInterval: 30_000,
  });
}

export function useSiemTest() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api.post<{ ok: boolean; error?: string }>('/audit/siem/test', {}),
    onSettled: () => qc.invalidateQueries({ queryKey: auditKeys.siem }),
  });
}
