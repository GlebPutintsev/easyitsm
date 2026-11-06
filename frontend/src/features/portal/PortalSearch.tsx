import { Anchor, Group, Loader, Stack, Text, TextInput } from '@mantine/core';
import { useClickOutside, useDebouncedValue } from '@mantine/hooks';
import { IconBook, IconSearch, IconShoppingCart, IconTicket } from '@tabler/icons-react';
import { useQuery } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router';
import { useArticleSuggestions } from '@/features/knowledge';
import { api, type CursorPage } from '@/shared/api/client';
import type { ItemCard } from '@/features/catalog/api';
import { useTicketSearch } from './requester';
import { TicketStatus } from './ui';

/**
 * Единая строка поиска портала (PRT-02, 10.4): услуги, статьи и свои заявки, результаты разделены.
 * Каждый источник ищется отдельно: если один недоступен, остальные всё равно показываются.
 */
export function PortalSearch() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [q, setQ] = useState('');
  const [open, setOpen] = useState(true);
  const ref = useClickOutside(() => setOpen(false));
  const [debounced] = useDebouncedValue(q.trim(), 250);
  const enabled = debounced.length >= 2;

  const services = useQuery({
    queryKey: ['search', 'services', debounced],
    queryFn: ({ signal }) => api.get<CursorPage<ItemCard>>('/catalog/items', { query: { q: debounced, limit: 5 }, signal }),
    enabled,
    retry: false,
  });
  const articles = useArticleSuggestions(enabled ? debounced : '');
  const tickets = useTicketSearch(debounced, enabled);

  const loading = services.isFetching || articles.isFetching || tickets.isFetching;
  const s = services.data?.items ?? [];
  const a = enabled ? (articles.data ?? []) : [];
  const tk = tickets.data ?? [];
  const nothing = enabled && !loading && !s.length && !a.length && !tk.length;

  return (
    <div className="ep-hero-search" ref={ref}>
      <TextInput
        size="lg"
        placeholder={t('portal.searchHint')}
        aria-label={t('portal.searchPlaceholder')}
        leftSection={<IconSearch size={20} />}
        leftSectionWidth={48}
        rightSection={loading ? <Loader size="xs" /> : null}
        value={q}
        onChange={(e) => {
          setQ(e.currentTarget.value);
          setOpen(true);
        }}
        onFocus={() => setOpen(true)}
        onKeyDown={(e) => {
          if (e.key === 'Escape') setOpen(false);
          if (e.key === 'Enter' && q.trim()) navigate(`/portal/catalog?q=${encodeURIComponent(q.trim())}`);
        }}
      />
      {enabled && open && (
        <div className="ep-card ep-search-results" aria-live="polite">
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
                  <Group key={x.id} gap="xs" wrap="nowrap" justify="space-between">
                    <Anchor component={Link} to={`/portal/tickets/${x.number}`} size="sm" lineClamp={1}>
                      <span className="ep-mono">{x.number}</span> {x.title}
                    </Anchor>
                    <TicketStatus status={x.status} />
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
        </div>
      )}
    </div>
  );
}

function Section({ icon, title, children }: { icon: ReactNode; title: string; children: ReactNode }) {
  return (
    <Stack gap={6}>
      <Group gap={6} c="dimmed">
        {icon}
        <Text size="xs" fw={600}>
          {title}
        </Text>
      </Group>
      {children}
    </Stack>
  );
}
