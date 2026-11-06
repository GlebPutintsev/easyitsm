import { Loader, Popover, Text, TextInput } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { IconSearch } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router';
import { StatusBadge, type StatusCategory } from '@/shared/ui/TicketBadges';
import { hitLink, MIN_QUERY, plainText, useGlobalSearch, type SearchHit } from './api';
import { Highlight } from './Highlight';
import { ResultIcon } from './ResultIcon';
import './i18n';
import './search.css';

interface Props {
  space: 'portal' | 'agent';
}

/**
 * Строка поиска в верхней панели (TCK-15, UI-11 «/»): по мере набора — до пяти результатов каждого вида,
 * Enter — страница поиска со всеми результатами и фильтрами.
 */
export function GlobalSearchBox({ space }: Props) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [q, setQ] = useState('');
  const [open, setOpen] = useState(false);
  const [debounced] = useDebouncedValue(q.trim(), 250);
  const search = useGlobalSearch({ q: debounced, limit: 5 }, open);
  const groups = (search.data?.groups ?? []).filter((g) => g.items.length > 0);
  const ready = debounced.length >= MIN_QUERY;
  const page = `/${space}/search?q=${encodeURIComponent(q.trim())}`;

  const go = (to: string) => {
    setOpen(false);
    navigate(to);
  };

  return (
    <div className="es-box">
      <Popover opened={open && ready} width="target" position="bottom-start" offset={6} shadow="md" trapFocus={false}
        onChange={setOpen} returnFocus={false}>
        <Popover.Target>
          <TextInput
            id="global-search"
            placeholder={t('globalSearch.placeholder')}
            aria-label={t('globalSearch.label')}
            leftSection={<IconSearch size={16} aria-hidden="true" />}
            rightSection={search.isFetching ? <Loader size="xs" /> : <Text size="xs" c="dimmed">/</Text>}
            value={q}
            onChange={(e) => {
              setQ(e.currentTarget.value);
              setOpen(true);
            }}
            onFocus={() => setOpen(true)}
            onBlur={() => window.setTimeout(() => setOpen(false), 150)}
            onKeyDown={(e) => {
              if (e.key === 'Escape') {
                setOpen(false);
                e.currentTarget.blur();
              }
              if (e.key === 'Enter' && q.trim().length >= MIN_QUERY) go(page);
            }}
          />
        </Popover.Target>
        <Popover.Dropdown className="es-dropdown" aria-live="polite" onMouseDown={(e) => e.preventDefault()}>
          {search.data?.degraded && <p className="es-empty">{t('globalSearch.degraded')}</p>}
          {search.isError && <p className="es-empty">{t('globalSearch.error')}</p>}
          {!search.isFetching && search.isSuccess && groups.length === 0 && (
            <p className="es-empty">{t('globalSearch.nothing')}</p>
          )}
          {groups.map((g) => (
            <section key={g.kind} aria-label={t(`globalSearch.kinds.${g.kind}`)}>
              <div className="es-section-title">
                <ResultIcon kind={g.kind} size={14} />
                {t(`globalSearch.kinds.${g.kind}`)}
              </div>
              {g.items.map((hit) => (
                <QuickHit key={hit.id} hit={hit} to={hitLink(hit, space)} onPick={() => setOpen(false)} />
              ))}
            </section>
          ))}
          {groups.length > 0 && (
            <Link className="es-footer" to={page} onClick={() => setOpen(false)}>
              {t('globalSearch.showAll')}
            </Link>
          )}
        </Popover.Dropdown>
      </Popover>
    </div>
  );
}

function QuickHit({ hit, to, onPick }: { hit: SearchHit; to: string; onPick: () => void }) {
  return (
    <Link className="es-item" to={to} onClick={onPick} aria-label={`${hit.ref ?? ''} ${plainText(hit.title)}`.trim()}>
      <span className="es-item-body">
        <span className="es-item-title" style={{ display: 'block' }}>
          {hit.ref && <span className="es-ref">{hit.ref}</span>}
          <Highlight segments={hit.title} />
        </span>
        {hit.snippet.length > 0 && (
          <span className="es-item-snippet">
            <Highlight segments={hit.snippet} />
          </span>
        )}
      </span>
      {hit.statusCategory && <StatusBadge category={hit.statusCategory as StatusCategory} />}
    </Link>
  );
}
