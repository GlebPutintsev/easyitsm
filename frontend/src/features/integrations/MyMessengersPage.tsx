import './i18n';
import { Badge, Button, Card, Code, Group, Stack, Text, Title } from '@mantine/core';
import { IconBrandSlack, IconBrandTeams, IconBrandTelegram, IconExternalLink, IconMessageCircle } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import { type LinkCode, type MyMessenger, useLinkCode, useMyMessengers, useRefreshMyMessengers, useUnlinkMessenger } from './api';

/** «Профиль → Мессенджеры»: привязка своего аккаунта к ботам Telegram и MAX одноразовой ссылкой. */
export function MyMessengersPage() {
  const { t } = useTranslation();
  const bots = useMyMessengers();
  const list = bots.data ?? [];
  const state = listStateOf(bots, list.length);
  return (
    <Stack gap="md" maw={760}>
      <div>
        <Title order={2}>{t('integrations.my.title')}</Title>
        <Text size="sm" c="dimmed">
          {t('integrations.my.subtitle')}
        </Text>
      </div>
      {state ? (
        <ListState
          state={state}
          title={state === 'empty' ? t('integrations.my.empty') : undefined}
          error={bots.error}
          onRetry={() => void bots.refetch()}
        />
      ) : (
        list.map((b) => <BotCard key={b.id} bot={b} />)
      )}
    </Stack>
  );
}

function BotCard({ bot }: { bot: MyMessenger }) {
  const { t, i18n } = useTranslation();
  const link = useLinkCode();
  const unlink = useUnlinkMessenger();
  const refresh = useRefreshMyMessengers();
  const [code, setCode] = useState<LinkCode | null>(null);
  const Icon = { telegram: IconBrandTelegram, slack: IconBrandSlack, teams: IconBrandTeams, max: IconMessageCircle }[bot.kind];

  return (
    <Card withBorder padding="md" data-testid={`my-bot-${bot.id}`}>
      <Stack gap="sm">
        <Group justify="space-between" wrap="wrap">
          <Group gap="sm">
            <Icon size={22} />
            <div>
              <Text fw={600}>{bot.name}</Text>
              <Text size="xs" c="dimmed">
                {t(`integrations.messengers.kinds.${bot.kind}`)}
                {bot.botUsername && bot.kind !== 'teams' ? ` · @${bot.botUsername}` : ''}
              </Text>
            </div>
          </Group>
          {bot.linked ? (
            <Group gap="xs">
              <Badge color="green" variant="light">
                {bot.externalName ? t('integrations.my.linkedAs', { name: bot.externalName }) : t('integrations.my.linked')}
              </Badge>
              <Button variant="subtle" color="red" size="xs" loading={unlink.isPending} onClick={() => unlink.mutate(bot.id)}>
                {t('integrations.my.unlink')}
              </Button>
            </Group>
          ) : (
            <Group gap="xs">
              <Badge color="gray" variant="light">
                {t('integrations.my.notLinked')}
              </Badge>
              <Button size="xs" loading={link.isPending} onClick={() => link.mutate(bot.id, { onSuccess: setCode })}>
                {t('integrations.my.link')}
              </Button>
            </Group>
          )}
        </Group>
        <ErrorLine error={link.error ?? unlink.error} />
        {code && !bot.linked && (
          <Stack gap="xs" data-testid="link-code">
            <Text size="sm">
              {t(code.command === code.code ? 'integrations.my.codeHintSend' : 'integrations.my.codeHint', {
                time: new Date(code.expiresAt).toLocaleTimeString(i18n.language, { timeStyle: 'short' }),
              })}
            </Text>
            <Code block>{code.command}</Code>
            <Group gap="xs">
              {code.url && (
                <Button component="a" href={code.url} target="_blank" rel="noopener noreferrer" leftSection={<IconExternalLink size={16} />}>
                  {t('integrations.my.openBot')}
                </Button>
              )}
              <Button variant="default" onClick={refresh}>
                {t('integrations.my.done')}
              </Button>
            </Group>
          </Stack>
        )}
      </Stack>
    </Card>
  );
}
