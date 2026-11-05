import { ActionBar, Button, Menu, Text } from '@mantine/core';
import { IconArrowsJoin, IconChevronUp, IconFlag, IconProgressCheck, IconUserCheck, IconUserPlus } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { Directory, Priority, TicketSummary } from '../api/model';
import { BULK_TRANSITIONS, BulkDialog, type BulkIntent } from './BulkDialog';

interface Props {
  selected: TicketSummary[];
  directory: Directory | undefined;
  onClear: () => void;
}

/** Панель массовых действий (10.6): появляется при выборе строк, Esc снимает выбор. */
export function BulkBar({ selected, directory, onClear }: Props) {
  const { t } = useTranslation();
  const [intent, setIntent] = useState<BulkIntent | null>(null);
  const me = directory?.me;
  const myGroup = directory?.agents.find((a) => a.id === me?.id)?.groupIds[0] ?? null;

  return (
    <>
      <ActionBar opened={selected.length > 0} onClose={onClear} aria-label={t('workspace.bulk.label')} shadow="lg" closeOnEscape>
        <Text size="sm" fw={600} px="xs" style={{ whiteSpace: 'nowrap' }}>
          {t('workspace.bulk.selected', { count: selected.length })}
        </Text>
        <ActionBar.Divider />
        <Button
          variant="subtle"
          size="compact-sm"
          leftSection={<IconUserCheck size={16} />}
          onClick={() => setIntent({ kind: 'assign', assigneeId: me?.id ?? null, groupId: undefined, direct: true })}
        >
          {t('workspace.bulk.assignMe')}
        </Button>
        <Button variant="subtle" size="compact-sm" leftSection={<IconUserPlus size={16} />} onClick={() => setIntent({ kind: 'assign', groupId: myGroup })}>
          {t('workspace.bulk.assign')}
        </Button>
        <Menu position="top">
          <Menu.Target>
            <Button variant="subtle" size="compact-sm" leftSection={<IconProgressCheck size={16} />} rightSection={<IconChevronUp size={12} />}>
              {t('workspace.bulk.status')}
            </Button>
          </Menu.Target>
          <Menu.Dropdown>
            {BULK_TRANSITIONS.map((to) => (
              <Menu.Item key={to} onClick={() => setIntent({ kind: 'transition', to })}>
                {t(`workspace.status.${to}`)}
              </Menu.Item>
            ))}
          </Menu.Dropdown>
        </Menu>
        <Menu position="top">
          <Menu.Target>
            <Button variant="subtle" size="compact-sm" leftSection={<IconFlag size={16} />} rightSection={<IconChevronUp size={12} />}>
              {t('workspace.bulk.priority')}
            </Button>
          </Menu.Target>
          <Menu.Dropdown>
            {(['P1', 'P2', 'P3', 'P4'] as Priority[]).map((p) => (
              <Menu.Item key={p} onClick={() => setIntent({ kind: 'priority', priority: p })}>
                {p} · {t(`workspace.priority.${p}`)}
              </Menu.Item>
            ))}
          </Menu.Dropdown>
        </Menu>
        <Button
          variant="subtle"
          size="compact-sm"
          leftSection={<IconArrowsJoin size={16} />}
          disabled={selected.length < 2}
          onClick={() => setIntent({ kind: 'merge' })}
        >
          {t('workspace.bulk.merge')}
        </Button>
        <ActionBar.Divider />
        <ActionBar.CloseButton aria-label={t('workspace.bulk.clear')} />
      </ActionBar>
      <BulkDialog intent={intent} tickets={selected} onClose={() => setIntent(null)} onApplied={onClear} />
    </>
  );
}
