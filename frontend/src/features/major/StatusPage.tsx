import { Group, Paper, Skeleton, Stack, Text, Title } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { NavLink } from 'react-router';
import { ErrorLine } from '@/shared/ui/ErrorLine';
import { useStatusPage, type StatusPage as Status } from './api';
import classes from './major.module.css';
import { dateTimeOf, timeOf } from './ui';

type Level = 'ok' | 'degraded' | 'outage';

function levelOf(s: Status | undefined): Level {
  if (!s) return 'ok';
  if (s.majorIncidents.some((m) => m.state === 'active') || s.announcements.some((a) => a.kind === 'outage')) return 'outage';
  if (s.majorIncidents.length > 0 || s.announcements.some((a) => a.kind === 'maintenance')) return 'degraded';
  return 'ok';
}

/** Страница статуса портала (10.2, MIM-04): крупные инциденты с последним сообщением и действующие объявления. */
export function StatusPage() {
  const { t, i18n } = useTranslation();
  const status = useStatusPage();
  const level = levelOf(status.data);
  return (
    <Stack gap="md" maw={880} mx="auto" w="100%">
      <Title order={2}>{t('major.status.title')}</Title>
      {status.isPending ? (
        <Skeleton height={56} />
      ) : status.error ? (
        <ErrorLine error={status.error} />
      ) : (
        <>
          <div className={classes.statusHead} data-level={level} role="status">
            <span className={classes.statusDot} data-level={level} aria-hidden="true" />
            {t(`major.status.level.${level}`)}
          </div>
          {status.data.majorIncidents.map((m) => (
            <Paper key={m.id} withBorder radius="md" p="md">
              <Group justify="space-between" gap="xs" wrap="wrap">
                <Text fw={700}>{m.title}</Text>
                <Text size="xs" fw={600} c={m.state === 'active' ? 'var(--ei-danger)' : 'var(--ei-ok)'}>
                  {t(`major.status.state.${m.state}`)}
                </Text>
              </Group>
              {m.services.length > 0 && (
                <Text size="sm" c="dimmed">
                  {t('major.status.services', { services: m.services.map((s) => s.name).join(', ') })}
                </Text>
              )}
              <Text size="xs" c="dimmed">
                {t('major.status.since', { at: dateTimeOf(m.declaredAt, i18n.language) })}
                {m.restoredAt ? ` · ${t('major.status.restoredAt', { at: dateTimeOf(m.restoredAt, i18n.language) })}` : ''}
              </Text>
              {m.lastUpdate && (
                <div className={classes.update} style={{ marginTop: 12 }}>
                  <Text size="sm" fw={600}>
                    {m.lastUpdate.title}
                  </Text>
                  <Text size="sm" mt={4} className={classes.pre}>
                    {m.lastUpdate.body}
                  </Text>
                  <Text size="xs" c="dimmed" mt={4}>
                    {dateTimeOf(m.lastUpdate.publishedAt, i18n.language)}
                  </Text>
                </div>
              )}
              {m.nextUpdateAt && m.state === 'active' && (
                <Text size="xs" c="dimmed" mt="xs">
                  {t('major.status.nextUpdate', { time: timeOf(m.nextUpdateAt, i18n.language) })}
                </Text>
              )}
            </Paper>
          ))}
          {status.data.announcements.map((a) => (
            <Paper key={a.id} withBorder radius="md" p="md">
              <Group justify="space-between" gap="xs" wrap="wrap">
                <Text fw={600}>{a.title}</Text>
                <Text size="xs" c="dimmed">
                  {t(`major.status.kind.${a.kind}`)}
                </Text>
              </Group>
              {a.body && (
                <Text size="sm" mt={4} className={classes.pre}>
                  {a.body}
                </Text>
              )}
              <Text size="xs" c="dimmed" mt={4}>
                {dateTimeOf(a.startsAt, i18n.language)}
                {a.endsAt ? ` — ${dateTimeOf(a.endsAt, i18n.language)}` : ''}
              </Text>
            </Paper>
          ))}
          {status.data.majorIncidents.length === 0 && status.data.announcements.length === 0 && (
            <Text c="dimmed" size="sm">
              {t('major.status.allGood')}
            </Text>
          )}
        </>
      )}
    </Stack>
  );
}

/** «Статус» в шапке портала: точка — зелёная, жёлтая или красная по текущим сбоям. */
export function StatusNavLink() {
  const { t } = useTranslation();
  const status = useStatusPage();
  const level = levelOf(status.data);
  return (
    <NavLink to="/portal/status" aria-label={`${t('major.status.nav')}: ${t(`major.status.level.${level}`)}`}>
      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
        <span className={classes.statusDot} data-level={level} aria-hidden="true" />
        {t('major.status.nav')}
      </span>
    </NavLink>
  );
}
