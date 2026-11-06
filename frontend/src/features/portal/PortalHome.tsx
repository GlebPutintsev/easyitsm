import { Button, Group, Skeleton } from '@mantine/core';
import { IconAlertTriangle, IconCalendarEvent, IconInfoCircle, IconShoppingBag } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import { useMe } from '@/features/auth/api';
import { useApprovalCount, usePopularItems } from '@/features/catalog/api';
import { ServiceIcon } from '@/features/catalog/icons';
import { leadTime } from '@/features/catalog/ItemTile';
import { useActiveAnnouncements } from './announcements';
import { dateTime } from './format';
import { PortalSearch } from './PortalSearch';
import { useRecentTickets } from './requester';
import { TicketHint, TicketStatus } from './ui';
import './portal.css';

/**
 * Главная портала (10.4, PRT-01; макет «Портал: главная»): поиск «Опишите, что случилось» и две главные
 * кнопки, баннеры сбоев и плановых работ, «Требует вашего ответа», популярные услуги, мои заявки справа.
 */
export function PortalHome() {
  const { t } = useTranslation();
  const me = useMe();
  const firstName = me.data?.displayName.trim().split(/\s+/)[0];
  return (
    <>
      <section className="ep-hero">
        <div className="ep-hero-inner">
          {firstName && <div style={{ fontSize: 14, color: 'var(--ei-text-3)' }}>{t('portal.hello', { name: firstName })}</div>}
          <h1 className="ep-display">{t('portal.headline')}</h1>
          <PortalSearch />
          <Group gap="sm" justify="center">
            <Button size="md" h={44} leftSection={<IconAlertTriangle size={18} />} component={Link} to="/portal/report">
              {t('portal.reportProblem')}
            </Button>
            <Button size="md" h={44} variant="default" leftSection={<IconShoppingBag size={18} />} component={Link} to="/portal/catalog">
              {t('portal.orderService')}
            </Button>
          </Group>
        </div>
      </section>

      <div className="ep-page" style={{ gap: 32, paddingTop: 32, paddingBottom: 96 }}>
        <Announcements />
        <div className="ep-columns">
          <div className="ep-main">
            <NeedsYou />
            <Popular />
          </div>
          <aside className="ep-aside">
            <RecentTickets />
          </aside>
        </div>
      </div>
    </>
  );
}

const bannerIcon = { outage: IconAlertTriangle, maintenance: IconCalendarEvent, info: IconInfoCircle } as const;

/** Сбои (warn) и плановые работы (accent) сверху; пока объявлений нет — блок не показывается. */
function Announcements() {
  const { t, i18n } = useTranslation();
  const announcements = useActiveAnnouncements();
  const items = announcements.data?.items ?? [];
  if (!items.length) return null;
  const order = { outage: 0, maintenance: 1, info: 2 } as const;
  return (
    <section style={{ display: 'flex', flexDirection: 'column', gap: 10 }} aria-label={t('portal.announcements')}>
      {[...items]
        .sort((a, b) => order[a.kind] - order[b.kind])
        .map((a) => {
          const Icon = bannerIcon[a.kind];
          return (
            <div key={a.id} className="ep-banner" data-kind={a.kind} role={a.kind === 'outage' ? 'alert' : undefined}>
              <Icon size={20} aria-hidden="true" />
              <div>
                <strong>{a.title}</strong>
                <p>
                  {a.body ? `${a.body} ` : ''}
                  {a.kind === 'maintenance' &&
                    `${dateTime(a.startsAt, i18n.language)}${a.endsAt ? ` — ${dateTime(a.endsAt, i18n.language)}` : ''}`}
                </p>
              </div>
            </div>
          );
        })}
    </section>
  );
}

/** «Требует вашего ответа»: заявки в ожидании заявителя и согласования; если ничего нет — блока нет. */
function NeedsYou() {
  const { t } = useTranslation();
  const waiting = useRecentTickets('waiting', 5);
  const approvals = useApprovalCount();
  const items = waiting.data ?? [];
  const n = approvals.data ?? 0;
  if (!items.length && !n) return null;
  return (
    <section className="ep-section">
      <h2 className="ep-h2">{t('portal.needsYou')}</h2>
      {items.map((x) => {
        const resolved = x.status.category === 'resolved';
        return (
          <div key={x.id} className="ep-needs">
            <div>
              <Group gap={8}>
                <span className="ep-mono">{x.number}</span>
                <TicketStatus status={x.status} />
              </Group>
              <strong>{x.title}</strong>
              <p>{resolved ? t('portal.needs.resolved') : x.pendingReason || t('portal.needs.pending')}</p>
            </div>
            <Button variant="default" component={Link} to={`/portal/tickets/${x.number}${resolved ? '' : '#reply'}`}>
              {resolved ? t('portal.needs.check') : t('portal.needs.reply')}
            </Button>
          </div>
        );
      })}
      {n > 0 && (
        <div className="ep-needs">
          <div>
            <strong>{t('portal.approvalsWaiting', { count: n })}</strong>
            <p>{t('portal.needs.approvalsHint')}</p>
          </div>
          <Button variant="default" component={Link} to="/portal/approvals">
            {t('portal.needs.decide')}
          </Button>
        </div>
      )}
    </section>
  );
}

function Popular() {
  const { t } = useTranslation();
  const popular = usePopularItems();
  const items = popular.data ?? [];
  if (!popular.isPending && !popular.error && !items.length) return null;
  return (
    <section className="ep-section">
      <div className="ep-section-head">
        <h2 className="ep-h2">{t('portal.popular')}</h2>
        <Link className="ep-link" to="/portal/catalog">
          {t('portal.allServices')}
        </Link>
      </div>
      {popular.isPending ? (
        <div className="ep-tiles" aria-busy="true">
          {Array.from({ length: 4 }, (_, i) => (
            <Skeleton key={i} height={120} radius="lg" />
          ))}
        </div>
      ) : popular.error ? (
        <ListState state="error" error={popular.error} onRetry={() => void popular.refetch()} />
      ) : (
        <div className="ep-tiles">
          {items.map((i) => (
            <Link key={i.id} className="ep-tile" to={`/portal/catalog/${i.id}`}>
              <ServiceIcon name={i.icon} kind={i.kind} />
              <b>{i.name}</b>
              <span>{i.leadTimeHours != null ? t('portal.leadTime', { time: leadTime(t, i.leadTimeHours) }) : ' '}</span>
            </Link>
          ))}
        </div>
      )}
    </section>
  );
}

function RecentTickets() {
  const { t } = useTranslation();
  const recent = useRecentTickets(undefined, 5);
  const items = recent.data ?? [];
  const state = listStateOf(recent, items.length);
  return (
    <section className="ep-card ep-mine">
      <div className="ep-section-head" style={{ marginBottom: 4 }}>
        <h2 className="ep-h2">{t('nav.myTickets')}</h2>
        <Link className="ep-link" to="/portal/tickets">
          {t('portal.allTickets')}
        </Link>
      </div>
      {state ? (
        <div style={{ padding: '8px 0 12px' }}>
          <ListState
            state={state}
            title={state === 'empty' ? t('portal.noTickets') : undefined}
            error={recent.error}
            onRetry={() => void recent.refetch()}
            rows={3}
          />
        </div>
      ) : (
        items.map((x) => (
          <Link key={x.id} className="ep-row" to={`/portal/tickets/${x.number}`}>
            <b>{x.title}</b>
            <TicketStatus status={x.status} />
            <small className="ep-mono">{x.number}</small>
            <small>
              <TicketHint ticket={x} />
            </small>
          </Link>
        ))
      )}
    </section>
  );
}
