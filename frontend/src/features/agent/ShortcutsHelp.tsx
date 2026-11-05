import { Group, Kbd, Modal, SimpleGrid, Stack, Text, Title } from '@mantine/core';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

const GROUPS: { id: string; keys: [string, string[]][] }[] = [
  { id: 'global', keys: [['search', ['/']], ['queue', ['G', 'Q']], ['help', ['?']]] },
  {
    id: 'queue',
    keys: [
      ['moveDown', ['J']],
      ['moveUp', ['K']],
      ['select', ['X']],
      ['open', ['Enter']],
      ['range', ['Shift', 'Click']],
      ['find', ['F']],
      ['takeNext', ['Shift', 'T']],
      ['clear', ['Esc']],
    ],
  },
  {
    id: 'ticket',
    keys: [
      ['reply', ['R']],
      ['note', ['M']],
      ['take', ['T']],
      ['resolve', ['E']],
      ['assign', ['A']],
      ['next', ['J']],
      ['prev', ['K']],
      ['back', ['U']],
    ],
  },
  {
    id: 'composer',
    keys: [
      ['send', ['Ctrl', 'Enter']],
      ['sendResolve', ['Ctrl', 'Shift', 'Enter']],
      ['bold', ['Ctrl', 'B']],
      ['link', ['Ctrl', 'K']],
      ['mention', ['@']],
    ],
  },
];

/** Справка по клавишам (UI-11): «?» в любом месте рабочего места, кроме полей ввода. */
export function ShortcutsHelp() {
  const { t } = useTranslation();
  const [opened, setOpened] = useState(false);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const el = e.target as HTMLElement | null;
      if (el && (el.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(el.tagName))) return;
      if (e.key === '?') setOpened((o) => !o);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  return (
    <Modal opened={opened} onClose={() => setOpened(false)} title={t('workspace.help.title')} size="xl">
      <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="lg">
        {GROUPS.map((g) => (
          <Stack key={g.id} gap={6}>
            <Title order={5}>{t(`workspace.help.group.${g.id}`)}</Title>
            {g.keys.map(([id, keys]) => (
              <Group key={id} justify="space-between" wrap="nowrap">
                <Text size="sm">{t(`workspace.help.key.${g.id}.${id}`)}</Text>
                <Group gap={4} wrap="nowrap">
                  {keys.map((k) => (
                    <Kbd key={k} size="xs">
                      {k}
                    </Kbd>
                  ))}
                </Group>
              </Group>
            ))}
          </Stack>
        ))}
      </SimpleGrid>
    </Modal>
  );
}
