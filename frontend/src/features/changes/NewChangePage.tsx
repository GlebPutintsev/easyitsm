import { Button, Group, Radio, Select, SimpleGrid, Stack, Text, Textarea, TextInput, Title } from '@mantine/core';
import { IconArrowLeft } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { type ChangeType, useCreateChange, useTemplates } from './api';
import classes from './changes.module.css';
import { CiPicker } from './CiPicker';

const TYPES: ChangeType[] = ['normal', 'standard', 'emergency'];

/** Новое изменение (CHG-01): тип, шаблон для стандартного, тема, КЕ и услуги. Остальное — в карточке. */
export function NewChangePage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [type, setType] = useState<ChangeType>('normal');
  const [templateId, setTemplateId] = useState<string | null>(null);
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [ciIds, setCiIds] = useState<string[]>([]);
  const [serviceIds, setServiceIds] = useState<string[]>([]);
  const [touched, setTouched] = useState(false);
  const templates = useTemplates(true);
  const create = useCreateChange();
  const forType = (templates.data ?? []).filter((tpl) => tpl.changeType === type);

  const titleError = touched && !title.trim() ? t('changes.new.titleRequired') : undefined;
  const templateError = touched && type === 'standard' && !templateId ? t('changes.new.templateRequired') : undefined;

  const submit = () => {
    setTouched(true);
    if (!title.trim() || (type === 'standard' && !templateId)) return;
    create.mutate(
      { type, title: title.trim(), description: description.trim() || undefined, templateId, ciIds, serviceIds },
      { onSuccess: (c) => navigate(`/agent/changes/${c.id}`) },
    );
  };

  return (
    <Stack gap="md" maw={880}>
      <Button variant="subtle" leftSection={<IconArrowLeft size={16} />} component={Link} to="/agent/changes" w="fit-content" px={0}>
        {t('changes.title')}
      </Button>
      <Title order={1} fz={24}>
        {t('changes.new.title')}
      </Title>
      <div className={classes.card}>
        <Stack gap="md">
          <Radio.Group value={type} onChange={(v) => { setType(v as ChangeType); setTemplateId(null); }} label={t('changes.new.type')}>
            <SimpleGrid cols={{ base: 1, sm: 3 }} mt="xs">
              {TYPES.map((v) => (
                <Radio.Card key={v} value={v} p="md" radius="md">
                  <Group wrap="nowrap" align="flex-start">
                    <Radio.Indicator />
                    <div>
                      <Text fw={700}>{t(`changes.type.${v}`)}</Text>
                      <Text size="sm" c="dimmed">
                        {t(`changes.new.typeHint.${v}`)}
                      </Text>
                    </div>
                  </Group>
                </Radio.Card>
              ))}
            </SimpleGrid>
          </Radio.Group>
          {(type === 'standard' || forType.length > 0) && (
            <Select
              label={t('changes.new.template')}
              description={type === 'standard' ? t('changes.new.templateHint') : undefined}
              withAsterisk={type === 'standard'}
              clearable={type !== 'standard'}
              value={templateId}
              onChange={setTemplateId}
              error={templateError}
              data={forType.map((tpl) => ({ value: tpl.id, label: tpl.name }))}
              nothingFoundMessage={t('changes.new.noTemplates')}
            />
          )}
          <TextInput label={t('changes.fields.title')} withAsterisk value={title} onChange={(e) => setTitle(e.currentTarget.value)} error={titleError} maxLength={255} />
          <Textarea label={t('changes.fields.goal')} description={t('changes.new.goalHint')} autosize minRows={3} value={description} onChange={(e) => setDescription(e.currentTarget.value)} />
          <CiPicker label={t('changes.fields.services')} services value={serviceIds} onChange={setServiceIds} />
          <CiPicker label={t('changes.fields.cis')} value={ciIds} onChange={setCiIds} />
          <ErrorLine error={create.error} />
          <Group justify="flex-end">
            <Button variant="default" component={Link} to="/agent/changes">
              {t('actions.cancel')}
            </Button>
            <Button onClick={submit} loading={create.isPending}>
              {t('changes.new.submit')}
            </Button>
          </Group>
        </Stack>
      </div>
    </Stack>
  );
}
