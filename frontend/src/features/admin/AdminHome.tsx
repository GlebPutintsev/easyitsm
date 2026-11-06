import { Anchor, Button, Group, Progress, SimpleGrid, Stack, Text, Title } from '@mantine/core';
import {
  IconBolt,
  IconBellCog,
  IconBook2,
  IconClipboardList,
  IconFileImport,
  IconHistory,
  IconListDetails,
  IconMail,
  IconPlugConnected,
  IconUsers,
  IconUsersGroup,
  IconWand,
  IconSitemap,
  type Icon,
} from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { hasRole, useMe } from '@/features/auth/api';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import { SETUP_STEPS, useSetup, useVersions } from './api';
import { VersionLine } from './ui/History';
import classes from './ui/admin.module.css';

const SECTIONS: { key: string; to: string; icon: Icon }[] = [
  { key: 'setup', to: '/admin/setup', icon: IconWand },
  { key: 'users', to: '/admin/users', icon: IconUsers },
  { key: 'groups', to: '/admin/groups', icon: IconUsersGroup },
  { key: 'dictionaries', to: '/admin/dictionaries', icon: IconBook2 },
  { key: 'catalog', to: '/admin/catalog', icon: IconClipboardList },
  { key: 'automation', to: '/admin/automation', icon: IconBolt },
  { key: 'changes', to: '/admin/changes', icon: IconSitemap },
  { key: 'mail', to: '/admin/mail', icon: IconMail },
  { key: 'integrations', to: '/admin/integrations', icon: IconPlugConnected },
  { key: 'notificationTemplates', to: '/admin/notification-templates', icon: IconBellCog },
  { key: 'import', to: '/admin/import', icon: IconFileImport },
  { key: 'journal', to: '/admin/journal', icon: IconHistory },
];

/** Главная «Управления»: незавершённый мастер, разделы и последние действия администраторов. */
export function AdminHome() {
  const { t } = useTranslation();
  const me = useMe();
  const isAdmin = hasRole(me.data, 'ADM');
  const canJournal = hasRole(me.data, 'ADM', 'SEC', 'AUD');
  const setup = useSetup(isAdmin);
  const recent = useVersions({}, canJournal);
  const rows = recent.data?.pages[0]?.items.slice(0, 8) ?? [];
  const done = setup.data ? SETUP_STEPS.filter((s) => setup.data.steps[s] !== 'pending').length : 0;

  if (me.data && !isAdmin && !canJournal) return <ListState state="forbidden" />;

  return (
    <Stack gap="lg" maw={1200}>
      <Title order={2}>{t('admin.title')}</Title>

      {isAdmin && setup.data && !setup.data.completedAt && (
        <div className={classes.wizardBanner}>
          <Group justify="space-between" align="center" wrap="wrap">
            <Stack gap={4} style={{ flex: 1, minWidth: 240 }}>
              <Text fw={600}>{t('admin.home.wizardTitle')}</Text>
              <Text size="sm">{t('admin.home.wizardText', { done, total: SETUP_STEPS.length })}</Text>
              <Progress value={(done / SETUP_STEPS.length) * 100} aria-label={t('admin.home.wizardProgress')} maw={360} />
            </Stack>
            <Button component={Link} to="/admin/setup">
              {done ? t('admin.home.wizardContinue') : t('admin.home.wizardStart')}
            </Button>
          </Group>
        </div>
      )}

      <SimpleGrid cols={{ base: 1, sm: 2, lg: 4 }} spacing="md">
        {SECTIONS.filter((s) => isAdmin || s.key === 'journal').map((s) => (
          <Link key={s.key} to={s.to} className={classes.tile}>
            <Stack gap={6}>
              <s.icon size={22} stroke={1.6} aria-hidden />
              <Text fw={600}>{t(`admin.sections.${s.key}.title`)}</Text>
              <Text size="sm" className={classes.muted}>
                {t(`admin.sections.${s.key}.text`)}
              </Text>
            </Stack>
          </Link>
        ))}
      </SimpleGrid>

      {canJournal && (
        <Stack gap="sm" className={classes.panel}>
          <Group justify="space-between">
            <Title order={3}>{t('admin.home.recent')}</Title>
            <Anchor component={Link} to="/admin/journal" size="sm">
              <Group gap={4}>
                <IconListDetails size={16} aria-hidden />
                {t('admin.home.allJournal')}
              </Group>
            </Anchor>
          </Group>
          {listStateOf(recent, rows.length) ? (
            <ListState state={listStateOf(recent, rows.length)!} error={recent.error} rows={3}
              title={t('admin.journal.empty')} />
          ) : (
            rows.map((v) => (
              <Group key={v.id} justify="space-between" wrap="nowrap" gap="md">
                <Text size="sm" fw={500} style={{ flex: 1 }}>
                  {v.label || `${v.entity} ${v.entityId}`}
                </Text>
                <VersionLine v={v} />
              </Group>
            ))
          )}
        </Stack>
      )}
    </Stack>
  );
}
