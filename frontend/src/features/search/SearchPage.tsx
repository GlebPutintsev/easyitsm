import { Group, SegmentedControl, Select, Stack, TextInput, Title } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { IconAlertTriangle, IconSearch } from '@tabler/icons-react';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router';
import { ListState } from '@/shared/ui/ListState';
import { PriorityBadge, StatusBadge, type Priority, type StatusCategory } from '@/shared/ui/TicketBadges';
import { hitLink, MIN_QUERY, plainText, useGlobalSearch, type SearchHit, type SearchKind } from './api';
import { Highlight } from './Highlight';
import { ResultIcon } from './ResultIcon';
import './i18n';
import './search.css';

type KindTab = 'all' | SearchKind;
const TICKET_TYPES = ['incident', 'request', 'problem', 'change', 'task'] as const;

/**
 * Страница глобального поиска (TCK-15): все виды или один, фильтры заявок по состоянию и типу.
 * Параметры в адресе (?q=&kind=&state=&type=), чтобы результатом можно было поделиться.
 */
export function SearchPage({ space }: { space: 'portal' | 'agent' }) {
  const { t, i18n } = useTranslation();
  const [params, setParams] = useSearchParams();
  const [q, setQ] = useState(params.get('q') ?? '');
  const [debounced] = useDebouncedValue(q.trim(), 300);
  const kind = (params.get('kind') as KindTab) || 'all';
  const state = (params.get('state') as 'active' | 'closed' | null) ?? undefined;
  const ticketType = params.get('type') ?? undefined;

  useEffect(() => {
    if ((params.get('q') ?? '') !== debounced) {
      const next = new URLSearchParams(params);
      if (debounced) next.set('q', debounced);
      else next.delete('q');
      setParams(next, { replace: true });
    }
  }, [debounced, params, setParams]);

  const set = (key: string, value: string | null) => {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    setParams(next, { replace: true });
  };

  const search = useGlobalSearch({
    q: debounced,
    types: kind === 'all' ? undefined : [kind],
    state: kind === 'all' || kind === 'ticket' ? state : undefined,
    ticketType: kind === 'all' || kind === 'ticket' ? ticketType : undefined,
    limit: kind === 'all' ? 10 : 20,
  });
  const groups = search.data?.groups ?? [];
  const hits = groups.flatMap((g) => g.items);
  const counts = Object.fromEntries(groups.map((g) => [g.kind, g.total ?? g.items.length])) as Record<string, number>;
  const date = (iso: string) => new Date(iso).toLocaleDateString(i18n.language);

  return (
    <Stack gap="md" className="es-page">
      <Title order={1}>{t('globalSearch.title')}</Title>
      <TextInput
        size="md"
        autoFocus
        value={q}
        onChange={(e) => setQ(e.currentTarget.value)}
        placeholder={t('globalSearch.placeholder')}
        aria-label={t('globalSearch.label')}
        leftSection={<IconSearch size={18} aria-hidden="true" />}
      />
      <Group gap="sm" wrap="wrap">
        <SegmentedControl
          size="sm"
          value={kind}
          onChange={(v) => set('kind', v === 'all' ? null : v)}
          data={(['all', 'ticket', 'article', 'service'] as const).map((k) => ({
            value: k,
            label: k !== 'all' && counts[k] !== undefined && kind === 'all'
              ? `${t(`globalSearch.kinds.${k}`)} · ${counts[k]}`
              : t(`globalSearch.kinds.${k}`),
          }))}
        />
        {(kind === 'all' || kind === 'ticket') && (
          <>
            <SegmentedControl
              size="sm"
              value={state ?? 'any'}
              onChange={(v) => set('state', v === 'any' ? null : v)}
              data={(['any', 'active', 'closed'] as const).map((s) => ({ value: s, label: t(`globalSearch.state.${s}`) }))}
            />
            <Select
              size="sm"
              w={180}
              aria-label={t('globalSearch.ticketType')}
              placeholder={t('globalSearch.anyType')}
              clearable
              value={ticketType ?? null}
              onChange={(v) => set('type', v)}
              data={TICKET_TYPES.map((v) => ({ value: v, label: t(`globalSearch.types.${v}`) }))}
            />
          </>
        )}
      </Group>

      {search.data?.degraded && (
        <div className="es-notice" role="status">
          <IconAlertTriangle size={18} aria-hidden="true" />
          <span>{t('globalSearch.degraded')}</span>
        </div>
      )}

      {debounced.length < MIN_QUERY ? (
        <p className="es-empty">{t('globalSearch.minChars')}</p>
      ) : search.isError ? (
        <ListState state="error" error={search.error} onRetry={() => void search.refetch()} />
      ) : search.isLoading ? (
        <ListState state="loading" rows={4} />
      ) : hits.length === 0 ? (
        <ListState state="noResults" title={t('globalSearch.nothing')} />
      ) : (
        <div className="es-results" aria-live="polite">
          {hits.map((hit) => (
            <ResultCard key={`${hit.kind}-${hit.id}`} hit={hit} to={hitLink(hit, space)} date={date} />
          ))}
        </div>
      )}
    </Stack>
  );
}

function ResultCard({ hit, to, date }: { hit: SearchHit; to: string; date: (iso: string) => string }) {
  const { t } = useTranslation();
  return (
    <Link className="es-card" to={to} aria-label={`${hit.ref ?? ''} ${plainText(hit.title)}`.trim()}>
      <span className="es-item-icon">
        <ResultIcon kind={hit.kind} />
      </span>
      <span className="es-item-body">
        <span className="es-item-title" style={{ display: 'block' }}>
          {hit.ref && <span className="es-ref">{hit.ref}</span>}
          <Highlight segments={hit.title} />
        </span>
        {hit.snippet.length > 0 && (
          <span className="es-item-snippet" style={{ marginTop: 4 }}>
            <Highlight segments={hit.snippet} />
          </span>
        )}
        <span className="es-meta">
          <span>{t(`globalSearch.kinds.${hit.kind}`)}</span>
          {hit.ticketType && <span>{t(`globalSearch.types.${hit.ticketType}`)}</span>}
          {hit.statusCategory && <StatusBadge category={hit.statusCategory as StatusCategory} />}
          {hit.priority && <PriorityBadge priority={hit.priority as Priority} />}
          {hit.updatedAt && (
            <span>{t(hit.kind === 'article' ? 'globalSearch.published' : 'globalSearch.updated', { date: date(hit.updatedAt) })}</span>
          )}
        </span>
      </span>
    </Link>
  );
}
