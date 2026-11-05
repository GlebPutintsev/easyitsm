import { ThemeIcon } from '@mantine/core';
import {
  IconAlertTriangle,
  IconBriefcase,
  IconCloud,
  IconDeviceDesktop,
  IconDeviceLaptop,
  IconDeviceMobile,
  IconKey,
  IconMail,
  IconNetwork,
  IconPackage,
  IconPrinter,
  IconShieldLock,
  IconUsers,
  IconWifi,
  type Icon,
} from '@tabler/icons-react';

/** Набор иконок для услуг и категорий: администратор выбирает по коду, без загрузки файлов. */
export const SERVICE_ICONS: Record<string, Icon> = {
  laptop: IconDeviceLaptop,
  desktop: IconDeviceDesktop,
  mobile: IconDeviceMobile,
  printer: IconPrinter,
  key: IconKey,
  security: IconShieldLock,
  mail: IconMail,
  network: IconNetwork,
  wifi: IconWifi,
  cloud: IconCloud,
  users: IconUsers,
  work: IconBriefcase,
  package: IconPackage,
  alert: IconAlertTriangle,
};

export function ServiceIcon({ name, kind, size = 36 }: { name?: string | null; kind?: 'request' | 'incident'; size?: number }) {
  const I = (name && SERVICE_ICONS[name]) || (kind === 'incident' ? IconAlertTriangle : IconPackage);
  return (
    <ThemeIcon variant="light" size={size} radius="md" color={kind === 'incident' ? 'orange' : 'blue'} aria-hidden>
      <I size={size * 0.55} />
    </ThemeIcon>
  );
}
