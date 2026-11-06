import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { api } from '@/shared/api/client';

/**
 * Клиент глобального поиска (GET /api/v1/search): заявки, статьи и услуги одной строкой с правами
 * текущего пользователя. Сервер ищет в OpenSearch или, если он недоступен, в PostgreSQL и сообщает
 * об этом флагом degraded.
 */

export type SearchKind = 'ticket' | 'article' | 'service';

/** Кусок текста, hl — совпадение со строкой поиска. Разметка не приходит с сервера, только данные. */
export interface Segment {
  text: string;
  hl: boolean;
}

export interface SearchHit {
  kind: SearchKind;
  id: string;
  /** INC-000123, KB-42; у услуги null */
  ref: string | null;
  title: Segment[];
  snippet: Segment[];
  ticketType: string | null;
  status: string | null;
  statusCategory: string | null;
  priority: string | null;
  updatedAt: string | null;
}

export interface SearchGroup {
  kind: SearchKind;
  items: SearchHit[];
  /** Сколько найдено всего, если движок знает (OpenSearch) */
  total: number | null;
}

export interface SearchResponse {
  engine: 'opensearch' | 'postgres';
  /** OpenSearch настроен, но недоступен: результаты из резервного поиска */
  degraded: boolean;
  groups: SearchGroup[];
}

export interface SearchParams {
  q: string;
  types?: SearchKind[];
  ticketType?: string;
  state?: 'active' | 'closed';
  limit?: number;
}

export const MIN_QUERY = 2;

export const searchKeys = {
  all: ['global-search'] as const,
  query: (p: SearchParams) => [...searchKeys.all, p] as const,
};

export function searchAll(p: SearchParams, signal?: AbortSignal) {
  return api.get<SearchResponse>('/search', {
    query: {
      q: p.q,
      types: p.types?.join(','),
      ticketType: p.ticketType,
      state: p.state,
      limit: p.limit,
    },
    signal,
  });
}

export function useGlobalSearch(p: SearchParams, enabled = true) {
  const q = p.q.trim();
  return useQuery({
    queryKey: searchKeys.query({ ...p, q }),
    queryFn: ({ signal }) => searchAll({ ...p, q }, signal),
    enabled: enabled && q.length >= MIN_QUERY,
    placeholderData: keepPreviousData,
    staleTime: 10_000,
    retry: false,
  });
}

/** Текст без разметки совпадений: для aria-label и заголовков. */
export function plainText(segments: Segment[]): string {
  return segments.map((s) => s.text).join('');
}

/** Куда ведёт результат в пространстве портала или рабочего места. */
export function hitLink(hit: SearchHit, space: 'portal' | 'agent'): string {
  switch (hit.kind) {
    case 'ticket':
      return space === 'agent' ? `/agent/tickets/${hit.ref ?? hit.id}` : `/portal/tickets/${hit.ref ?? hit.id}`;
    case 'article':
      return `/${space}/kb/${hit.id}`;
    case 'service':
      return `/portal/catalog/${hit.id}`;
  }
}
