import { Badge, Group, Select, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import type { Directory } from '../api/model';

interface Props {
  directory: Directory | undefined;
  groupId: string | null;
  assigneeId: string | null;
  onChange: (value: { groupId: string | null; assigneeId: string | null }) => void;
  autoFocus?: boolean;
}

/**
 * Группа и исполнитель с загрузкой и доступностью (10.7 «Назначить»: показ загрузки и графика).
 * Исполнитель выбирается из участников группы; смена группы сбрасывает чужого исполнителя.
 */
export function AssignFields({ directory, groupId, assigneeId, onChange, autoFocus }: Props) {
  const { t } = useTranslation();
  const agents = (directory?.agents ?? []).filter((a) => !groupId || a.groupIds.includes(groupId));
  const byId = new Map(agents.map((a) => [a.id, a]));

  return (
    <>
      <Select
        label={t('workspace.field.group')}
        data={(directory?.groups ?? []).map((g) => ({ value: g.id, label: g.name }))}
        value={groupId}
        onChange={(g) => {
          const keep = assigneeId && directory?.agents.find((a) => a.id === assigneeId)?.groupIds.includes(g ?? '');
          onChange({ groupId: g, assigneeId: keep ? assigneeId : null });
        }}
        searchable
        data-autofocus={autoFocus || undefined}
        comboboxProps={{ withinPortal: false }}
      />
      <Select
        label={t('workspace.field.assignee')}
        placeholder={t('workspace.unassigned')}
        data={agents
          .slice()
          .sort((a, b) => Number(b.available) - Number(a.available) || a.openTickets - b.openTickets)
          .map((a) => ({ value: a.id, label: a.id === directory?.me.id ? `${a.name} (${t('workspace.filter.me')})` : a.name }))}
        value={assigneeId}
        onChange={(a) => onChange({ groupId, assigneeId: a })}
        searchable
        clearable
        comboboxProps={{ withinPortal: false }}
        renderOption={({ option }) => {
          const a = byId.get(option.value);
          return (
            <Group justify="space-between" w="100%" wrap="nowrap">
              <Text size="sm">{option.label}</Text>
              {a && (
                <Group gap={4} wrap="nowrap">
                  {!a.available && (
                    <Badge size="xs" color="gray" variant="light">
                      {t('workspace.assign.away')}
                    </Badge>
                  )}
                  <Badge size="xs" color={a.openTickets > 9 ? 'orange' : 'gray'} variant="light">
                    {t('workspace.assign.load', { count: a.openTickets })}
                  </Badge>
                </Group>
              )}
            </Group>
          );
        }}
      />
    </>
  );
}
