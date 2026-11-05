import { useQuery } from '@tanstack/react-query';
import { api } from '@/shared/api/client';

/**
 * Имена пользователей. Справочник даёт модуль identity (GET /users/{id}). Пока его нет,
 * показывается сокращённый идентификатор, а интерфейс остаётся рабочим.
 */
export interface UserSummary {
  id: string;
  displayName: string;
  login?: string;
  email?: string;
}

export function shortId(id: string): string {
  return `${id.slice(0, 4)}…${id.slice(-4)}`;
}

export function useUserName(id: string | null | undefined): string {
  const q = useQuery({
    queryKey: ['users', id],
    queryFn: ({ signal }) => api.get<UserSummary>(`/users/${id}`, { signal }),
    enabled: !!id,
    staleTime: 10 * 60_000,
    retry: false,
  });
  if (!id) return '';
  return q.data?.displayName ?? shortId(id);
}

export function useUserSearch(q: string) {
  return useQuery({
    queryKey: ['users', 'search', q],
    queryFn: ({ signal }) => api.get<{ items: UserSummary[] }>('/users', { query: { q, limit: 10 }, signal }),
    enabled: q.trim().length >= 2,
    staleTime: 60_000,
    retry: false,
    select: (r) => r.items,
  });
}

export const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
