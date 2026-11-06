import './i18n';
import {
  ActionIcon,
  Box,
  Button,
  Divider,
  Group,
  Indicator,
  Popover,
  ScrollArea,
  Stack,
  Switch,
  Text,
  Tooltip,
  UnstyledButton,
} from '@mantine/core';
import { IconBell, IconCircleFilled, IconPoint } from '@tabler/icons-react';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import { ListState } from '@/shared/ui/ListState';
import {
  type Notification,
  useMarkAllRead,
  useMarkRead,
  useNotifications,
  usePushInfo,
  useUnreadCount,
} from './api';
import { currentPushState, disablePush, enablePush, type PushState } from './push';
import classes from './NotificationBell.module.css';

/** «5 мин назад», «вчера», «3 нояб.». */
function ago(iso: string, locale: string): string {
  const diff = (new Date(iso).getTime() - Date.now()) / 1000;
  const rtf = new Intl.RelativeTimeFormat(locale, { numeric: 'auto', style: 'short' });
  const abs = Math.abs(diff);
  if (abs < 60) return rtf.format(0, 'second');
  if (abs < 3600) return rtf.format(Math.round(diff / 60), 'minute');
  if (abs < 86_400) return rtf.format(Math.round(diff / 3600), 'hour');
  if (abs < 7 * 86_400) return rtf.format(Math.round(diff / 86_400), 'day');
  return new Intl.DateTimeFormat(locale, { day: 'numeric', month: 'short' }).format(new Date(iso));
}

function Item({ n, onOpen }: { n: Notification; onOpen: (n: Notification) => void }) {
  const { t, i18n } = useTranslation();
  const mark = useMarkRead();
  return (
    <Group gap={4} wrap="nowrap" align="flex-start" className={classes.item} data-unread={!n.read || undefined}>
      <UnstyledButton className={classes.itemBody} onClick={() => onOpen(n)}>
        <Text size="sm" fw={n.read ? 400 : 600} lineClamp={2}>
          {n.title}
        </Text>
        {n.body && (
          <Text size="xs" c="dimmed" lineClamp={2}>
            {n.body}
          </Text>
        )}
        <Text size="xs" c="dimmed" mt={2}>
          {ago(n.createdAt, i18n.language)}
        </Text>
      </UnstyledButton>
      <Tooltip label={n.read ? t('notifications.markUnread') : t('notifications.markRead')}>
        <ActionIcon
          variant="subtle"
          size="sm"
          color="gray"
          aria-label={n.read ? t('notifications.markUnread') : t('notifications.markRead')}
          onClick={() => mark.mutate({ id: n.id, read: !n.read })}
        >
          {n.read ? <IconPoint size={14} /> : <IconCircleFilled size={8} className={classes.dot} />}
        </ActionIcon>
      </Tooltip>
    </Group>
  );
}

function PushToggle({ open }: { open: boolean }) {
  const { t } = useTranslation();
  const info = usePushInfo(open);
  const [state, setState] = useState<PushState | undefined>();
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    if (open) void currentPushState().then(setState);
  }, [open]);

  if (!info.data || state === undefined) return null;
  const disabled = !info.data.enabled || !info.data.publicKey;
  const hint = disabled
    ? t('notifications.push.disabled')
    : failed
      ? t('notifications.push.failed')
      : state === 'unsupported'
        ? t('notifications.push.unsupported')
        : state === 'denied'
          ? t('notifications.push.denied')
          : state === 'on'
            ? t('notifications.push.on')
            : t('notifications.push.off');

  const toggle = async (on: boolean) => {
    setBusy(true);
    setFailed(false);
    try {
      setState(on ? await enablePush(info.data!.publicKey!) : await disablePush());
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Box px="sm" py="xs">
      <Switch
        size="sm"
        label={t('notifications.push.label')}
        description={hint}
        checked={state === 'on'}
        disabled={disabled || busy || state === 'unsupported' || state === 'denied'}
        onChange={(e) => void toggle(e.currentTarget.checked)}
      />
    </Box>
  );
}

/** Колокольчик в шапке: счётчик непрочитанных и центр уведомлений (NTF-01). */
export function NotificationBell() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [unreadOnly, setUnreadOnly] = useState(false);
  const count = useUnreadCount();
  const list = useNotifications(unreadOnly, open);
  const markAll = useMarkAllRead();
  const mark = useMarkRead();
  const n = count.data ?? 0;
  const items = list.data?.pages.flatMap((p) => p.items) ?? [];

  const openItem = (item: Notification) => {
    if (!item.read) mark.mutate({ id: item.id, read: true });
    setOpen(false);
    if (item.link) navigate(item.link);
  };

  return (
    <Popover opened={open} onChange={setOpen} position="bottom-end" width={380} shadow="md" withinPortal>
      <Popover.Target>
        <Tooltip label={t('topbar.notifications')} disabled={open}>
          <Indicator disabled={!n} label={n > 99 ? '99+' : n} size={16} offset={6}>
            <ActionIcon
              variant="subtle"
              size="lg"
              aria-label={n ? t('notifications.badge', { count: n }) : t('topbar.notifications')}
              aria-expanded={open}
              onClick={() => setOpen((o) => !o)}
            >
              <IconBell size={20} />
            </ActionIcon>
          </Indicator>
        </Tooltip>
      </Popover.Target>
      <Popover.Dropdown p={0}>
        <Group justify="space-between" px="sm" py="xs" wrap="nowrap">
          <Text fw={600}>{t('notifications.title')}</Text>
          <Button variant="subtle" size="compact-xs" disabled={!n} loading={markAll.isPending} onClick={() => markAll.mutate()}>
            {t('notifications.markAllRead')}
          </Button>
        </Group>
        <Box px="sm" pb="xs">
          <Switch
            size="xs"
            label={t('notifications.unreadOnly')}
            checked={unreadOnly}
            onChange={(e) => setUnreadOnly(e.currentTarget.checked)}
          />
        </Box>
        <Divider />
        <ScrollArea.Autosize mah={420} type="auto">
          {list.isPending ? (
            <Box p="sm">
              <ListState state="loading" rows={4} />
            </Box>
          ) : list.isError ? (
            <Box p="sm">
              <ListState state="error" error={list.error} onRetry={() => void list.refetch()} />
            </Box>
          ) : items.length === 0 ? (
            <Stack gap={4} p="md" align="center" ta="center">
              <Text size="sm" fw={500}>
                {t('notifications.empty')}
              </Text>
              <Text size="xs" c="dimmed">
                {t('notifications.emptyHint')}
              </Text>
            </Stack>
          ) : (
            <Stack gap={0}>
              {items.map((item) => (
                <Item key={item.id} n={item} onOpen={openItem} />
              ))}
              {list.hasNextPage && (
                <Button
                  variant="subtle"
                  size="xs"
                  m="xs"
                  loading={list.isFetchingNextPage}
                  onClick={() => void list.fetchNextPage()}
                >
                  {t('notifications.more')}
                </Button>
              )}
            </Stack>
          )}
        </ScrollArea.Autosize>
        <Divider />
        <PushToggle open={open} />
      </Popover.Dropdown>
    </Popover>
  );
}
