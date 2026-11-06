import { Anchor, Group, Loader, Stack, Text, TextInput } from '@mantine/core';
import { useClickOutside, useDebouncedValue } from '@mantine/hooks';
import { IconAlertTriangle, IconBook, IconSearch, IconShoppingCart, IconTicket } from '@tabler/icons-react';
import type { ReactNode } from 'react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router';
import { Highlight, MIN_QUERY, useGlobalSearch, type SearchHit, type SearchKind } from '@/features/search';
import { StatusBadge, type StatusCategory } from '@/shared/ui/TicketBadges';

const icons: Record<SearchKind, ReactNode> = {
  service: <IconShoppingCart size={16} />,
  article: <IconBook size={16} />,
  ticket: <IconTicket size={16} />,
};
const titles: Record<SearchKind, string> = { service: 'search.services', article: 'search.articles', ticket: 'search.tickets' };

/**
 * Единая строка поиска портала (PRT-02, 10.4): услуги, статьи и свои заявки одним запросом глобального
 * поиска. Права применяет сервер: заявитель видит только свои заявки и статьи своей аудитории.
 * Enter открывает страницу поиска со всеми результатами.
 */
export function PortalSearch() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [q, setQ] = useState('');
  const [open, setOpen] = useState(true);
  const ref = useClickOutside(() => setOpen(false));
  const [debounced] = useDebouncedValue(q.trim(), 250);
  const enabled = debounced.length >= MIN_QUERY;
  const search = useGlobalSearch({ q: debounced, types: ['service', 'article', 'ticket'], limit: 5 }, enabled);
  const groups = (search.data?.groups ?? []).filter((g) => g.items.length > 0);
  const order: SearchKind[] = ['service', 'article', 'ticket'];
  groups.sort((a, b) => order.indexOf(a.kind) - order.indexOf(b.kind));
  const nothing = enabled && !search.isFetching && search.isSuccess && groups.length === 0;

  return (
    <div className="ep-hero-search" ref={ref}>
      <TextInput
        size="lg"
        placeholder={t('portal.searchHint')}
        aria-label={t('portal.searchPlaceholder')}
        leftSection={<IconSearch size={20} />}
        leftSectionWidth={48}
        rightSection={search.isFetching ? <Loader size="xs" /> : null}
        value={q}
        onChange={(e) => {
          setQ(e.currentTarget.value);
          setOpen(true);
        }}
        onFocus={() => setOpen(true)}
        onKeyDown={(e) => {
          if (e.key === 'Escape') setOpen(false);
          if (e.key === 'Enter' && q.trim().length >= MIN_QUERY) navigate(`/portal/search?q=${encodeURIComponent(q.trim())}`);
        }}
      />
      {enabled && open && (
        <div className="ep-card ep-search-results" aria-live="polite">
          <Stack gap="md">
            {search.data?.degraded && (
              <Group gap={6} wrap="nowrap" c="dimmed">
                <IconAlertTriangle size={16} aria-hidden="true" />
                <Text size="xs">{t('globalSearch.degraded')}</Text>
              </Group>
            )}
            {groups.map((g) => (
              <Section key={g.kind} icon={icons[g.kind]} title={t(titles[g.kind])}>
                {g.items.map((x) => (
                  <Hit key={x.id} hit={x} />
                ))}
              </Section>
            ))}
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

function Hit({ hit }: { hit: SearchHit }) {
  if (hit.kind === 'ticket') {
    return (
      <Group gap="xs" wrap="nowrap" justify="space-between">
        <Anchor component={Link} to={`/portal/tickets/${hit.ref ?? hit.id}`} size="sm" lineClamp={1}>
          <span className="ep-mono">{hit.ref}</span> <Highlight segments={hit.title} />
        </Anchor>
        {hit.statusCategory && <StatusBadge category={hit.statusCategory as StatusCategory} />}
      </Group>
    );
  }
  const to = hit.kind === 'article' ? `/portal/kb/${hit.id}` : `/portal/catalog/${hit.id}`;
  return (
    <Anchor component={Link} to={to} size="sm">
      <Highlight segments={hit.title} />
    </Anchor>
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
