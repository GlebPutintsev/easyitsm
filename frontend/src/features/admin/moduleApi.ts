import { keepPreviousData, useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, etagOf, newIdempotencyKey, type CursorPage } from '@/shared/api/client';
import { recordVersion } from './api';

/**
 * Настройки других модулей, которые меняет «Управление»: пользователи и группы (identity), графики (sla),
 * категории, статусы и матрица приоритета (tickets). Каждое сохранение записывает версию настройки.
 */

// ---------------------------------------------------------------- пользователи

export interface UserRow {
  id: string;
  login: string;
  email: string | null;
  displayName: string;
  locale: string;
  timezone: string;
  state: 'active' | 'blocked';
  blockedReason: 'admin' | 'source' | null;
  source: 'local' | 'ldap' | 'oidc';
  organizationId: string | null;
  departmentId: string | null;
  managerId: string | null;
  lastLoginAt: string | null;
  createdAt: string;
  version: number;
}

export function useUsers(q: string, state: string | null) {
  return useInfiniteQuery({
    queryKey: ['admin', 'users', q, state],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam, signal }) =>
      api.get<CursorPage<UserRow>>('/users', {
        query: { q: q || undefined, state: state ?? undefined, cursor: pageParam, limit: 50 },
        signal,
      }),
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    placeholderData: keepPreviousData,
  });
}

function userSnapshot(u: UserRow) {
  return { login: u.login, displayName: u.displayName, email: u.email, departmentId: u.departmentId, state: u.state };
}

export interface InviteInput {
  login: string;
  displayName: string;
  email: string;
  departmentId: string | null;
}

export function useUserMutations() {
  const qc = useQueryClient();
  const refresh = () => void qc.invalidateQueries({ queryKey: ['admin', 'users'] });
  return {
    /** «Пригласить»: пользователь без пароля и письмо со ссылкой задать пароль. */
    invite: useMutation({
      mutationFn: async (v: InviteInput & { tenant?: string }) => {
        const user = await api.post<UserRow>(
          '/users',
          { login: v.login, displayName: v.displayName, email: v.email || undefined, departmentId: v.departmentId ?? undefined },
          { idempotencyKey: newIdempotencyKey() },
        );
        await recordVersion('identity.user', user.id, user.displayName, 'create', userSnapshot(user));
        if (v.email) await api.post('/auth/password/forgot', { login: v.login, tenant: v.tenant || undefined });
        return user;
      },
      onSuccess: refresh,
    }),
    update: useMutation({
      mutationFn: async (v: { user: UserRow; departmentId: string | null; displayName: string; email: string }) => {
        const saved = await api.patch<UserRow>(
          `/users/${v.user.id}`,
          { displayName: v.displayName, email: v.email || undefined, departmentId: v.departmentId ?? undefined },
          { ifMatch: etagOf(v.user.version) },
        );
        await recordVersion('identity.user', saved.id, saved.displayName, 'update', userSnapshot(saved));
        return saved;
      },
      onSuccess: refresh,
    }),
    block: useMutation({ mutationFn: (id: string) => api.post<UserRow>(`/users/${id}/block`, undefined), onSuccess: refresh }),
    unblock: useMutation({ mutationFn: (id: string) => api.post<UserRow>(`/users/${id}/unblock`, undefined), onSuccess: refresh }),
    revokeSessions: useMutation({
      mutationFn: (id: string) => api.post<{ revoked: number }>(`/users/${id}/sessions/revoke`, undefined),
    }),
    /** Сервер требует повторного входа (SEC-08): клиент сам покажет окно подтверждения. */
    resetMfa: useMutation({ mutationFn: (id: string) => api.post(`/users/${id}/mfa/reset`, undefined) }),
    sync: useMutation({
      mutationFn: () =>
        api.post<{ users: number; created: number; blocked: number; unblocked: number; groups: number }>(
          '/directory/ldap/sync',
          undefined,
        ),
      onSuccess: refresh,
    }),
  };
}

// ---------------------------------------------------------------- группы

export interface GroupRow {
  id: string;
  name: string;
  description: string;
  type: 'support' | 'security' | 'approval' | 'other';
  source: 'local' | 'ldap';
  updatedAt: string;
  version: number;
}

export interface Member {
  userId: string;
  login: string;
  displayName: string;
  lead: boolean;
  active: boolean;
  addedAt: string;
}

export function useGroups() {
  return useQuery({
    queryKey: ['admin', 'groups'],
    queryFn: ({ signal }) => api.get<CursorPage<GroupRow>>('/groups', { query: { limit: 200 }, signal }),
  });
}

export function useMembers(groupId: string | null) {
  return useQuery({
    queryKey: ['admin', 'groups', groupId, 'members'],
    enabled: !!groupId,
    queryFn: ({ signal }) => api.get<Member[]>(`/groups/${groupId}/members`, { signal }),
  });
}

async function snapshotGroup(groupId: string, label: string, action: 'create' | 'update') {
  const [group, members] = await Promise.all([
    api.get<GroupRow>(`/groups/${groupId}`),
    api.get<Member[]>(`/groups/${groupId}/members`),
  ]);
  await recordVersion('identity.group', groupId, label || group.name, action, {
    name: group.name,
    description: group.description,
    type: group.type,
    members: members.map((m) => ({ id: m.userId, name: m.displayName, lead: m.lead })),
  });
}

export function useGroupMutations() {
  const qc = useQueryClient();
  const refresh = () => void qc.invalidateQueries({ queryKey: ['admin', 'groups'] });
  return {
    save: useMutation({
      mutationFn: async (g: { id?: string; name: string; description: string; type: GroupRow['type']; version?: number }) => {
        const saved = g.id
          ? await api.patch<GroupRow>(`/groups/${g.id}`, { name: g.name, description: g.description, type: g.type }, { ifMatch: etagOf(g.version ?? 0) })
          : await api.post<GroupRow>('/groups', { name: g.name, description: g.description, type: g.type }, { idempotencyKey: newIdempotencyKey() });
        await snapshotGroup(saved.id, saved.name, g.id ? 'update' : 'create');
        return saved;
      },
      onSuccess: refresh,
    }),
    addMember: useMutation({
      mutationFn: async (v: { groupId: string; userId: string; lead: boolean }) => {
        await api.put(`/groups/${v.groupId}/members/${v.userId}`, { lead: v.lead });
        await snapshotGroup(v.groupId, '', 'update');
      },
      onSuccess: refresh,
    }),
    removeMember: useMutation({
      mutationFn: async (v: { groupId: string; userId: string }) => {
        await api.delete(`/groups/${v.groupId}/members/${v.userId}`);
        await snapshotGroup(v.groupId, '', 'update');
      },
      onSuccess: refresh,
    }),
  };
}

// ---------------------------------------------------------------- графики SLA

export type Weekday = 'MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY' | 'FRIDAY' | 'SATURDAY' | 'SUNDAY';
export const WEEKDAYS: Weekday[] = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];

export interface Schedule {
  id: string;
  name: string;
  timezone: string;
  week: Partial<Record<Weekday, string[]>>;
  updatedAt: string;
  version: number;
}

export interface Holiday {
  date: string;
  name: string;
  hours: string[];
}

export function useSchedules() {
  return useQuery({
    queryKey: ['admin', 'schedules'],
    queryFn: ({ signal }) => api.get<CursorPage<Schedule>>('/sla/schedules', { query: { limit: 200 }, signal }),
  });
}

export function useHolidays(scheduleId: string | null, year: number) {
  return useQuery({
    queryKey: ['admin', 'schedules', scheduleId, 'holidays', year],
    enabled: !!scheduleId,
    queryFn: ({ signal }) => api.get<Holiday[]>(`/sla/schedules/${scheduleId}/holidays`, { query: { year }, signal }),
  });
}

export function useScheduleMutations() {
  const qc = useQueryClient();
  const refresh = () => void qc.invalidateQueries({ queryKey: ['admin', 'schedules'] });
  return {
    save: useMutation({
      mutationFn: async (s: Omit<Schedule, 'id' | 'updatedAt' | 'version'> & { id?: string; version?: number }) => {
        const body = { name: s.name, timezone: s.timezone, week: s.week };
        const saved = s.id
          ? await api.patch<Schedule>(`/sla/schedules/${s.id}`, body, { ifMatch: etagOf(s.version ?? 0) })
          : await api.post<Schedule>('/sla/schedules', body, { idempotencyKey: newIdempotencyKey() });
        await recordVersion('sla.schedule', saved.id, saved.name, s.id ? 'update' : 'create', body);
        return saved;
      },
      onSuccess: refresh,
    }),
    holidays: useMutation({
      mutationFn: async (v: { schedule: Schedule; year: number; items: Holiday[] }) => {
        const saved = await api.put<Holiday[]>(`/sla/schedules/${v.schedule.id}/holidays`, v.items, { query: { year: v.year } });
        await recordVersion('sla.holidays', `${v.schedule.id}:${v.year}`, `${v.schedule.name} · ${v.year}`, 'update', { items: saved });
        return saved;
      },
      onSuccess: refresh,
    }),
  };
}

export interface SlaPolicy {
  id: string;
  name: string;
  active: boolean;
}

export function useSlaPolicies(enabled = true) {
  return useQuery({
    queryKey: ['admin', 'sla-policies'],
    enabled,
    queryFn: ({ signal }) => api.get<CursorPage<SlaPolicy>>('/sla/policies', { query: { limit: 50 }, signal }),
  });
}

export function useInstallSlaDefaults() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (timezone: string) => api.post<SlaPolicy>('/sla/setup/defaults', { timezone }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['admin'] }),
  });
}

// ---------------------------------------------------------------- настройки заявок

export interface TicketCategory {
  id: string;
  parentId: string | null;
  name: string;
  ticketTypes: string[];
  active: boolean;
  version: number;
}

export interface WorkflowStatus {
  code: string;
  name: string;
  category: string;
  initial: boolean | null;
}

export interface Workflow {
  type: string;
  statuses: WorkflowStatus[];
  transitions: { from: string; to: string; name: string; roles: string[]; requiredFields: string[]; trigger: string }[];
  version: number;
}

export interface PriorityMatrix {
  cells: Record<string, Record<string, string>>;
  version: number;
}

export function useTicketCategories() {
  return useQuery({
    queryKey: ['admin', 'ticket-categories'],
    queryFn: ({ signal }) => api.get<TicketCategory[]>('/ticket-settings/categories', { signal }),
  });
}

export function useWorkflows() {
  return useQuery({
    queryKey: ['admin', 'workflows'],
    queryFn: ({ signal }) => api.get<Workflow[]>('/ticket-settings/workflows', { signal }),
  });
}

export function usePriorityMatrix() {
  return useQuery({
    queryKey: ['admin', 'priority-matrix'],
    queryFn: ({ signal }) => api.get<PriorityMatrix>('/ticket-settings/priority-matrix', { signal }),
  });
}

export function useTicketSettingsMutations() {
  const qc = useQueryClient();
  return {
    category: useMutation({
      mutationFn: async (c: Omit<TicketCategory, 'id' | 'version'> & { id?: string; version?: number }) => {
        const body = { name: c.name, parentId: c.parentId, ticketTypes: c.ticketTypes, active: c.active };
        const saved = c.id
          ? await api.patch<TicketCategory>(`/ticket-settings/categories/${c.id}`, body, { ifMatch: etagOf(c.version ?? 0) })
          : await api.post<TicketCategory>('/ticket-settings/categories', body);
        await recordVersion('tickets.category', saved.id, saved.name, c.id ? 'update' : 'create', body);
        return saved;
      },
      onSuccess: () => void qc.invalidateQueries({ queryKey: ['admin', 'ticket-categories'] }),
    }),
    workflow: useMutation({
      mutationFn: async (v: { wf: Workflow; restore?: boolean }) => {
        const saved = await api.put<Workflow>(`/ticket-settings/workflows/${v.wf.type}`, { ...v.wf, version: undefined }, { ifMatch: etagOf(v.wf.version) });
        await recordVersion('tickets.workflow', saved.type, saved.type, v.restore ? 'restore' : 'update', { statuses: saved.statuses, transitions: saved.transitions });
        return saved;
      },
      onSuccess: () => void qc.invalidateQueries({ queryKey: ['admin', 'workflows'] }),
    }),
    matrix: useMutation({
      mutationFn: async (v: { m: PriorityMatrix; restore?: boolean }) => {
        const saved = await api.put<PriorityMatrix>('/ticket-settings/priority-matrix', { cells: v.m.cells }, { ifMatch: etagOf(v.m.version) });
        await recordVersion('tickets.priority_matrix', '', 'Матрица приоритета', v.restore ? 'restore' : 'update', { cells: saved.cells });
        return saved;
      },
      onSuccess: () => void qc.invalidateQueries({ queryKey: ['admin', 'priority-matrix'] }),
    }),
  };
}

// ---------------------------------------------------------------- каталог (для мастера)

export function useCatalogCategoryCount(enabled = true) {
  return useQuery({
    queryKey: ['admin', 'catalog-categories'],
    enabled,
    queryFn: ({ signal }) => api.get<CursorPage<{ id: string; name: string }>>('/catalog/categories', { query: { all: true }, signal }),
  });
}
