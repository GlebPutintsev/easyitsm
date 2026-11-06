import './i18n';
import { Stack, Tabs, Text, Title } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router';
import { DeliveriesPanel } from './DeliveriesPanel';
import { EventsPanel } from './EventsPanel';
import { SourcesPanel } from './SourcesPanel';
import { WebhooksPanel } from './WebhooksPanel';

/** «Управление → Интеграции» (раздел 8): мониторинг, его журнал, исходящие вебхуки и их доставки. */
export function IntegrationsPage() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const tab = params.get('tab') ?? 'monitoring';
  return (
    <Stack gap="md" maw={1200}>
      <div>
        <Title order={2}>{t('integrations.title')}</Title>
        <Text size="sm" c="dimmed">
          {t('integrations.subtitle')}
        </Text>
      </div>
      <Tabs
        value={tab}
        onChange={(v) => setParams(v && v !== 'monitoring' ? { tab: v } : {}, { replace: true })}
        keepMounted={false}
      >
        <Tabs.List>
          <Tabs.Tab value="monitoring">{t('integrations.tabs.monitoring')}</Tabs.Tab>
          <Tabs.Tab value="events">{t('integrations.tabs.events')}</Tabs.Tab>
          <Tabs.Tab value="webhooks">{t('integrations.tabs.webhooks')}</Tabs.Tab>
          <Tabs.Tab value="deliveries">{t('integrations.tabs.deliveries')}</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="monitoring" pt="md">
          <SourcesPanel />
        </Tabs.Panel>
        <Tabs.Panel value="events" pt="md">
          <EventsPanel sourceId={params.get('source') ?? undefined} />
        </Tabs.Panel>
        <Tabs.Panel value="webhooks" pt="md">
          <WebhooksPanel />
        </Tabs.Panel>
        <Tabs.Panel value="deliveries" pt="md">
          <DeliveriesPanel />
        </Tabs.Panel>
      </Tabs>
    </Stack>
  );
}
