import {
  ActionIcon,
  Badge,
  Button,
  Card,
  Group,
  Modal,
  PasswordInput,
  Stack,
  Table,
  Text,
  TextInput,
  Title,
  Tooltip,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconDeviceDesktop, IconFingerprint, IconKey, IconLogout, IconShieldCheck, IconTrash } from '@tabler/icons-react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import { api, ApiError } from '@/shared/api/client';
import { ListState, listStateOf } from '@/shared/ui/ListState';
import { meKey, useMe, useAuthConfig, type WebAuthnOptions } from '@/features/auth/api';
import { RecoveryCodes } from '@/features/auth/RecoveryCodes';
import { TotpEnrollment, type TotpSetup } from '@/features/auth/TotpEnrollment';
import { createKey, webauthnSupported } from '@/features/auth/webauthn';

interface SessionRow {
  id: string;
  authMethod: string;
  createdAt: string;
  lastSeenAt: string;
  ip: string | null;
  userAgent: string | null;
  current: boolean;
}

interface KeyRow {
  id: string;
  name: string;
  createdAt: string;
  lastUsedAt: string | null;
  passkey: boolean;
}

interface LoginRow {
  at: string;
  result: string;
  method: string;
  ip: string | null;
  userAgent: string | null;
}

/** «Профиль → Безопасность» (10.3 ТЗ): пароль, приложение, ключи, резервные коды, сессии, журнал входов. */
export function SecurityPage() {
  const { t } = useTranslation();
  const me = useMe();
  if (!me.data) return <ListState state="loading" />;
  const local = me.data.source === 'local';
  return (
    <Stack maw={960} gap="lg">
      <Title order={2}>{t('security.title')}</Title>
      {local && <PasswordSection />}
      <MfaSection />
      <SessionsSection />
      <LoginHistorySection />
    </Stack>
  );
}

function Section({ title, icon, children, action }: { title: string; icon: ReactNode; children: ReactNode; action?: ReactNode }) {
  return (
    <Card withBorder radius="md" padding="lg">
      <Group justify="space-between" mb="md">
        <Group gap="xs">
          {icon}
          <Title order={4}>{title}</Title>
        </Group>
        {action}
      </Group>
      {children}
    </Card>
  );
}

function errorText(e: unknown, fallback: string) {
  return e instanceof ApiError ? e.message : fallback;
}

function PasswordSection() {
  const { t } = useTranslation();
  const config = useAuthConfig();
  const min = config.data?.passwordMinLength ?? 12;
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [repeat, setRepeat] = useState('');
  const change = useMutation({
    mutationFn: () => api.post('/me/password', { currentPassword: current, newPassword: next }),
    onSuccess: () => {
      setCurrent('');
      setNext('');
      setRepeat('');
      notifications.show({ color: 'green', message: t('security.passwordChanged') });
    },
  });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    change.mutate();
  };
  return (
    <Section title={t('security.password')} icon={<IconKey size={20} />}>
      <Stack component="form" onSubmit={submit} maw={420}>
        <PasswordInput label={t('security.currentPassword')} value={current} onChange={(e) => setCurrent(e.currentTarget.value)} autoComplete="current-password" required />
        <PasswordInput
          label={t('security.newPassword')}
          description={t('security.passwordRules', { min })}
          value={next}
          onChange={(e) => setNext(e.currentTarget.value)}
          autoComplete="new-password"
          required
        />
        <PasswordInput
          label={t('security.repeatPassword')}
          value={repeat}
          onChange={(e) => setRepeat(e.currentTarget.value)}
          autoComplete="new-password"
          error={repeat && repeat !== next ? t('security.mismatch') : undefined}
          required
        />
        {change.isError && <Text c="red" size="sm">{errorText(change.error, t('states.errorText'))}</Text>}
        <Group>
          <Button type="submit" loading={change.isPending} disabled={!current || next.length < min || next !== repeat}>
            {t('security.changePassword')}
          </Button>
        </Group>
      </Stack>
    </Section>
  );
}

function MfaSection() {
  const { t, i18n } = useTranslation();
  const queryClient = useQueryClient();
  const me = useMe();
  const keys = useQuery({ queryKey: ['me', 'keys'], queryFn: ({ signal }) => api.get<KeyRow[]>('/me/mfa/keys', { signal }) });
  const [totpSetup, setTotpSetup] = useState<TotpSetup>();
  const [codes, setCodes] = useState<string[]>();
  const [keyName, setKeyName] = useState('');
  const [error, setError] = useState<string>();
  const security = me.data?.security;
  const date = new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium' });

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: meKey });
  };

  const startTotp = useMutation({
    mutationFn: () => api.post<TotpSetup>('/me/mfa/totp', {}),
    onSuccess: setTotpSetup,
    onError: (e) => setError(errorText(e, t('states.errorText'))),
  });
  const disableTotp = useMutation({
    mutationFn: () => api.delete('/me/mfa/totp'),
    onSuccess: refresh,
    onError: (e) => setError(errorText(e, t('states.errorText'))),
  });
  const regenerate = useMutation({
    mutationFn: () => api.post<{ recoveryCodes: string[] }>('/me/mfa/recovery-codes', {}),
    onSuccess: (r) => {
      setCodes(r.recoveryCodes);
      refresh();
    },
    onError: (e) => setError(errorText(e, t('states.errorText'))),
  });
  const deleteKey = useMutation({
    mutationFn: (id: string) => api.delete(`/me/mfa/keys/${id}`),
    onSuccess: () => {
      refresh();
      void keys.refetch();
    },
    onError: (e) => setError(errorText(e, t('states.errorText'))),
  });

  const addKey = async () => {
    setError(undefined);
    try {
      const options = await api.post<WebAuthnOptions>('/me/mfa/keys/options', {});
      const credential = await createKey(options);
      const result = await api.post<{ recoveryCodes: string[] | null }>('/me/mfa/keys', {
        ceremonyId: options.ceremonyId,
        credential,
        name: keyName || undefined,
      });
      setKeyName('');
      if (result.recoveryCodes) setCodes(result.recoveryCodes);
      refresh();
      void keys.refetch();
    } catch (e) {
      if (!(e instanceof Error && e.name === 'NotAllowedError')) setError(errorText(e, t('states.errorText')));
    }
  };

  return (
    <Section
      title={t('security.mfa')}
      icon={<IconShieldCheck size={20} />}
      action={security?.mfaRequired ? <Badge color="orange">{t('security.mfaRequired')}</Badge> : undefined}
    >
      <Stack gap="lg">
        {error && <Text c="red" size="sm">{error}</Text>}
        <Group justify="space-between" wrap="nowrap">
          <div>
            <Text fw={500}>{t('security.app')}</Text>
            <Text size="sm" c="dimmed">
              {security?.totp ? t('security.appOn') : t('security.appOff')}
            </Text>
          </div>
          {security?.totp ? (
            <Button variant="default" color="red" loading={disableTotp.isPending} onClick={() => disableTotp.mutate()}>
              {t('security.disable')}
            </Button>
          ) : (
            <Button loading={startTotp.isPending} onClick={() => startTotp.mutate()}>
              {t('security.setupApp')}
            </Button>
          )}
        </Group>

        <div>
          <Group justify="space-between" mb="xs" wrap="nowrap">
            <div>
              <Text fw={500}>{t('security.keys')}</Text>
              <Text size="sm" c="dimmed">
                {t('security.keysHint')}
              </Text>
            </div>
          </Group>
          {keys.data && keys.data.length > 0 && (
            <Table mb="sm">
              <Table.Tbody>
                {keys.data.map((k) => (
                  <Table.Tr key={k.id}>
                    <Table.Td>
                      <Group gap="xs">
                        <IconFingerprint size={16} />
                        {k.name}
                        {k.passkey && <Badge size="xs" variant="light">passkey</Badge>}
                      </Group>
                    </Table.Td>
                    <Table.Td c="dimmed">{t('security.added', { date: date.format(new Date(k.createdAt)) })}</Table.Td>
                    <Table.Td w={40}>
                      <Tooltip label={t('security.removeKey')}>
                        <ActionIcon variant="subtle" color="red" aria-label={t('security.removeKey')} onClick={() => deleteKey.mutate(k.id)}>
                          <IconTrash size={16} />
                        </ActionIcon>
                      </Tooltip>
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          )}
          {webauthnSupported() && (
            <Group>
              <TextInput placeholder={t('security.keyName')} aria-label={t('security.keyName')} value={keyName} onChange={(e) => setKeyName(e.currentTarget.value)} maxLength={100} />
              <Button leftSection={<IconFingerprint size={16} />} onClick={() => void addKey()}>
                {t('security.addKey')}
              </Button>
            </Group>
          )}
        </div>

        <Group justify="space-between" wrap="nowrap">
          <div>
            <Text fw={500}>{t('security.recovery')}</Text>
            <Text size="sm" c="dimmed">
              {t('security.recoveryLeft', { count: security?.recoveryCodesLeft ?? 0 })}
            </Text>
          </div>
          <Button variant="default" loading={regenerate.isPending} disabled={!security?.totp && !security?.keys} onClick={() => regenerate.mutate()}>
            {t('security.generateCodes')}
          </Button>
        </Group>
      </Stack>

      <Modal opened={!!totpSetup} onClose={() => setTotpSetup(undefined)} title={t('auth.setup.appTitle')} centered>
        {totpSetup && (
          <TotpEnrollment
            setup={totpSetup}
            onConfirm={async (code) => {
              const r = await api.post<{ recoveryCodes: string[] | null }>('/me/mfa/totp/confirm', { code });
              setTotpSetup(undefined);
              if (r.recoveryCodes) setCodes(r.recoveryCodes);
              refresh();
            }}
          />
        )}
      </Modal>
      <Modal opened={!!codes} onClose={() => setCodes(undefined)} title={t('auth.recovery.title')} centered>
        {codes && <RecoveryCodes codes={codes} onDone={() => setCodes(undefined)} />}
      </Modal>
    </Section>
  );
}

function SessionsSection() {
  const { t, i18n } = useTranslation();
  const sessions = useQuery({ queryKey: ['me', 'sessions'], queryFn: ({ signal }) => api.get<SessionRow[]>('/me/sessions', { signal }) });
  const end = useMutation({ mutationFn: (id: string) => api.delete(`/me/sessions/${id}`), onSuccess: () => void sessions.refetch() });
  const endOthers = useMutation({ mutationFn: () => api.post('/me/sessions/revoke-others', {}), onSuccess: () => void sessions.refetch() });
  const items = sessions.data ?? [];
  const state = listStateOf(sessions, items.length);
  const time = new Intl.DateTimeFormat(i18n.language, { dateStyle: 'short', timeStyle: 'short' });
  return (
    <Section
      title={t('security.sessions')}
      icon={<IconDeviceDesktop size={20} />}
      action={
        <Button variant="default" size="xs" leftSection={<IconLogout size={14} />} loading={endOthers.isPending} disabled={items.length < 2} onClick={() => endOthers.mutate()}>
          {t('security.endOthers')}
        </Button>
      }
    >
      {state ? (
        <ListState state={state} error={sessions.error} onRetry={() => void sessions.refetch()} rows={2} />
      ) : (
        <Table.ScrollContainer minWidth={560}>
          <Table>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('security.device')}</Table.Th>
                <Table.Th>{t('security.ip')}</Table.Th>
                <Table.Th>{t('security.lastSeen')}</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {items.map((s) => (
                <Table.Tr key={s.id}>
                  <Table.Td>
                    <Text size="sm" lineClamp={1} title={s.userAgent ?? ''}>
                      {browserName(s.userAgent)}
                    </Text>
                    {s.current && <Badge size="xs">{t('security.current')}</Badge>}
                  </Table.Td>
                  <Table.Td>{s.ip}</Table.Td>
                  <Table.Td>{time.format(new Date(s.lastSeenAt))}</Table.Td>
                  <Table.Td>
                    {!s.current && (
                      <Button variant="subtle" color="red" size="xs" onClick={() => end.mutate(s.id)}>
                        {t('security.end')}
                      </Button>
                    )}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
    </Section>
  );
}

function LoginHistorySection() {
  const { t, i18n } = useTranslation();
  const logins = useQuery({ queryKey: ['me', 'logins'], queryFn: ({ signal }) => api.get<LoginRow[]>('/me/logins', { signal }) });
  const items = logins.data ?? [];
  const state = listStateOf(logins, items.length);
  const time = new Intl.DateTimeFormat(i18n.language, { dateStyle: 'short', timeStyle: 'short' });
  return (
    <Section title={t('security.history')} icon={<IconKey size={20} />}>
      {state ? (
        <ListState state={state} error={logins.error} onRetry={() => void logins.refetch()} rows={3} />
      ) : (
        <Table.ScrollContainer minWidth={560}>
          <Table striped>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('security.when')}</Table.Th>
                <Table.Th>{t('security.result')}</Table.Th>
                <Table.Th>{t('security.method')}</Table.Th>
                <Table.Th>{t('security.ip')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {items.map((l, i) => (
                <Table.Tr key={i}>
                  <Table.Td>{time.format(new Date(l.at))}</Table.Td>
                  <Table.Td>
                    <Badge color={l.result === 'success' ? 'green' : l.result === 'reset_requested' ? 'blue' : 'red'} variant="light">
                      {t(`security.results.${l.result}`, { defaultValue: l.result })}
                    </Badge>
                  </Table.Td>
                  <Table.Td>{methodLabel(l.method, t)}</Table.Td>
                  <Table.Td>{l.ip}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
    </Section>
  );
}

/** Способ входа из журнала: «password+totp» → «Пароль + код из приложения», «sso:keycloak» → «Единый вход (keycloak)». */
function methodLabel(method: string, t: TFunction) {
  return method
    .split('+')
    .map((m) => (m.startsWith('sso:') ? `${t('security.methods.oidc')} (${m.slice(4)})` : t(`security.methods.${m}`, { defaultValue: m })))
    .join(' + ');
}

/** Короткое имя браузера и системы из User-Agent для таблицы сессий. */
export function browserName(ua: string | null): string {
  if (!ua) return '—';
  const browser = /Edg\//.test(ua) ? 'Edge' : /YaBrowser/.test(ua) ? 'Yandex' : /Firefox\//.test(ua) ? 'Firefox' : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : 'Browser';
  const os = /Windows/.test(ua) ? 'Windows' : /Android/.test(ua) ? 'Android' : /iPhone|iPad/.test(ua) ? 'iOS' : /Mac OS X/.test(ua) ? 'macOS' : /Linux/.test(ua) ? 'Linux' : '';
  return os ? `${browser}, ${os}` : browser;
}
