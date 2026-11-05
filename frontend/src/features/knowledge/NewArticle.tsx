import { Button, Group, Radio, Select, SimpleGrid, Stack, Text, TextInput, Title, UnstyledButton } from '@mantine/core';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import { AUDIENCES, newIdempotencyKey, useCreateArticle, useTemplates, type Audience, type TemplateCode } from './api';
import { CategoryField } from './CategoryField';
import { notifyError } from './ui';

/** Новая статья (10.12): шаблон, категория, аудитория. Затем открывается редактор черновика. */
export function NewArticle() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const templates = useTemplates();
  const create = useCreateArticle();
  const [title, setTitle] = useState('');
  const [template, setTemplate] = useState<TemplateCode>('howto');
  const [categoryId, setCategoryId] = useState<string | null>(null);
  const [audience, setAudience] = useState<Audience>('employees');
  // Один ключ на попытку: повторное нажатие после сбоя сети не создаст вторую статью.
  const [key, setKey] = useState(newIdempotencyKey);

  const submit = () =>
    create.mutate(
      { input: { title: title.trim(), template, categoryId, audience: audience === 'group' ? 'employees' : audience }, key },
      {
        onSuccess: (a) => navigate(`/agent/kb/${a.id}/edit`, { replace: true }),
        onError: (e) => {
          notifyError(t, e);
          setKey(newIdempotencyKey());
        },
      },
    );

  const codes: TemplateCode[] = templates.data?.map((x) => x.code) ?? ['blank', 'howto', 'solution', 'faq', 'known_error'];

  return (
    <Stack maw={760} mx="auto" gap="lg" py="md">
      <Title order={2}>{t('kb.new.title')}</Title>
      <TextInput
        label={t('kb.fields.title')}
        placeholder={t('kb.new.titlePlaceholder')}
        value={title}
        onChange={(e) => setTitle(e.currentTarget.value)}
        maxLength={255}
        required
        data-autofocus
        size="md"
      />
      <Radio.Group label={t('kb.new.template')} value={template} onChange={(v) => setTemplate(v as TemplateCode)}>
        <SimpleGrid cols={{ base: 1, xs: 2, md: 3 }} mt="xs">
          {codes.map((code) => (
            <UnstyledButton
              key={code}
              onClick={() => setTemplate(code)}
              p="sm"
              style={{
                border: `1px solid ${template === code ? 'var(--mantine-primary-color-filled)' : 'var(--mantine-color-default-border)'}`,
                borderRadius: 'var(--mantine-radius-md)',
              }}
            >
              <Group gap="xs" wrap="nowrap" align="flex-start">
                <Radio value={code} aria-label={t(`kb.templates.${code}.name`)} />
                <div>
                  <Text fw={500} size="sm">
                    {t(`kb.templates.${code}.name`)}
                  </Text>
                  <Text size="xs" c="dimmed">
                    {t(`kb.templates.${code}.description`)}
                  </Text>
                </div>
              </Group>
            </UnstyledButton>
          ))}
        </SimpleGrid>
      </Radio.Group>
      <SimpleGrid cols={{ base: 1, sm: 2 }}>
        <CategoryField value={categoryId} onChange={setCategoryId} />
        <Select
          label={t('kb.fields.audience')}
          description={t('kb.new.audienceHint')}
          inputWrapperOrder={['label', 'input', 'description']}
          value={audience}
          onChange={(v) => v && setAudience(v as Audience)}
          data={AUDIENCES.filter((a) => a !== 'group').map((a) => ({ value: a, label: t(`kb.audience.${a}`) }))}
          allowDeselect={false}
        />
      </SimpleGrid>
      <Group justify="flex-end">
        <Button variant="default" onClick={() => navigate(-1)}>
          {t('kb.actions.cancel')}
        </Button>
        <Button onClick={submit} disabled={!title.trim()} loading={create.isPending}>
          {t('kb.new.create')}
        </Button>
      </Group>
    </Stack>
  );
}
