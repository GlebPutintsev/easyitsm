import { Anchor, Button, Group, Stack, Switch, Table, Tabs, Text, Title } from '@mantine/core';
import { IconPlus } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import { type Rule, useRules, useSaveRule } from './api';
import { RunsPanel } from './RunsPanel';

/** «Управление → Автоматизация» (10.16): правила и журнал запусков (AUT-01, AUT-04). */
export function AutomationPage() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const tab = params.get('tab') ?? 'rules';
  return (
    <Stack gap="md" maw={1200}>
      <div>
        <Title order={2}>{t('automation.title')}</Title>
        <Text size="sm" c="dimmed">
          {t('automation.subtitle')}
        </Text>
      </div>
      <Tabs value={tab} onChange={(v) => setParams(v && v !== 'rules' ? { tab: v } : {}, { replace: true })} keepMounted={false}>
        <Tabs.List>
          <Tabs.Tab value="rules">{t('automation.tabs.rules')}</Tabs.Tab>
          <Tabs.Tab value="runs">{t('automation.tabs.runs')}</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="rules" pt="md">
          <RulesPanel />
        </Tabs.Panel>
        <Tabs.Panel value="runs" pt="md">
          <RunsPanel ruleId={params.get('rule') ?? undefined} />
        </Tabs.Panel>
      </Tabs>
    </Stack>
  );
}

function RulesPanel() {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const rules = useRules();
  const list = rules.data ?? [];
  const state = listStateOf(rules, list.length);
  const save = useSaveRule();
  const toggle = (r: Rule, enabled: boolean) => save.mutate({ id: r.id, version: r.version, input: { enabled } });

  return (
    <Stack gap="sm">
      <Group justify="flex-end">
        <Button leftSection={<IconPlus size={16} />} component={Link} to="/admin/automation/rules/new">
          {t('automation.newRule')}
        </Button>
      </Group>
      <ErrorLine error={save.error} />
      {state ? (
        <ListState
          state={state}
          title={state === 'empty' ? t('automation.noRules') : undefined}
          error={rules.error}
          onRetry={() => void rules.refetch()}
          action={state === 'empty' ? <Text size="sm" c="dimmed">{t('automation.noRulesHint')}</Text> : undefined}
        />
      ) : (
        <Table highlightOnHover verticalSpacing="sm">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t('automation.columns.name')}</Table.Th>
              <Table.Th>{t('automation.columns.trigger')}</Table.Th>
              <Table.Th>{t('automation.columns.actions')}</Table.Th>
              <Table.Th>{t('automation.columns.updated')}</Table.Th>
              <Table.Th w={110}>{t('automation.columns.enabled')}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {list.map((r) => (
              <Table.Tr key={r.id} style={{ cursor: 'pointer' }} onClick={() => navigate(`/admin/automation/rules/${r.id}`)}>
                <Table.Td>
                  <Anchor component={Link} to={`/admin/automation/rules/${r.id}`} onClick={(e) => e.stopPropagation()}>
                    {r.name}
                  </Anchor>
                  {r.description && (
                    <Text size="xs" c="dimmed" lineClamp={1}>
                      {r.description}
                    </Text>
                  )}
                </Table.Td>
                <Table.Td>{t(`automation.triggers.${r.trigger}`)}</Table.Td>
                <Table.Td>
                  <Text size="sm">{r.actions.map((a) => t(`automation.actionTypes.${a.type}`)).join(', ')}</Text>
                </Table.Td>
                <Table.Td>
                  <Text size="sm" c="dimmed">
                    {new Date(r.updatedAt).toLocaleString(i18n.language, { dateStyle: 'short', timeStyle: 'short' })}
                  </Text>
                </Table.Td>
                <Table.Td onClick={(e) => e.stopPropagation()}>
                  <Switch
                    aria-label={r.enabled ? t('automation.disable') : t('automation.enable')}
                    checked={r.enabled}
                    disabled={save.isPending}
                    onChange={(e) => toggle(r, e.currentTarget.checked)}
                  />
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
    </Stack>
  );
}
