import './i18n';
import {
  Badge,
  Button,
  Code,
  Group,
  Modal,
  SegmentedControl,
  Stack,
  Switch,
  Table,
  Tabs,
  Text,
  TextInput,
  Textarea,
  Title,
} from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { useQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import { previewTemplate, type Template, useResetTemplate, useSaveTemplate, useTemplates } from './api';

/** Шаблоны уведомлений арендатора (NTF-01, NTF-05): тема, текст письма, предпросмотр с маскированием. */
export function TemplatesPage() {
  const { t, i18n } = useTranslation();
  const [locale, setLocale] = useState<'ru' | 'en'>(i18n.language.startsWith('en') ? 'en' : 'ru');
  const [editing, setEditing] = useState<Template | null>(null);
  const templates = useTemplates();
  const rows = (templates.data ?? []).filter((x) => x.locale === locale);
  const state = listStateOf(templates, rows.length);

  return (
    <Stack gap="md" maw={1100}>
      <Group justify="space-between" align="flex-end">
        <Stack gap={4}>
          <Title order={2}>{t('notificationTemplates.title')}</Title>
          <Text size="sm" c="dimmed" maw={760}>
            {t('notificationTemplates.intro')}
          </Text>
        </Stack>
        <SegmentedControl
          value={locale}
          onChange={(v) => setLocale(v as 'ru' | 'en')}
          data={[
            { value: 'ru', label: 'Русский' },
            { value: 'en', label: 'English' },
          ]}
        />
      </Group>
      {state ? (
        <ListState state={state} error={templates.error} onRetry={() => void templates.refetch()} />
      ) : (
        <Table highlightOnHover verticalSpacing="xs">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t('notificationTemplates.kind')}</Table.Th>
              <Table.Th>{t('notificationTemplates.subject')}</Table.Th>
              <Table.Th>{t('notificationTemplates.channels')}</Table.Th>
              <Table.Th>{t('notificationTemplates.state')}</Table.Th>
              <Table.Th />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {rows.map((row) => (
              <Table.Tr key={row.kind}>
                <Table.Td>
                  <Text size="sm" fw={500}>
                    {t(`notificationTemplates.kinds.${row.kind}`, { defaultValue: row.kind })}
                  </Text>
                  <Text size="xs" c="dimmed" ff="monospace">
                    {row.kind}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <Text size="sm" lineClamp={1}>
                    {row.subject}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <Group gap={4}>
                    <Badge variant="light" color="gray" size="sm">
                      {t('notificationTemplates.channel.inApp')}
                    </Badge>
                    {row.email && (
                      <Badge variant="light" color="gray" size="sm">
                        {t('notificationTemplates.channel.email')}
                      </Badge>
                    )}
                    {row.push && (
                      <Badge variant="light" color="gray" size="sm">
                        {t('notificationTemplates.channel.push')}
                      </Badge>
                    )}
                  </Group>
                </Table.Td>
                <Table.Td>
                  <Badge variant={row.custom ? 'filled' : 'outline'} color={row.custom ? undefined : 'gray'} size="sm">
                    {row.custom ? t('notificationTemplates.custom') : t('notificationTemplates.builtIn')}
                  </Badge>
                </Table.Td>
                <Table.Td ta="right">
                  <Button size="compact-sm" variant="light" onClick={() => setEditing(row)}>
                    {t('notificationTemplates.edit')}
                  </Button>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
      {editing && <TemplateEditor template={editing} onClose={() => setEditing(null)} />}
    </Stack>
  );
}

function TemplateEditor({ template, onClose }: { template: Template; onClose: () => void }) {
  const { t } = useTranslation();
  const [subject, setSubject] = useState(template.subject);
  const [body, setBody] = useState(template.body);
  const [confidential, setConfidential] = useState(false);
  const [view, setView] = useState<string>('html');
  const save = useSaveTemplate();
  const reset = useResetTemplate();
  const input = useMemo(() => ({ subject, body, confidential }), [subject, body, confidential]);
  const [debounced] = useDebouncedValue(input, 400);

  const preview = useQuery({
    queryKey: ['notification-templates', 'preview', template.kind, template.locale, debounced],
    queryFn: () => previewTemplate(template.kind, template.locale, debounced),
    enabled: debounced.subject.trim().length > 0,
    placeholderData: (prev) => prev,
  });

  const title = t(`notificationTemplates.kinds.${template.kind}`, { defaultValue: template.kind });

  return (
    <Modal opened onClose={onClose} size="90rem" title={title}>
      <Group align="flex-start" grow wrap="wrap" gap="lg">
        <Stack gap="sm" miw={320}>
          <TextInput
            label={t('notificationTemplates.subject')}
            value={subject}
            maxLength={500}
            required
            onChange={(e) => setSubject(e.currentTarget.value)}
          />
          {template.email && (
            <Textarea
              label={t('notificationTemplates.body')}
              value={body}
              autosize
              minRows={10}
              maxRows={22}
              styles={{ input: { fontFamily: 'var(--mantine-font-family-monospace)', fontSize: 13 } }}
              onChange={(e) => setBody(e.currentTarget.value)}
            />
          )}
          <Stack gap={4}>
            <Text size="sm" fw={500}>
              {t('notificationTemplates.variables')}
            </Text>
            <Group gap={4}>
              {template.variables.map((v) => (
                <Code key={v}>{`{{${v}}}`}</Code>
              ))}
            </Group>
            <Text size="xs" c="dimmed">
              {t('notificationTemplates.variablesHint')}
            </Text>
          </Stack>
          <ErrorLine error={save.error ?? reset.error} />
          <Group justify="space-between">
            <Button
              variant="subtle"
              color="gray"
              disabled={!template.custom}
              loading={reset.isPending}
              onClick={() =>
                reset.mutate({ kind: template.kind, locale: template.locale }, { onSuccess: onClose })
              }
            >
              {t('notificationTemplates.reset')}
            </Button>
            <Button
              disabled={!subject.trim()}
              loading={save.isPending}
              onClick={() =>
                save.mutate(
                  { kind: template.kind, locale: template.locale, subject, body, version: template.version },
                  { onSuccess: onClose },
                )
              }
            >
              {t('notificationTemplates.save')}
            </Button>
          </Group>
        </Stack>
        <Stack gap="sm" miw={320}>
          <Group justify="space-between">
            <Text fw={500}>{t('notificationTemplates.preview')}</Text>
            <Switch
              size="sm"
              label={t('notificationTemplates.previewConfidential')}
              checked={confidential}
              onChange={(e) => setConfidential(e.currentTarget.checked)}
            />
          </Group>
          <ErrorLine error={preview.error} />
          {preview.data && (
            <>
              <Text size="sm" fw={600}>
                {preview.data.subject}
              </Text>
              {template.email && (
                <Tabs value={view} onChange={(v) => setView(v ?? 'html')}>
                  <Tabs.List>
                    <Tabs.Tab value="html">{t('notificationTemplates.previewHtml')}</Tabs.Tab>
                    <Tabs.Tab value="text">{t('notificationTemplates.previewText')}</Tabs.Tab>
                  </Tabs.List>
                  <Tabs.Panel value="html" pt="xs">
                    <iframe
                      title={t('notificationTemplates.previewHtml')}
                      sandbox=""
                      srcDoc={preview.data.html}
                      style={{
                        width: '100%',
                        height: 440,
                        border: '1px solid var(--ei-border)',
                        borderRadius: 8,
                        background: 'var(--ei-surface)',
                      }}
                    />
                  </Tabs.Panel>
                  <Tabs.Panel value="text" pt="xs">
                    <Code block style={{ whiteSpace: 'pre-wrap', maxHeight: 440, overflow: 'auto' }}>
                      {preview.data.text}
                    </Code>
                  </Tabs.Panel>
                </Tabs>
              )}
            </>
          )}
        </Stack>
      </Group>
    </Modal>
  );
}
