import './i18n';
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, etagOf, newIdempotencyKey, type CursorPage } from '@/shared/api/client';
import type { Op, Trigger } from './model';

/** Типы и запросы API автоматизации (/api/v1/automation). */

export interface Condition {
  field: string;
  op: Op;
  value?: unknown;
}

export interface Conditions {
  match: 'all' | 'any';
  items: Condition[];
}

export type ActionType =
  | 'assign'
  | 'set_priority'
  | 'set_status'
  | 'add_comment'
  | 'add_watcher'
  | 'escalate'
  | 'notify'
  | 'webhook';

export type NotifyTarget = 'requester' | 'assignee' | 'group' | 'watchers' | 'user';

export interface Action {
  type: ActionType;
  groupId?: string | null;
  assigneeId?: string | null;
  priority?: string | null;
  status?: string | null;
  body?: string | null;
  visibility?: 'public' | 'internal' | null;
  userId?: string | null;
  reasonCode?: string | null;
  resolutionCode?: string | null;
  kind?: 'functional' | 'hierarchical' | null;
  reason?: string | null;
  target?: NotifyTarget | null;
  title?: string | null;
  email?: boolean | null;
  webhookId?: string | null;
}

export interface Rule {
  id: string;
  name: string;
  description: string;
  trigger: Trigger;
  conditions: Conditions;
  actions: Action[];
  enabled: boolean;
  position: number;
  version: number;
  createdAt: string;
  updatedAt: string;
  updatedBy: string | null;
}

export type RuleInput = Partial<Pick<Rule, 'name' | 'description' | 'trigger' | 'conditions' | 'actions' | 'enabled' | 'position'>>;

export interface Run {
  id: string;
  ruleId: string | null;
  ruleName: string;
  ruleVersion: number;
  trigger: Trigger;
  ticketId: string | null;
  ticketNumber: string | null;
  depth: number;
  chain: string[];
  status: 'success' | 'failed' | 'skipped';
  skipReason: 'depth_limit' | 'loop' | 'rate_limit' | null;
  results: { type: ActionType; outcome: 'applied' | 'unchanged'; detail?: string }[];
  errorCode: string | null;
  error: string | null;
  startedAt: string;
  durationMs: number;
}

export interface TestResult {
  ticket: { id: string; number: string; title: string; statusCode: string; priority: string | null };
  matched: boolean | null;
  conditions: (boolean | null)[];
  unknownFields: string[];
  actions: { type: ActionType; outcome: 'change' | 'no_change' | 'error'; detail?: string }[];
}

export interface RunFilter {
  ruleId?: string;
  status?: string;
  ticket?: string;
}

export interface GroupOption {
  id: string;
  name: string;
}

export interface WorkflowStatus {
  code: string;
  name: string;
  category: string;
}

const keys = {
  rules: ['automation', 'rules'] as const,
  rule: (id: string) => ['automation', 'rules', id] as const,
  runs: (f: RunFilter) => ['automation', 'runs', f] as const,
};

export function useRules() {
  return useQuery({
    queryKey: keys.rules,
    queryFn: ({ signal }) => api.get<CursorPage<Rule>>('/automation/rules', { signal }),
    select: (page) => page.items,
  });
}

export function useRule(id: string | undefined) {
  return useQuery({
    queryKey: keys.rule(id ?? ''),
    queryFn: ({ signal }) => api.get<Rule>(`/automation/rules/${id}`, { signal }),
    enabled: !!id,
  });
}

export function useSaveRule() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, version, input }: { id?: string; version?: number; input: RuleInput }) =>
      id
        ? api.patch<Rule>(`/automation/rules/${id}`, input, { ifMatch: etagOf(version ?? 0) })
        : api.post<Rule>('/automation/rules', input, { idempotencyKey: newIdempotencyKey() }),
    onSuccess: (saved) => {
      qc.setQueryData(keys.rule(saved.id), saved);
      void qc.invalidateQueries({ queryKey: keys.rules });
    },
  });
}

export function useDeleteRule() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.delete<void>(`/automation/rules/${id}`),
    onSuccess: () => void qc.invalidateQueries({ queryKey: keys.rules }),
  });
}

export function useTestRule() {
  return useMutation({
    mutationFn: ({ rule, ticket }: { rule: RuleInput; ticket: string }) =>
      api.post<TestResult>('/automation/test', { rule, ticket }),
  });
}

export function useRuns(filter: RunFilter) {
  return useInfiniteQuery({
    queryKey: keys.runs(filter),
    queryFn: ({ pageParam, signal }) =>
      api.get<CursorPage<Run>>('/automation/runs', {
        query: { ...filter, cursor: pageParam ?? undefined, limit: 50 },
        signal,
      }),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor,
  });
}

/** Группы для выбора в условиях и действиях (справочник identity, доступен ADM). */
export function useGroups() {
  return useQuery({
    queryKey: ['automation', 'groups'],
    queryFn: ({ signal }) => api.get<CursorPage<GroupOption>>('/groups', { query: { limit: 200 }, signal }),
    select: (page) => page.items,
    staleTime: 5 * 60_000,
  });
}

export interface WebhookOption {
  id: string;
  name: string;
  url: string;
  enabled: boolean;
}

/** Вебхуки «Интеграций» для действия «Отправить вебхук». */
export function useWebhooks() {
  return useQuery({
    queryKey: ['automation', 'webhooks'],
    queryFn: ({ signal }) => api.get<CursorPage<WebhookOption>>('/integrations/webhooks', { signal }),
    select: (page) => page.items,
    staleTime: 60_000,
  });
}

/** Статусы всех циклов заявок без повторов кода: в правиле статус выбирается до типа заявки. */
export function useStatuses() {
  return useQuery({
    queryKey: ['automation', 'statuses'],
    queryFn: ({ signal }) =>
      api.get<{ type: string; statuses: WorkflowStatus[] }[]>('/ticket-settings/workflows', { signal }),
    select: (workflows) => {
      const byCode = new Map<string, WorkflowStatus>();
      for (const wf of workflows) for (const s of wf.statuses) if (!byCode.has(s.code)) byCode.set(s.code, s);
      return [...byCode.values()];
    },
    staleTime: 5 * 60_000,
  });
}
