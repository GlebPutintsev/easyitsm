import { AppShell } from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { useMediaQuery } from '@mantine/hooks';
import { Outlet, useLocation } from 'react-router';
import { MobileNav } from '@/features/portal/MobileNav';
import { SideNav } from './SideNav';
import { TopBar } from './TopBar';
import { spaceOf } from './spaces';
import { useGlobalHotkeys } from './useGlobalHotkeys';

/**
 * Шаблон страницы (UI-01): верхняя панель, левое меню, содержимое.
 * Правая панель контекста задаётся страницей через AppShell.Aside, например карточкой заявки.
 */
export function AppLayout() {
  const [navOpened, { toggle: toggleNav }] = useDisclosure();
  const space = spaceOf(useLocation().pathname);
  useGlobalHotkeys();
  // Нижнее меню портала на телефоне (10.17).
  const mobile = useMediaQuery('(max-width: 48em)') ?? false;
  const bottomNav = mobile && space === 'portal';

  return (
    <AppShell
      header={{ height: 56 }}
      navbar={{ width: 232, breakpoint: 'sm', collapsed: { mobile: !navOpened } }}
      footer={bottomNav ? { height: 60 } : undefined}
      padding="md"
    >
      <AppShell.Header>
        <TopBar space={space} navOpened={navOpened} onToggleNav={toggleNav} />
      </AppShell.Header>
      <AppShell.Navbar p="xs">
        <SideNav space={space} />
      </AppShell.Navbar>
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
