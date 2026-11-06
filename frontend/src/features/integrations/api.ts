import './i18n';
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, etagOf, newIdempotencyKey, type CursorPage } from '@/shared/api/client';

/** Типы и запросы «Интеграции → Мониторинг» (/api/v1/integrations/monitoring). */

export type SourceKind = 'zabbix' | 'alertmanager' | 'generic';
export type Severity = 'critical' | 'high' | 'warning' | 'info';
export type Level = 'high' | 'medium' | 'low';
export const SEVERITIES: Severity[] = ['critical', 'high', 'warning', 'info'];

export interface Impact {
  impact: Level;
  urgency: Level;
}

export interface MonitoringSource {
  id: string;
  name: string;
  kind: SourceKind;
  tokenHint: string;
  enabled: boolean;
  requesterId: string;
  groupId: string | null;
  minSeverity: Severity;
  severityMap: Record<Severity, Impact>;
  autoResolve: boolean;
  resolutionCode: string;
  lastEventAt: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface SourceView {
  source: MonitoringSource;
  requesterName: string | null;
  groupName: string | null;
}

export interface WithToken {
  source: SourceView;
  token: string;
}

export interface SourceInput {
  name?: string;
  kind?: SourceKind;
  enabled?: boolean;
  requesterId?: string | null;
  groupId?: string | null;
  clearGroup?: boolean;
  minSeverity?: Severity;
  severityMap?: Record<Severity, Impact>;
  autoResolve?: boolean;
  resolutionCode?: string;
}

export interface ActiveAlert {
  id: string;
  fingerprint: string;
  ticketNumber: string;
  ticketId: string;
  severity: Severity;
  title: string;
  occurrences: number;
  firstAt: string;
  lastAt: string;
}

export type Outcome = 'created' | 'updated' | 'escalated' | 'reopened' | 'resolved' | 'ignored' | 'error';
export const OUTCOMES: Outcome[] = ['created', 'updated', 'escalated', 'reopened', 'resolved', 'ignored', 'error'];

export interface EventEntry {
  id: string;
  sourceId: string;
  receivedAt: string;
  fingerprint: string | null;
  alertStatus: 'firing' | 'resolved' | null;
  severity: Severity | null;
  title: string | null;
  outcome: Outcome;
  ticketId: string | null;
  ticketNumber: string | null;
  detail: string | null;
}

export interface Option {
  id: string;
  name: string;
}

const keys = {
  sources: ['integrations', 'monitoring', 'sources'] as const,
  alerts: (id: string) => ['integrations', 'monitoring', 'alerts', id] as const,
  events: (sourceId?: string, outcome?: string) => ['integrations', 'monitoring', 'events', sourceId, outcome] as const,
};

const BASE = '/integrations/monitoring';

export function useSources() {
  return useQuery({
    queryKey: keys.sources,
    queryFn: ({ signal }) => api.get<CursorPage<SourceView>>(`${BASE}/sources`, { signal }),
    select: (page) => page.items,
  });
}

export function useCreateSource() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input: SourceInput) =>
      api.post<WithToken>(`${BASE}/sources`, input, { idempotencyKey: newIdempotencyKey() }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: keys.sources }),
  });
}

export function useUpdateSource() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, version, input }: { id: string; version: number; input: SourceInput }) =>
      api.patch<SourceView>(`${BASE}/sources/${id}`, input, { ifMatch: etagOf(version) }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: keys.sources }),
  });
}

export function useRotateToken() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.post<WithToken>(`${BASE}/sources/${id}/token`, {}),
    onSuccess: () => void qc.invalidateQueries({ queryKey: keys.sources }),
  });
}

export function useDeleteSource() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.delete<void>(`${BASE}/sources/${id}`),
    onSuccess: () => void qc.invalidateQueries({ queryKey: keys.sources }),
  });
}

export function useActiveAlerts(id: string | null) {
  return useQuery({
    queryKey: keys.alerts(id ?? ''),
    queryFn: ({ signal }) => api.get<CursorPage<ActiveAlert>>(`${BASE}/sources/${id}/alerts`, { signal }),
    select: (page) => page.items,
    enabled: !!id,
  });
}

export function useEvents(sourceId?: string, outcome?: string) {
  return useInfiniteQuery({
    queryKey: keys.events(sourceId, outcome),
    queryFn: ({ pageParam, signal }) =>
      api.get<CursorPage<EventEntry>>(`${BASE}/events`, {
        query: { sourceId, outcome, cursor: pageParam ?? undefined, limit: 50 },
        signal,
      }),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor,
  });
}

export function useGroups() {
  return useQuery({
    queryKey: ['integrations', 'groups'],
    queryFn: ({ signal }) => api.get<CursorPage<Option>>('/groups', { query: { limit: 200 }, signal }),
    select: (page) => page.items,
    staleTime: 5 * 60_000,
  });
}

/** Активные коды решения (справочник «Управления») для автозакрытия. */
export function useResolutionCodes() {
  return useQuery({
    queryKey: ['integrations', 'resolution-codes'],
    queryFn: ({ signal }) =>
      api.get<{ kind: string; items: { code: string; name: string; active: boolean }[] }[]>('/admin/dictionaries', {
        signal,
      }),
    select: (all) => (all.find((d) => d.kind === 'resolution')?.items ?? []).filter((i) => i.active),
    staleTime: 5 * 60_000,
  });
}

/** Адрес приёма событий для настройки мониторинга. */
export function eventsUrl(): string {
  return `${window.location.origin}/api/v1/integrations/events`;
}

// ---------------------------------------------------------------- исходящие вебхуки

export interface Webhook {
  id: string;
  name: string;
  url: string;
  enabled: boolean;
  lastUsedAt: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface WebhookWithSecret {
  webhook: Webhook;
  secret: string;
}

export interface WebhookInput {
  name?: string;
  url?: string;
  enabled?: boolean;
}

export interface OutboundPolicy {
  allowedHosts: string[];
  version: number;
}

export interface WebhookTestResult {
  ok: boolean;
  status: number | null;
  error: string | null;
  response: string | null;
}

export type DeliveryStatus = 'pending' | 'delivered' | 'dead';
export const DELIVERY_STATUSES: DeliveryStatus[] = ['pending', 'delivered', 'dead'];

export interface Delivery {
  id: string;
  webhookId: string;
  eventType: string;
  status: DeliveryStatus;
  attempts: number;
  nextAttemptAt: string | null;
  lastStatus: number | null;
  lastError: string | null;
  deliveredAt: string | null;
  source: string | null;
  ticketNumber: string | null;
  createdAt: string;
}

const HOOKS = ['integrations', 'webhooks'] as const;
const POLICY = ['integrations', 'outbound-policy'] as const;
const DELIVERIES = ['integrations', 'deliveries'] as const;

export function useWebhooks() {
  return useQuery({
    queryKey: HOOKS,
    queryFn: ({ signal }) => api.get<CursorPage<Webhook>>('/integrations/webhooks', { signal }),
    select: (page) => page.items,
  });
}

function useHookMutation<A, R>(fn: (arg: A) => Promise<R>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: HOOKS });
      void qc.invalidateQueries({ queryKey: ['automation', 'webhooks'] });
    },
  });
}

export function useCreateWebhook() {
  return useHookMutation((input: WebhookInput) =>
    api.post<WebhookWithSecret>('/integrations/webhooks', input, { idempotencyKey: newIdempotencyKey() }),
  );
}

export function useUpdateWebhook() {
  return useHookMutation(({ id, version, input }: { id: string; version: number; input: WebhookInput }) =>
    api.patch<Webhook>(`/integrations/webhooks/${id}`, input, { ifMatch: etagOf(version) }),
  );
}

export function useRotateWebhookSecret() {
  return useHookMutation((id: string) => api.post<WebhookWithSecret>(`/integrations/webhooks/${id}/secret`, {}));
}

export function useDeleteWebhook() {
  return useHookMutation((id: string) => api.delete<void>(`/integrations/webhooks/${id}`));
}

export function useTestWebhook() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.post<WebhookTestResult>(`/integrations/webhooks/${id}/test`, {}),
    onSuccess: () => void qc.invalidateQueries({ queryKey: HOOKS }),
  });
}

export function usePolicy() {
  return useQuery({
    queryKey: POLICY,
    queryFn: ({ signal }) => api.get<OutboundPolicy>('/integrations/outbound-policy', { signal }),
  });
}

export function useSavePolicy() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ version, allowedHosts }: { version: number; allowedHosts: string[] }) =>
      api.put<OutboundPolicy>('/integrations/outbound-policy', { allowedHosts }, { ifMatch: etagOf(version) }),
    onSuccess: (p) => qc.setQueryData(POLICY, p),
  });
}

export function useDeliveries(webhookId?: string, status?: string) {
  return useInfiniteQuery({
    queryKey: [...DELIVERIES, webhookId, status],
    queryFn: ({ pageParam, signal }) =>
      api.get<CursorPage<Delivery>>('/integrations/webhook-deliveries', {
        query: { webhookId, status, cursor: pageParam ?? undefined, limit: 50 },
        signal,
      }),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor,
  });
}

export function useRetryDelivery() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.post<Delivery>(`/integrations/webhook-deliveries/${id}/retry`, {}),
    onSuccess: () => void qc.invalidateQueries({ queryKey: DELIVERIES }),
  });
}

// ---------------------------------------------------------------- мессенджеры

export type MessengerKind = 'telegram' | 'max' | 'slack' | 'teams';
export const MESSENGER_KINDS: MessengerKind[] = ['telegram', 'max', 'slack', 'teams'];
/** Мессенджеры, которым кроме токена бота нужен токен приложения (Slack Socket Mode). */
export const NEEDS_APP_TOKEN: MessengerKind[] = ['slack'];
export const DEFAULT_API: Record<MessengerKind, string> = {
  telegram: 'https://api.telegram.org',
  max: 'https://platform-api.max.ru',
  slack: 'https://slack.com/api',
  teams: '',
};

/** Адрес приёма сообщений Teams: его указывают как Messaging endpoint бота в Azure. */
export function teamsEndpoint(id: string): string {
  return `${window.location.origin}/api/v1/integrations/teams/${id}/messages`;
}
export type DataField = 'title' | 'text' | 'link';
export const DATA_FIELDS: DataField[] = ['title', 'text', 'link'];

export interface Messenger {
  id: string;
  kind: MessengerKind;
  name: string;
  tokenHint: string;
  appTokenHint: string | null;
  baseUrl: string | null;
  botUsername: string | null;
  botUrl: string | null;
  appId: string | null;
  appTenant: string | null;
  enabled: boolean;
  allowTickets: boolean;
  ticketType: 'incident' | 'request';
  groupId: string | null;
  dataFields: DataField[];
  lastPollAt: string | null;
  lastError: string | null;
  lastEventAt: string | null;
  linkedUsers: number;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface MessengerInput {
  kind?: MessengerKind;
  name?: string;
  token?: string;
  appToken?: string;
  appId?: string;
  appTenant?: string;
  baseUrl?: string;
  enabled?: boolean;
  allowTickets?: boolean;
  ticketType?: 'incident' | 'request';
  groupId?: string | null;
  clearGroup?: boolean;
  dataFields?: DataField[];
}

export interface MyMessenger {
  id: string;
  kind: MessengerKind;
  name: string;
  botUsername: string | null;
  linked: boolean;
  externalName: string | null;
  linkedAt: string | null;
}

export interface LinkCode {
  url: string | null;
  code: string;
  /** Что отправить боту вручную: «/start код» или сам код (Slack). */
  command: string;
  expiresAt: string;
}

const BOTS = ['integrations', 'messengers'] as const;
const MY_BOTS = ['integrations', 'my-messengers'] as const;

export function useMessengers() {
  return useQuery({
    queryKey: BOTS,
    queryFn: ({ signal }) => api.get<CursorPage<Messenger>>('/integrations/messengers', { signal }),
    select: (page) => page.items,
  });
}

function useBotMutation<A, R>(fn: (arg: A) => Promise<R>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => void qc.invalidateQueries({ queryKey: BOTS }) });
}

export function useCreateMessenger() {
  return useBotMutation((input: MessengerInput) =>
    api.post<Messenger>('/integrations/messengers', input, { idempotencyKey: newIdempotencyKey() }),
  );
}

export function useUpdateMessenger() {
  return useBotMutation(({ id, version, input }: { id: string; version: number; input: MessengerInput }) =>
    api.patch<Messenger>(`/integrations/messengers/${id}`, input, { ifMatch: etagOf(version) }),
  );
}

export function useDeleteMessenger() {
  return useBotMutation((id: string) => api.delete<void>(`/integrations/messengers/${id}`));
}

export function useCheckMessenger() {
  return useBotMutation((id: string) =>
    api.post<{ ok: boolean; botUsername: string | null; error: string | null }>(`/integrations/messengers/${id}/check`, {}),
  );
}

export function useMyMessengers() {
  return useQuery({
    queryKey: MY_BOTS,
    queryFn: ({ signal }) => api.get<MyMessenger[]>('/integrations/my-messengers', { signal }),
  });
}

export function useLinkCode() {
  return useMutation({ mutationFn: (id: string) => api.post<LinkCode>(`/integrations/my-messengers/${id}/link`, {}) });
}

export function useUnlinkMessenger() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.delete<void>(`/integrations/my-messengers/${id}`),
    onSuccess: () => void qc.invalidateQueries({ queryKey: MY_BOTS }),
  });
}

/** Обновить привязки: после того как пользователь нажал «Старт» в мессенджере. */
export function useRefreshMyMessengers() {
  const qc = useQueryClient();
  return () => void qc.invalidateQueries({ queryKey: MY_BOTS });
}
