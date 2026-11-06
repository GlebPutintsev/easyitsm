import { useQuery } from '@tanstack/react-query';
import { api } from './client';

/**
 * Справочник людей модуля identity (GET /api/v1/people). Доступен любому вошедшему пользователю,
 * в отличие от /users, который видит только поддержка. Отдаёт только имя и почту, без ролей и групп.
 *
 * - `?ids=a,b,c` (до 100 id) — в том числе заблокированных (`active: false`), чтобы у старых записей были имена;
 *   неизвестные id в ответ не попадают.
 * - `?q=ив&limit=10` — поиск только среди активных.
 */
export interface Person {
  id: string;
  displayName: string;
  email: string | null;
  active: boolean;
}

const MAX_IDS = 100;
const cache = new Map<string, Promise<Person | null>>();
let queue = new Map<string, { resolve: (p: Person | null) => void }>();
let scheduled = false;

function flush() {
  scheduled = false;
  const batch = queue;
  queue = new Map();
  const ids = [...batch.keys()];
  for (let i = 0; i < ids.length; i += MAX_IDS) {
    const chunk = ids.slice(i, i + MAX_IDS);
    api
      .get<Person[]>('/people', { query: { ids: chunk.join(',') } })
      .then((people) => {
        const byId = new Map(people.map((p) => [p.id, p]));
        for (const id of chunk) batch.get(id)?.resolve(byId.get(id) ?? null);
      })
      .catch(() => {
        // Справочник недоступен: показываем сокращённый id и попробуем снова при следующем запросе.
        for (const id of chunk) {
          cache.delete(id);
          batch.get(id)?.resolve(null);
        }
      });
  }
}

/** Один человек по id. Запросы одного кадра собираются в один вызов `?ids=`, ответы кэшируются. */
export function loadPerson(id: string): Promise<Person | null> {
  const known = cache.get(id);
  if (known) return known;
  const promise = new Promise<Person | null>((resolve) => queue.set(id, { resolve }));
  cache.set(id, promise);
  if (!scheduled) {
    scheduled = true;
    queueMicrotask(flush);
  }
  return promise;
}

/** Несколько людей сразу, для кода вне компонентов (адаптеры). Неизвестных в ответе нет. */
export async function loadPeople(ids: Iterable<string>): Promise<Map<string, Person>> {
  const unique = [...new Set(ids)].filter(Boolean);
  const people = await Promise.all(unique.map(loadPerson));
  return new Map(people.filter((p): p is Person => !!p).map((p) => [p.id, p]));
}

/** Сбросить кэш (выход из системы, тесты). */
export function clearPeopleCache() {
  cache.clear();
}

export function shortId(id: string): string {
  return `${id.slice(0, 4)}…${id.slice(-4)}`;
}

export const peopleKeys = {
  person: (id: string) => ['people', id] as const,
  search: (q: string) => ['people', 'search', q] as const,
};

export function usePerson(id: string | null | undefined) {
  return useQuery({
    queryKey: peopleKeys.person(id ?? ''),
    queryFn: () => loadPerson(id as string),
    enabled: !!id,
    staleTime: 10 * 60_000,
  });
}

/** Имя для показа: пока справочник не ответил или человека нет — сокращённый id. */
export function usePersonName(id: string | null | undefined): string {
  const q = usePerson(id);
  if (!id) return '';
  return q.data?.displayName ?? shortId(id);
}

export function usePeopleSearch(q: string, limit = 10) {
  const trimmed = q.trim();
  return useQuery({
    queryKey: peopleKeys.search(trimmed),
    queryFn: ({ signal }) => api.get<Person[]>('/people', { query: { q: trimmed, limit }, signal }),
    enabled: trimmed.length >= 2,
    staleTime: 60_000,
    retry: false,
  });
}

export const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Инициалы для аватара: «Анна Ковалёва» → «АК». */
export function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  const first = parts[0]?.[0] ?? '?';
  const last = parts.length > 1 ? (parts[parts.length - 1]?.[0] ?? '') : '';
  return (first + last).toUpperCase();
}
