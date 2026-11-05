import { Group, Indicator, Text, UnstyledButton } from '@mantine/core';
import { IconChecklist, IconHome, IconListDetails, IconPlus, IconUserCircle, type Icon } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { useLocation, useNavigate } from 'react-router';
import { useApprovalCount } from '@/features/catalog/api';

/** Нижнее меню мобильного режима (10.17): Домой, Заявки, Создать, Согласования, Профиль. */
export function MobileNav() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const approvals = useApprovalCount();
  const items: { key: string; label: string; to: string; icon: Icon; badge?: number }[] = [
    { key: 'home', label: t('nav.home'), to: '/portal', icon: IconHome },
    { key: 'tickets', label: t('nav.myTickets'), to: '/portal/tickets', icon: IconListDetails },
    { key: 'create', label: t('nav.create'), to: '/portal/catalog', icon: IconPlus },
    { key: 'approvals', label: t('nav.approvals'), to: '/portal/approvals', icon: IconChecklist, badge: approvals.data },
    { key: 'profile', label: t('nav.profile'), to: '/portal/profile', icon: IconUserCircle },
  ];
  const active = (to: string) => (to === '/portal' ? pathname === '/portal' : pathname.startsWith(to));
  return (
    <Group component="nav" aria-label={t('nav.label')} h="100%" justify="space-around" wrap="nowrap" gap={0}>
      {items.map(({ key, label, to, icon: I, badge }) => (
        <UnstyledButton
          key={key}
          onClick={() => navigate(to)}
          aria-current={active(to) ? 'page' : undefined}
          style={{ flex: 1, textAlign: 'center', color: active(to) ? 'var(--mantine-primary-color-filled)' : 'var(--mantine-color-dimmed)' }}
          py={6}
        >
          <Indicator disabled={!badge} label={badge} size={14} offset={2} inline>
            <I size={22} />
          </Indicator>
          <Text size="10px" lh={1.2}>
            {label}
          </Text>
        </UnstyledButton>
      ))}
    </Group>
  );
}
