import { NavLink, ScrollArea } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { Link, useLocation } from 'react-router';
import { spaces, type SpaceId } from './spaces';

export function SideNav({ space }: { space: SpaceId }) {
  const { t } = useTranslation();
  const { pathname } = useLocation();
  const items = spaces[space].nav;

  return (
    <ScrollArea component="nav" aria-label={t('nav.label')}>
      {items.map((item) => (
        <NavLink
          key={item.to}
          component={Link}
          to={item.to}
          label={t(`nav.${item.key}`)}
          leftSection={<item.icon size={18} stroke={1.6} />}
          active={item.to === spaces[space].home ? pathname === item.to : pathname.startsWith(item.to)}
        />
      ))}
    </ScrollArea>
  );
}
