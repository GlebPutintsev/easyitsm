import { ActionIcon, Button, Group, Modal, Select, Stack, TextInput, Tooltip } from '@mantine/core';
import { IconPlus } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { newIdempotencyKey, useCategories, useCreateCategory } from './api';
import { notifyError } from './ui';

/** Выбор категории с быстрым созданием новой (создать может редактор знаний или администратор). */
export function CategoryField({ value, onChange }: { value: string | null; onChange: (id: string | null) => void }) {
  const { t } = useTranslation();
  const categories = useCategories();
  const create = useCreateCategory();
  const [opened, setOpened] = useState(false);
  const [name, setName] = useState('');
  const [key] = useState(newIdempotencyKey);

  return (
    <>
      <Group gap={4} align="flex-end" wrap="nowrap">
        <Select
          label={t('kb.fields.category')}
          placeholder={t('kb.fields.noCategory')}
          value={value}
          onChange={onChange}
          data={(categories.data ?? []).map((c) => ({ value: c.id, label: c.name }))}
          clearable
          searchable
          style={{ flex: 1 }}
        />
        <Tooltip label={t('kb.category.create')}>
          <ActionIcon variant="default" size="lg" aria-label={t('kb.category.create')} onClick={() => setOpened(true)}>
            <IconPlus size={16} />
          </ActionIcon>
        </Tooltip>
      </Group>
      <Modal opened={opened} onClose={() => setOpened(false)} title={t('kb.category.create')}>
        <Stack>
          <TextInput label={t('kb.category.name')} value={name} onChange={(e) => setName(e.currentTarget.value)} maxLength={120} data-autofocus />
          <Group justify="flex-end">
            <Button
              disabled={!name.trim()}
              loading={create.isPending}
              onClick={() =>
                create.mutate(
                  { name: name.trim(), key: `${key}-${name.trim()}` },
                  {
                    onSuccess: (c) => {
                      onChange(c.id);
                      setName('');
                      setOpened(false);
                    },
                    onError: (e) => notifyError(t, e),
                  },
                )
              }
            >
              {t('kb.actions.create')}
            </Button>
          </Group>
        </Stack>
      </Modal>
    </>
  );
}
