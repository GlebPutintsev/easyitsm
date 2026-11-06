import { Button, Group, List, Stack, Text, TextInput, Title } from '@mantine/core';
import { IconFlask } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { type RuleInput, useTestRule } from './api';
import css from './automation.module.css';

/** «Проверить»: что сделает правило с заявкой сейчас. Ничего не меняет, правило может быть не сохранено. */
export function TestPanel({ rule, disabled }: { rule: RuleInput; disabled: boolean }) {
  const { t } = useTranslation();
  const [ticket, setTicket] = useState('');
  const test = useTestRule();
  const r = test.data;
  const conditions = rule.conditions?.items ?? [];

  return (
    <Stack gap="sm" className={css.testBox}>
      <div>
        <Title order={4}>{t('automation.test.title')}</Title>
        <Text size="sm" c="dimmed">
          {t('automation.test.hint')}
        </Text>
      </div>
      <Group align="flex-end" gap="xs">
        <TextInput
          label={t('automation.test.ticket')}
          placeholder="INC-000123"
          value={ticket}
          onChange={(e) => setTicket(e.currentTarget.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && ticket.trim() && !disabled) test.mutate({ rule, ticket: ticket.trim() });
          }}
          w={200}
        />
        <Button
          variant="default"
          leftSection={<IconFlask size={16} />}
          disabled={disabled || !ticket.trim()}
          loading={test.isPending}
          onClick={() => test.mutate({ rule, ticket: ticket.trim() })}
        >
          {t('automation.test.run')}
        </Button>
      </Group>
      <ErrorLine error={test.error} />
      {r && (
        <Stack gap="xs" aria-live="polite">
          <Text size="sm" fw={600}>
            <span className={css.mono}>{r.ticket.number}</span> · {r.ticket.title}
          </Text>
          <span
            className={`${css.pill} ${r.matched === true ? css.success : r.matched === false ? css.off : css.skipped}`}
            style={{ width: 'fit-content' }}
          >
            {r.matched === true
              ? t('automation.test.matched')
              : r.matched === false
                ? t('automation.test.notMatched')
                : t('automation.test.unknown', { fields: r.unknownFields.map((f) => t(`automation.fields.${f}`)).join(', ') })}
          </span>
          {r.conditions.length > 0 && (
            <List size="sm" spacing={2}>
              {r.conditions.map((ok, i) => (
                <List.Item key={i}>
                  {conditions[i] ? `${t(`automation.fields.${conditions[i].field}`)} ${t(`automation.ops.${conditions[i].op}`)}` : `#${i + 1}`}
                  {' — '}
                  {ok === true ? t('automation.test.conditionOk') : ok === false ? t('automation.test.conditionFail') : t('automation.test.conditionUnknown')}
                </List.Item>
              ))}
            </List>
          )}
          <List size="sm" spacing={2} type="ordered">
            {r.actions.map((a, i) => (
              <List.Item key={i}>
                {t(`automation.actionTypes.${a.type}`)} — {t(`automation.test.outcome.${a.outcome}`)}
                {a.detail && (
                  <Text size="xs" c="dimmed" style={{ whiteSpace: 'pre-wrap' }}>
                    {a.detail}
                  </Text>
                )}
              </List.Item>
            ))}
          </List>
        </Stack>
      )}
    </Stack>
  );
}
