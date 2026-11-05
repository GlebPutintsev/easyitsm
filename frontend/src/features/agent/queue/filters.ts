import type { SavedView, SortDir, SortField, TicketFilter } from '../api/model';

const LIST_KEYS = ['status', 'priority', 'type', 'groupId', 'assignee', 'sla', 'serviceId'] as const;
export type ListFilterKey = (typeof LIST_KEYS)[number];

/**
 * Фильтр живёт в адресе страницы: ссылку на отфильтрованную очередь можно отправить коллеге,
 * а «Назад» в браузере возвращает предыдущий фильтр. Без параметров действует фильтр представления.
 */
export function filterFromParams(params: URLSearchParams, view: SavedView | undefined): TicketFilter {
  const custom = params.get('custom') === '1';
  const base: TicketFilter = custom ? {} : structuredClone(view?.filter ?? {});
  if (custom) {
    for (const key of LIST_KEYS) {
      const v = params.get(key);
      if (v) (base as Record<string, string[]>)[key] = v.split(',');
    }
  }
  const q = params.get('q');
  if (q) base.q = q;
  return base;
}

export function paramsFromFilter(filter: TicketFilter, prev: URLSearchParams): URLSearchParams {
  const next = new URLSearchParams();
  const sort = prev.get('sort');
  if (sort) next.set('sort', sort);
  next.set('custom', '1');
  for (const key of LIST_KEYS) {
    const v = filter[key];
    if (v && v.length) next.set(key, v.join(','));
  }
  if (filter.q) next.set('q', filter.q);
  return next;
}

export function sortFromParams(params: URLSearchParams, view: SavedView | undefined): { field: SortField; dir: SortDir } {
  const raw = params.get('sort');
  if (raw) {
    const [field, dir] = raw.split(':') as [SortField, SortDir];
    return { field, dir: dir === 'desc' ? 'desc' : 'asc' };
  }
  return view?.sort ?? { field: 'sla', dir: 'asc' };
}

/** Сколько условий выбрано (без поиска): показывается на кнопке «Фильтр». */
export function filterCount(filter: TicketFilter): number {
  return LIST_KEYS.reduce((n, key) => n + (filter[key]?.length ? 1 : 0), 0);
}

export function sameFilter(a: TicketFilter, b: TicketFilter): boolean {
  const norm = (f: TicketFilter) =>
    JSON.stringify(LIST_KEYS.map((k) => [...(f[k] ?? [])].sort()).concat([[f.q?.trim() ?? '']]));
  return norm(a) === norm(b);
}

export { LIST_KEYS };
