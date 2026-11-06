import {
  IconAlertTriangle,
  IconBolt,
  IconBook,
  IconBook2,
  IconBox,
  IconChartBar,
  IconChecklist,
  IconClipboardList,
  IconDeviceDesktop,
  IconFileImport,
  IconFlame,
  IconHistory,
  IconHome,
  IconListDetails,
  IconMail,
  IconBellCog,
  IconSettings,
  IconSitemap,
  IconStack2,
  IconUsers,
  IconUsersGroup,
  IconWand,
  type Icon,
} from '@tabler/icons-react';

export type SpaceId = 'portal' | 'agent' | 'admin';

export interface NavItem {
  /** Ключ перевода в разделе nav */
  key: string;
  to: string;
  icon: Icon;
}

/** Левое меню каждого пространства (10.6). Пункты без права скрываются, но сервер всё равно проверяет доступ. */
export const spaces: Record<SpaceId, { home: string; nav: NavItem[] }> = {
  portal: {
    home: '/portal',
    nav: [
      { key: 'home', to: '/portal', icon: IconHome },
      { key: 'catalog', to: '/portal/catalog', icon: IconClipboardList },
      { key: 'myTickets', to: '/portal/tickets', icon: IconListDetails },
      { key: 'approvals', to: '/portal/approvals', icon: IconChecklist },
      { key: 'knowledge', to: '/portal/kb', icon: IconBook },
    ],
  },
  agent: {
    home: '/agent',
    nav: [
      { key: 'home', to: '/agent', icon: IconHome },
      { key: 'myTickets', to: '/agent/my', icon: IconListDetails },
      { key: 'queues', to: '/agent/queues', icon: IconStack2 },
      { key: 'approvals', to: '/agent/approvals', icon: IconChecklist },
      { key: 'majorIncidents', to: '/agent/major', icon: IconFlame },
      { key: 'problems', to: '/agent/problems', icon: IconAlertTriangle },
      { key: 'changes', to: '/agent/changes', icon: IconSitemap },
      { key: 'knowledge', to: '/agent/kb', icon: IconBook },
      { key: 'cmdb', to: '/agent/cmdb', icon: IconDeviceDesktop },
      { key: 'assets', to: '/agent/assets', icon: IconBox },
      { key: 'reports', to: '/agent/reports', icon: IconChartBar },
    ],
  },
  admin: {
    home: '/admin',
    nav: [
      { key: 'home', to: '/admin', icon: IconSettings },
      { key: 'setup', to: '/admin/setup', icon: IconWand },
      { key: 'users', to: '/admin/users', icon: IconUsers },
      { key: 'groups', to: '/admin/groups', icon: IconUsersGroup },
      { key: 'dictionaries', to: '/admin/dictionaries', icon: IconBook2 },
      { key: 'catalogAdmin', to: '/admin/catalog', icon: IconClipboardList },
      { key: 'automation', to: '/admin/automation', icon: IconBolt },
      { key: 'import', to: '/admin/import', icon: IconFileImport },
      { key: 'journal', to: '/admin/journal', icon: IconHistory },
      { key: 'mail', to: '/admin/mail', icon: IconMail },
      { key: 'notificationTemplates', to: '/admin/notification-templates', icon: IconBellCog },
    ],
  },
};

export function spaceOf(pathname: string): SpaceId {
  if (pathname.startsWith('/agent')) return 'agent';
  if (pathname.startsWith('/admin')) return 'admin';
  return 'portal';
}
