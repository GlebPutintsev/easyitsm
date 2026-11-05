import {
  ActionIcon,
  Burger,
  Button,
  Group,
  Indicator,
  Menu,
  Text,
  TextInput,
  Tooltip,
  useMantineColorScheme,
} from '@mantine/core';
import {
  IconBell,
  IconChecklist,
  IconChevronDown,
  IconLanguage,
  IconPlus,
  IconSearch,
  IconSunMoon,
  IconUserCircle,
} from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import { spaces, type SpaceId } from './spaces';

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
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const { colorScheme, setColorScheme } = useMantineColorScheme();

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

      <TextInput
        id="global-search"
        placeholder={t('topbar.searchPlaceholder')}
        leftSection={<IconSearch size={16} />}
        rightSection={<Text size="xs" c="dimmed">/</Text>}
        aria-label={t('topbar.search')}
        style={{ flex: 1, maxWidth: 520 }}
        visibleFrom="xs"
      />

      <Group gap={6} wrap="nowrap">
        <Menu position="bottom-end">
          <Menu.Target>
            <Button leftSection={<IconPlus size={16} />} size="sm" visibleFrom="sm">
              {t('topbar.create')}
            </Button>
          </Menu.Target>
          <Menu.Dropdown>
            {(['incident', 'request', 'change', 'problem', 'article'] as const).map((kind) => (
              <Menu.Item key={kind}>{t(`create.${kind}`)}</Menu.Item>
            ))}
          </Menu.Dropdown>
        </Menu>

        <Tooltip label={t('topbar.approvals')}>
          <ActionIcon variant="subtle" size="lg" aria-label={t('topbar.approvals')}>
            <IconChecklist size={20} />
          </ActionIcon>
        </Tooltip>
        <Tooltip label={t('topbar.notifications')}>
          <Indicator disabled size={8} offset={6}>
            <ActionIcon variant="subtle" size="lg" aria-label={t('topbar.notifications')}>
              <IconBell size={20} />
            </ActionIcon>
          </Indicator>
        </Tooltip>

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

        <Menu position="bottom-end">
          <Menu.Target>
            <ActionIcon variant="subtle" size="lg" aria-label={t('topbar.profile')}>
              <IconUserCircle size={22} />
            </ActionIcon>
          </Menu.Target>
          <Menu.Dropdown>
            <Menu.Label>{t('topbar.profile')}</Menu.Label>
            <Menu.Item
              leftSection={<IconSunMoon size={16} />}
              onClick={() => setColorScheme(colorScheme === 'dark' ? 'light' : 'dark')}
            >
              {t('topbar.toggleTheme')}
            </Menu.Item>
            <Menu.Item
              leftSection={<IconLanguage size={16} />}
              onClick={() => void i18n.changeLanguage(i18n.language === 'ru' ? 'en' : 'ru')}
            >
              {t('topbar.language')}
            </Menu.Item>
          </Menu.Dropdown>
        </Menu>
      </Group>
    </Group>
  );
}
