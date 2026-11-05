import './i18n';
import { keepPreviousData, useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, etagOf, newIdempotencyKey, type CursorPage } from '@/shared/api/client';
import type { Condition, FormSchema } from './form/types';

/** Типы и запросы API каталога и согласований (/api/v1/catalog, /api/v1/approvals). */

export interface Category {
  id: string;
  parentId: string | null;
  name: string;
  description: string;
  icon: string | null;
  sortOrder: number;
  active: boolean;
  version: number;
}

export interface ItemCard {
  id: string;
  categoryId: string;
  kind: 'request' | 'incident';
  name: string;
  summary: string;
  icon: string | null;
  team: string | null;
  cost: number | null;
  currency: string | null;
  leadTimeHours: number | null;
  favorite: boolean;
  active: boolean;
}

export interface ItemDetail extends Omit<ItemCard, 'favorite'> {
  description: string;
  keywords: string;
  form: FormSchema;
  approvalFlowId: string | null;
  visibleRoles: string[];
  allowOnBehalf: boolean;
  sortOrder: number;
  favorite: boolean;
  orderCount: number;
  updatedAt: string;
  version: number;
}

export type ItemInput = Omit<ItemDetail, 'id' | 'favorite' | 'orderCount' | 'updatedAt' | 'version'>;

export interface StepDef {
  name: string;
  mode: 'all' | 'any' | 'quorum';
  quorum?: number | null;
  approvers: string[];
  condition?: Condition | null;
  dueMinutes?: number | null;
  allowDelegation?: boolean;
}

export interface Flow {
  id: string;
  name: string;
  description: string;
  steps: StepDef[];
  active: boolean;
  updatedAt: string;
  version: number;
}

export type FlowInput = Pick<Flow, 'name' | 'description' | 'steps' | 'active'>;

export interface OrderInput {
  forUserId?: string | null;
  title?: string;
  description?: string;
  impact?: 'only_me' | 'department' | 'everyone';
  values: Record<string, unknown>;
}

export interface OrderResult {
  ticketId: string;
  number: string;
  status: string;
  awaitingApproval: boolean;
  expectedBy: string | null;
}

export type ApprovalDecision = 'pending' | 'approved' | 'rejected' | 'skipped' | 'delegated' | 'cancelled';

export interface Approval {
  id: string;
  ticketId: string;
  ticketNumber: string;
  title: string;
  requesterId: string;
  createdForId: string | null;
  stepName: string;
  stepIndex: number;
  decision: ApprovalDecision;
  comment: string | null;
  infoRequestedAt: string | null;
  dueAt: string | null;
  decidedAt: string | null;
  createdAt: string;
  delegateFrom: string | null;
  allowDelegation: boolean;
}

export interface RouteStep {
  index: number;
  name: string;
  mode: StepDef['mode'];
  required: number;
  state: 'pending' | 'waiting' | 'approved' | 'rejected' | 'skipped' | 'cancelled';
  approvals: { id: string; approverId: string; decision: ApprovalDecision; comment: string | null; decidedAt: string | null; delegateFrom: string | null }[];
}

export interface Route {
  ticketId: string;
  state: 'pending' | 'approved' | 'rejected' | 'cancelled';
  currentStep: number;
  decidedAt: string | null;
  steps: RouteStep[];
}

export const catalogKeys = {
  all: ['catalog'] as const,
  categories: (all = false) => ['catalog', 'categories', all] as const,
  items: (filter: ItemFilter) => ['catalog', 'items', filter] as const,
  item: (id: string) => ['catalog', 'item', id] as const,
  popular: ['catalog', 'popular'] as const,
  teams: ['catalog', 'teams'] as const,
  flows: ['catalog', 'flows'] as const,
  approvals: (tab: string) => ['approvals', tab] as const,
  approvalCount: ['approvals', 'count'] as const,
  route: (ticketId: string) => ['approvals', 'route', ticketId] as const,
};

export interface ItemFilter {
  categoryId?: string;
  q?: string;
  team?: string;
  favorites?: boolean;
  all?: boolean;
}

export function useCategories(includeInactive = false) {
  return useQuery({
    queryKey: catalogKeys.categories(includeInactive),
    queryFn: ({ signal }) =>
      api.get<CursorPage<Category>>('/catalog/categories', { query: { all: includeInactive || undefined }, signal }),
    select: (page) => page.items,
  });
}

export function useCatalogItems(filter: ItemFilter) {
  return useInfiniteQuery({
    queryKey: catalogKeys.items(filter),
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) =>
      api.get<CursorPage<ItemCard>>('/catalog/items', {
        query: {
          categoryId: filter.categoryId,
          q: filter.q || undefined,
          team: filter.team,
          favorites: filter.favorites || undefined,
          all: filter.all || undefined,
          cursor: pageParam,
          limit: 48,
        },
        signal,
      }),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    placeholderData: keepPreviousData,
  });
}

export function usePopularItems() {
  return useQuery({
    queryKey: catalogKeys.popular,
    queryFn: ({ signal }) => api.get<CursorPage<ItemCard>>('/catalog/items', { query: { popular: true }, signal }),
    select: (page) => page.items,
  });
}

export function useTeams() {
  return useQuery({
    queryKey: catalogKeys.teams,
    queryFn: ({ signal }) => api.get<string[]>('/catalog/teams', { signal }),
    staleTime: 5 * 60_000,
  });
}

export function useCatalogItem(id: string | undefined) {
  return useQuery({
    queryKey: catalogKeys.item(id ?? ''),
    queryFn: ({ signal }) => api.get<ItemDetail>(`/catalog/items/${id}`, { signal }),
    enabled: !!id,
  });
}

export function useToggleFavorite() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, on }: { id: string; on: boolean }) =>
      on ? api.put<void>(`/catalog/items/${id}/favorite`) : api.delete<void>(`/catalog/items/${id}/favorite`),
    onSuccess: () => qc.invalidateQueries({ queryKey: catalogKeys.all }),
  });
}

/** Заказ услуги. Ключ идемпотентности создаётся один раз на открытую форму и защищает от двойной отправки. */
export function useOrder(itemId: string, idempotencyKey: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input: OrderInput) =>
      api.post<OrderResult>(`/catalog/items/${itemId}/order`, input, { idempotencyKey }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['tickets'] });
      void qc.invalidateQueries({ queryKey: catalogKeys.popular });
    },
  });
}

// ---------- управление каталогом (ADM) ----------

export function useSaveCategory() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, version, input }: { id?: string; version?: number; input: Partial<Category> }) =>
      id
        ? api.put<Category>(`/catalog/categories/${id}`, input, { ifMatch: etagOf(version ?? 0) })
        : api.post<Category>('/catalog/categories', input, { idempotencyKey: newIdempotencyKey() }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['catalog', 'categories'] }),
  });
}

export function useSaveItem() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, version, input }: { id?: string; version?: number; input: ItemInput }) =>
      id
        ? api.put<ItemDetail>(`/catalog/items/${id}`, input, { ifMatch: etagOf(version ?? 0) })
        : api.post<ItemDetail>('/catalog/items', input, { idempotencyKey: newIdempotencyKey() }),
    onSuccess: (saved) => {
      qc.setQueryData(catalogKeys.item(saved.id), saved);
      void qc.invalidateQueries({ queryKey: catalogKeys.all });
    },
  });
}

export function useFlows() {
  return useQuery({
    queryKey: catalogKeys.flows,
    queryFn: ({ signal }) => api.get<CursorPage<Flow>>('/catalog/approval-flows', { signal }),
    select: (page) => page.items,
  });
}

export function useSaveFlow() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, version, input }: { id?: string; version?: number; input: FlowInput }) =>
      id
        ? api.put<Flow>(`/catalog/approval-flows/${id}`, input, { ifMatch: etagOf(version ?? 0) })
        : api.post<Flow>('/catalog/approval-flows', input, { idempotencyKey: newIdempotencyKey() }),
    onSuccess: () => qc.invalidateQueries({ queryKey: catalogKeys.flows }),
  });
}

// ---------- согласования ----------

export function useApprovals(tab: 'pending' | 'decided') {
  return useInfiniteQuery({
    queryKey: catalogKeys.approvals(tab),
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) =>
      api.get<CursorPage<Approval>>('/approvals', { query: { tab, cursor: pageParam, limit: 50 }, signal }),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  });
}

export function useApprovalCount() {
  return useQuery({
    queryKey: catalogKeys.approvalCount,
    queryFn: ({ signal }) => api.get<{ pending: number }>('/approvals/count', { signal }),
    select: (r) => r.pending,
    refetchInterval: 60_000,
  });
}

export function useApprovalRoute(ticketId: string | undefined) {
  return useQuery({
    queryKey: catalogKeys.route(ticketId ?? ''),
    queryFn: ({ signal }) => api.get<Route>(`/approvals/routes/${ticketId}`, { signal }),
    enabled: !!ticketId,
    // 404 — у заявки нет согласования: это нормальное состояние, повторять не нужно.
    retry: false,
  });
}

export function useDecide() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, decision, comment }: { id: string; decision: 'approve' | 'reject' | 'request_info'; comment?: string }) =>
      api.post<Approval>(`/approvals/${id}/decision`, { decision, comment }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['approvals'] });
      void qc.invalidateQueries({ queryKey: ['tickets'] });
    },
  });
}

export function useDelegate() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, toUserId, comment }: { id: string; toUserId: string; comment?: string }) =>
      api.post<Approval>(`/approvals/${id}/delegate`, { toUserId, comment }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['approvals'] }),
  });
}
