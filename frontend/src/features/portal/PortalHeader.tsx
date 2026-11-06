import { Group } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { Link, NavLink } from 'react-router';
import { hasRole, useMe } from '@/features/auth/api';
import { ApprovalsButton } from '@/features/catalog/ApprovalsPage';
import { NotificationBell } from '@/features/notifications/NotificationBell';
import { ProfileMenu, SpaceSwitcher } from '@/app/layout/TopBar';
import { BrandMark } from '@/shared/ui/BrandMark';
import './portal.css';

/** Роли, у которых кроме портала есть рабочее место или управление. */
const STAFF = ['AGT', 'LEAD', 'MIM', 'CHG', 'PRB', 'KNW', 'CFG', 'AST', 'ANL', 'ADM', 'SEC', 'AUD'];

/**
 * Шапка портала (макет «Адмирал», DESIGN.md «Портал»): тёмно-синяя полоса 64 px, верхняя навигация вместо левого меню —
 * Главная, Каталог услуг, Мои заявки, База знаний. На телефоне разделы в нижнем меню.
 */
export function PortalHeader() {
  const { t } = useTranslation();
  const me = useMe();
  const links = [
    { to: '/portal', label: t('nav.home'), end: true },
    { to: '/portal/catalog', label: t('nav.catalog') },
    { to: '/portal/tickets', label: t('nav.myTickets') },
    { to: '/portal/kb', label: t('portal.knowledge') },
  ];
  return (
    <div className="ep-header">
      <div className="ep-header-inner">
        <Link to="/portal" className="ep-brand">
          <BrandMark />
          {t('portal.brand')}
        </Link>
        <Group component="nav" aria-label={t('portal.navLabel')} className="ep-nav" visibleFrom="sm" wrap="nowrap">
          {links.map((l) => (
            <NavLink key={l.to} to={l.to} end={l.end}>
              {l.label}
            </NavLink>
          ))}
        </Group>
        <div style={{ flex: 1 }} />
        <Group gap={6} wrap="nowrap">
          <ApprovalsButton />
          <NotificationBell />
          {hasRole(me.data, ...STAFF) && <SpaceSwitcher space="portal" />}
          <ProfileMenu avatar />
        </Group>
      </div>
    </div>
  );
}
