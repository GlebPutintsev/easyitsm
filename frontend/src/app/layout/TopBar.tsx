import {
  ActionIcon,
  Box,
  Burger,
  Button,
  Group,
  Menu,
  Text,
  UnstyledButton,
  useMantineColorScheme,
} from '@mantine/core';
import {
  IconChevronDown,
  IconLanguage,
  IconLogout,
  IconPlus,
  IconShieldLock,
  IconSunMoon,
  IconUserCircle,
} from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import { api } from '@/shared/api/client';
import { clearPeopleCache, initials } from '@/shared/api/people';
import { useMe } from '@/features/auth/api';
import { ApprovalsButton } from '@/features/catalog/ApprovalsPage';
import { GlobalSearchBox } from '@/features/search';
import { NotificationBell } from '@/features/notifications/NotificationBell';
import { spaces, type SpaceId } from './spaces';

/** Куда ведут пункты «+ Создать». Остальные пункты подключат модули изменений, проблем и знаний. */
const createTarget: Record<string, string | undefined> = {
  incident: '/portal/report',
  request: '/portal/catalog',
  article: '/agent/kb/new',
};

interface Props {
  space: SpaceId;
  navOpened: boolean;
  onToggleNav: () => void;
}

/**
 * Верхняя панель, общая для всех пространств (10.2).
 * TODO(волна 1): счётчики уведомлений и согласований, индикатор крупных инцидентов, пункты по правам.
 */
export function TopBar({ space, navOpened, onToggleNav }: Props) {
  const { t } = useTranslation();
  const navigate = useNavigate();

  return (
    <Group h="100%" px="md" justify="space-between" wrap="nowrap" gap="sm">
      <Group gap="sm" wrap="nowrap">
        <Burger opened={navOpened} onClick={onToggleNav} hiddenFrom="sm" size="sm" aria-label={t('topbar.menu')} />
        <Text
          fw={700}
          size="lg"
          component="a"
          c="var(--mantine-color-text)"
          td="none"
          href={spaces[space].home}
          onClick={(e) => {
            e.preventDefault();
            navigate(spaces[space].home);
          }}
        >
          Easy ITSM
        </Text>
      </Group>

      <Box visibleFrom="xs" style={{ flex: 1, maxWidth: 560, display: 'flex' }}>
        <GlobalSearchBox space={space === 'portal' ? 'portal' : 'agent'} />
      </Box>

      <Group gap={6} wrap="nowrap">
        <Menu position="bottom-end">
          <Menu.Target>
            <Button leftSection={<IconPlus size={16} />} size="sm" visibleFrom="sm">
              {t('topbar.create')}
            </Button>
          </Menu.Target>
          <Menu.Dropdown>
            {(['incident', 'request', 'change', 'problem', 'article'] as const).map((kind) => (
              <Menu.Item key={kind} onClick={() => createTarget[kind] && navigate(createTarget[kind])}>
                {t(`create.${kind}`)}
              </Menu.Item>
            ))}
          </Menu.Dropdown>
        </Menu>

        <ApprovalsButton />
        <NotificationBell />

        <SpaceSwitcher space={space} />
        <ProfileMenu />
      </Group>
    </Group>
  );
}

/** Переключатель пространств: портал, рабочее место, управление. */
export function SpaceSwitcher({ space }: { space: SpaceId }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  return (
    <Menu position="bottom-end">
      <Menu.Target>
        <Button variant="subtle" size="sm" rightSection={<IconChevronDown size={14} />} visibleFrom="md">
          {t(`spaces.${space}`)}
        </Button>
      </Menu.Target>
      <Menu.Dropdown>
        {(Object.keys(spaces) as SpaceId[]).map((id) => (
          <Menu.Item key={id} onClick={() => navigate(spaces[id].home)}>
            {t(`spaces.${id}`)}
          </Menu.Item>
        ))}
      </Menu.Dropdown>
    </Menu>
  );
}

/** Меню профиля: безопасность, тема, язык, выход. Кнопка — иконка или аватар с инициалами (портал). */
export function ProfileMenu({ avatar }: { avatar?: boolean }) {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const { colorScheme, setColorScheme } = useMantineColorScheme();
  const me = useMe();
  const queryClient = useQueryClient();
  const name = me.data?.displayName;

  const logout = async () => {
    try {
      await api.post('/auth/logout', {});
    } finally {
      queryClient.clear();
      clearPeopleCache();
      navigate('/login', { replace: true });
    }
  };

  return (
    <Menu position="bottom-end">
      <Menu.Target>
        {avatar ? (
          <UnstyledButton className="ep-avatar" aria-label={name ? `${t('topbar.profile')}: ${name}` : t('topbar.profile')}>
            {name ? initials(name) : <IconUserCircle size={20} />}
          </UnstyledButton>
        ) : (
          <ActionIcon variant="subtle" size="lg" aria-label={t('topbar.profile')}>
            <IconUserCircle size={22} />
          </ActionIcon>
        )}
      </Menu.Target>
      <Menu.Dropdown>
        <Menu.Label>{name ?? t('topbar.profile')}</Menu.Label>
        <Menu.Item leftSection={<IconShieldLock size={16} />} onClick={() => navigate('/profile/security')}>
          {t('security.title')}
        </Menu.Item>
        <Menu.Item leftSection={<IconSunMoon size={16} />} onClick={() => setColorScheme(colorScheme === 'dark' ? 'light' : 'dark')}>
          {t('topbar.toggleTheme')}
        </Menu.Item>
        <Menu.Item
          leftSection={<IconLanguage size={16} />}
          onClick={() => void i18n.changeLanguage(i18n.language === 'ru' ? 'en' : 'ru')}
        >
          {t('topbar.language')}
        </Menu.Item>
        <Menu.Divider />
        <Menu.Item leftSection={<IconLogout size={16} />} color="red" onClick={() => void logout()}>
          {t('auth.logout')}
        </Menu.Item>
      </Menu.Dropdown>
    </Menu>
  );
}
