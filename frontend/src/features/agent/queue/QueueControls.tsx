import { ActionIcon, Button, Checkbox, Group, Menu, Modal, SegmentedControl, Stack, Text, TextInput, Tooltip } from '@mantine/core';
import { IconArrowDown, IconArrowUp, IconColumns3, IconLayoutList, IconLayoutRows } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { ViewVisibility } from '../api/model';
import { ALL_COLUMNS, DEFAULT_COLUMNS, type ColumnId, type Density } from './columns';

/** Меню колонок: выбор и порядок (10.6). Номер и тема обязательны, без них строку не узнать. */
export function ColumnsMenu({ columns, onChange }: { columns: ColumnId[]; onChange: (c: ColumnId[]) => void }) {
  const { t } = useTranslation();
  const required: ColumnId[] = ['number', 'title'];
  const ordered = [...columns, ...ALL_COLUMNS.filter((c) => !columns.includes(c))];
  const move = (id: ColumnId, delta: number) => {
    const i = columns.indexOf(id);
    const j = i + delta;
    if (i < 0 || j < 0 || j >= columns.length) return;
    const next = [...columns];
    [next[i], next[j]] = [next[j] as ColumnId, next[i] as ColumnId];
    onChange(next);
  };
  return (
    <Menu position="bottom-end" closeOnItemClick={false} width={250}>
      <Menu.Target>
        <Tooltip label={t('workspace.queue.columns')}>
          <ActionIcon variant="default" size="lg" aria-label={t('workspace.queue.columns')}>
            <IconColumns3 size={18} />
          </ActionIcon>
        </Tooltip>
      </Menu.Target>
      <Menu.Dropdown>
        <Menu.Label>{t('workspace.queue.columns')}</Menu.Label>
        {ordered.map((id) => {
          const on = columns.includes(id);
          return (
            <Group key={id} justify="space-between" px="xs" py={4} wrap="nowrap">
              <Checkbox
                size="xs"
                label={t(`workspace.column.${id}`)}
                checked={on}
                disabled={required.includes(id)}
                onChange={() => onChange(on ? columns.filter((c) => c !== id) : [...columns, id])}
              />
              {on && (
                <Group gap={2} wrap="nowrap">
                  <ActionIcon size="xs" variant="subtle" onClick={() => move(id, -1)} aria-label={t('workspace.queue.moveUp')}>
                    <IconArrowUp size={12} />
                  </ActionIcon>
                  <ActionIcon size="xs" variant="subtle" onClick={() => move(id, 1)} aria-label={t('workspace.queue.moveDown')}>
                    <IconArrowDown size={12} />
                  </ActionIcon>
                </Group>
              )}
            </Group>
          );
        })}
        <Menu.Divider />
        <Menu.Item onClick={() => onChange(DEFAULT_COLUMNS)}>{t('workspace.queue.resetColumns')}</Menu.Item>
      </Menu.Dropdown>
    </Menu>
  );
}

export function DensityToggle({ density, onChange }: { density: Density; onChange: (d: Density) => void }) {
  const { t } = useTranslation();
  const compact = density === 'compact';
  return (
    <Tooltip label={t(compact ? 'workspace.queue.comfortable' : 'workspace.queue.compact')}>
      <ActionIcon
        variant="default"
        size="lg"
        onClick={() => onChange(compact ? 'comfortable' : 'compact')}
        aria-label={t(compact ? 'workspace.queue.comfortable' : 'workspace.queue.compact')}
      >
        {compact ? <IconLayoutRows size={18} /> : <IconLayoutList size={18} />}
      </ActionIcon>
    </Tooltip>
  );
}

/** Сохранить представление: имя и видимость — личное, группы, общее (10.6, TCK-16). */
export function SaveViewModal({
  opened,
  onClose,
  onSave,
  saving,
}: {
  opened: boolean;
  onClose: () => void;
  onSave: (name: string, visibility: ViewVisibility) => void;
  saving: boolean;
}) {
  const { t } = useTranslation();
  const [name, setName] = useState('');
  const [visibility, setVisibility] = useState<ViewVisibility>('personal');
  return (
    <Modal opened={opened} onClose={onClose} title={t('workspace.views.saveTitle')}>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (name.trim()) onSave(name.trim(), visibility);
        }}
      >
        <Stack>
          <TextInput label={t('workspace.views.name')} value={name} onChange={(e) => setName(e.currentTarget.value)} required data-autofocus maxLength={255} />
          <div>
            <Text size="sm" fw={500} mb={4}>
              {t('workspace.views.visibility')}
            </Text>
            <SegmentedControl
              fullWidth
              value={visibility}
              onChange={(v) => setVisibility(v as ViewVisibility)}
              data={(['personal', 'group', 'shared'] as const).map((v) => ({ value: v, label: t(`workspace.views.vis.${v}`) }))}
            />
            <Text size="xs" c="dimmed" mt={4}>
              {t(`workspace.views.visHint.${visibility}`)}
            </Text>
          </div>
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose}>
              {t('workspace.actions.cancel')}
            </Button>
            <Button type="submit" loading={saving} disabled={!name.trim()}>
              {t('workspace.actions.save')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
