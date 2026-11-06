import { useQuery } from '@tanstack/react-query';
import { api, type CursorPage } from '@/shared/api/client';

export interface Announcement {
  id: string;
  kind: 'info' | 'maintenance' | 'outage';
  title: string;
  body: string;
  startsAt: string;
  endsAt: string | null;
  published: boolean;
  /** Затронутые услуги (в MVP — позиции каталога); пусто — объявление для всех */
  serviceIds: string[];
  version: number;
}

export function useActiveAnnouncements(enabled = true) {
  return useQuery({
    queryKey: ['announcements', 'active'],
    enabled,
    queryFn: ({ signal }) =>
      api.get<CursorPage<Announcement>>('/announcements', { query: { active: true, limit: 20 }, signal }),
  });
}
