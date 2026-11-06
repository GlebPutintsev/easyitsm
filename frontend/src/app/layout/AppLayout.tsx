import { AppShell } from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { useMediaQuery } from '@mantine/hooks';
import { Outlet, useLocation, useMatches } from 'react-router';
import { MobileNav } from '@/features/portal/MobileNav';
import { PortalHeader } from '@/features/portal/PortalHeader';
import { SideNav } from './SideNav';
import { TopBar } from './TopBar';
import { spaceOf } from './spaces';
import { useGlobalHotkeys } from './useGlobalHotkeys';

/**
 * Шаблон страницы (UI-01): верхняя панель, левое меню, содержимое.
 * Портал — без левого меню: верхняя навигация, на телефоне нижнее меню (DESIGN.md, «Портал»).
 * Правая панель контекста задаётся страницей через AppShell.Aside, например карточкой заявки.
 */
export function AppLayout() {
  const [navOpened, { toggle: toggleNav }] = useDisclosure();
  const space = spaceOf(useLocation().pathname);
  useGlobalHotkeys();
  // Нижнее меню портала на телефоне (10.17).
  const mobile = useMediaQuery('(max-width: 48em)') ?? false;
  const portal = space === 'portal';
  const bottomNav = mobile && portal;
  // Экраны портала сами задают поля и ширину (шапка главной во всю ширину), см. handle в router.tsx.
  const bleed = useMatches().some((m) => (m.handle as { bleed?: boolean } | undefined)?.bleed);

  return (
    <AppShell
      // Шапка портала выше рабочей: 64 px по макету «Адмирал».
      header={{ height: portal ? 64 : 56 }}
      navbar={portal ? undefined : { width: 232, breakpoint: 'sm', collapsed: { mobile: !navOpened } }}
      footer={bottomNav ? { height: 60 } : undefined}
      padding={bleed ? 0 : 'md'}
    >
      <AppShell.Header className="ei-chrome">
        {portal ? <PortalHeader /> : <TopBar space={space} navOpened={navOpened} onToggleNav={toggleNav} />}
      </AppShell.Header>
      {!portal && (
        <AppShell.Navbar p="xs" className="ei-chrome">
          <SideNav space={space} />
        </AppShell.Navbar>
      )}
      <AppShell.Main bg="var(--ei-bg)">
        <Outlet />
      </AppShell.Main>
      {bottomNav && (
        <AppShell.Footer>
          <MobileNav />
        </AppShell.Footer>
      )}
    </AppShell>
  );
}
