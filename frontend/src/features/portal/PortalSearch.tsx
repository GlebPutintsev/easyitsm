import { Anchor, Group, Loader, Paper, Stack, Text, TextInput } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { IconBook, IconSearch, IconShoppingCart, IconTicket } from '@tabler/icons-react';
import { useQuery } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router';
import { useArticleSuggestions } from '@/features/knowledge';
import { api, type CursorPage } from '@/shared/api/client';
import type { ItemCard } from '@/features/catalog/api';
import { StatusBadge } from './StatusBadge';
import type { TicketListItem } from './tickets';

/**
 * Единая строка поиска портала (PRT-02, 10.4): услуги, статьи и свои заявки, результаты разделены.
 * Каждый источник ищется отдельно: если один недоступен, остальные всё равно показываются.
 */
export function PortalSearch() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [q, setQ] = useState('');
  const [debounced] = useDebouncedValue(q.trim(), 250);
  const enabled = debounced.length >= 2;

  const services = useQuery({
    queryKey: ['search', 'services', debounced],
    queryFn: ({ signal }) => api.get<CursorPage<ItemCard>>('/catalog/items', { query: { q: debounced, limit: 5 }, signal }),
    enabled,
    retry: false,
  });
  const articles = useArticleSuggestions(enabled ? debounced : '');
  const tickets = useQuery({
    queryKey: ['search', 'tickets', debounced],
    queryFn: ({ signal }) => api.get<CursorPage<TicketListItem>>('/tickets', { query: { scope: 'mine', q: debounced, limit: 5 }, signal }),
    enabled,
    retry: false,
  });

  const loading = services.isFetching || articles.isFetching || tickets.isFetching;
  const s = services.data?.items ?? [];
  const a = enabled ? (articles.data ?? []) : [];
  const tk = tickets.data?.items ?? [];
  const nothing = enabled && !loading && !s.length && !a.length && !tk.length;

  return (
    <Stack gap="xs">
      <TextInput
        size="lg"
        radius="xl"
        placeholder={t('portal.searchPlaceholder')}
        aria-label={t('portal.searchPlaceholder')}
        leftSection={<IconSearch size={20} />}
        rightSection={loading ? <Loader size="xs" /> : null}
        value={q}
        onChange={(e) => setQ(e.currentTarget.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && q.trim()) navigate(`/portal/catalog?q=${encodeURIComponent(q.trim())}`);
        }}
      />
      {enabled && (
        <Paper withBorder p="md" radius="md" aria-live="polite">
          <Stack gap="md">
            {s.length > 0 && (
              <Section icon={<IconShoppingCart size={16} />} title={t('search.services')}>
                {s.map((i) => (
                  <Anchor key={i.id} component={Link} to={`/portal/catalog/${i.id}`} size="sm">
                    {i.name}
                  </Anchor>
                ))}
              </Section>
            )}
            {a.length > 0 && (
              <Section icon={<IconBook size={16} />} title={t('search.articles')}>
                {a.map((x) => (
                  <Anchor key={x.id} component={Link} to={`/portal/kb/${x.id}`} size="sm">
                    {x.title}
                  </Anchor>
                ))}
              </Section>
            )}
            {tk.length > 0 && (
              <Section icon={<IconTicket size={16} />} title={t('search.tickets')}>
                {tk.map((x) => (
                  <Group key={x.id} gap="xs" wrap="nowrap">
                    <Anchor component={Link} to={`/portal/tickets/${x.id}`} size="sm" lineClamp={1}>
                      {x.number} · {x.title}
                    </Anchor>
                    <StatusBadge status={x.status} />
                  </Group>
                ))}
              </Section>
            )}
            {nothing && (
              <Stack gap={4}>
                <Text size="sm" c="dimmed">
                  {t('search.nothing')}
                </Text>
                <Anchor component={Link} to="/portal/report" size="sm">
                  {t('search.report')}
                </Anchor>
              </Stack>
            )}
          </Stack>
        </Paper>
      )}
    </Stack>
  );
}

function Section({ icon, title, children }: { icon: ReactNode; title: string; children: ReactNode }) {
  return (
    <Stack gap={4}>
      <Group gap={6} c="dimmed">
        {icon}
        <Text size="xs" tt="uppercase" fw={700}>
          {title}
        </Text>
      </Group>
      {children}
    </Stack>
  );
}
