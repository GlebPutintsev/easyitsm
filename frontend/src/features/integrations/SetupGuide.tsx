import { Code, CopyButton, Button, Group, Stack, Table, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { eventsUrl, type SourceKind } from './api';
import { alertmanagerConfig, curlExample, grafanaHint, ZABBIX_PARAMETERS, ZABBIX_SCRIPT } from './snippets';

/** Как подключить источник: адрес, токен и готовые фрагменты настройки для его вида. */
export function SetupGuide({ kind, token }: { kind: SourceKind; token: string | null }) {
  const { t } = useTranslation();
  const url = eventsUrl();
  const shown = token ?? t('integrations.setup.tokenPlaceholder');
  return (
    <Stack gap="xs">
      <Text size="sm" fw={500}>
        {t('integrations.setup.title')}
      </Text>
      <Group gap="xs" wrap="nowrap">
        <Text size="sm">{t('integrations.setup.url')}</Text>
        <Code style={{ overflowWrap: 'anywhere' }}>{url}</Code>
        <Copy value={url} />
      </Group>
      {kind === 'zabbix' && (
        <>
          <Text size="sm">{t('integrations.setup.zabbixSteps')}</Text>
          <Table.ScrollContainer minWidth={420}>
            <Table withTableBorder verticalSpacing={2} fz="xs">
              <Table.Tbody>
                {ZABBIX_PARAMETERS.map(([name, value]) => (
                  <Table.Tr key={name}>
                    <Table.Td ff="monospace">{name}</Table.Td>
                    <Table.Td ff="monospace">{value}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Table.ScrollContainer>
          <Text size="xs" c="dimmed">
            {t('integrations.setup.zabbixMacros')}
          </Text>
          <Snippet value={ZABBIX_SCRIPT} />
        </>
      )}
      {kind === 'alertmanager' && (
        <>
          <Text size="sm">{t('integrations.setup.alertmanagerSteps')}</Text>
          <Snippet value={alertmanagerConfig(url, shown)} />
          <Text size="sm">{t('integrations.setup.grafanaSteps')}</Text>
          <Snippet value={grafanaHint(url)} />
        </>
      )}
      {kind === 'generic' && (
        <>
          <Text size="sm">{t('integrations.setup.genericSteps')}</Text>
          <Snippet value={curlExample(url, shown)} />
        </>
      )}
    </Stack>
  );
}

function Snippet({ value }: { value: string }) {
  return (
    <Stack gap={4}>
      <Code block style={{ maxHeight: 260, overflow: 'auto' }}>
        {value}
      </Code>
      <Group justify="flex-end">
        <Copy value={value} />
      </Group>
    </Stack>
  );
}

function Copy({ value }: { value: string }) {
  const { t } = useTranslation();
  return (
    <CopyButton value={value}>
      {({ copied, copy }) => (
        <Button size="compact-xs" variant="subtle" onClick={copy}>
          {copied ? t('integrations.copied') : t('integrations.copy')}
        </Button>
      )}
    </CopyButton>
  );
}
