import { Alert, Anchor, Button, Center, Paper, PasswordInput, Stack, Text, TextInput, Title } from '@mantine/core';
import { useState, type FormEvent, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router';
import { api, ApiError } from '@/shared/api/client';
import { saveTenant, savedTenant, useAuthConfig } from './api';

function Frame({ children }: { children: ReactNode }) {
  return (
    <Center mih="100dvh" p="md">
      <Paper withBorder p="xl" radius="md" shadow="sm" w="100%" maw={420}>
        {children}
      </Paper>
    </Center>
  );
}

/** «Забыли пароль?»: ответ всегда одинаковый, чтобы не раскрывать, есть ли такой адрес. */
export function ForgotPasswordPage() {
  const { t } = useTranslation();
  const config = useAuthConfig();
  const [tenant, setTenant] = useState(savedTenant);
  const [login, setLogin] = useState('');
  const [sent, setSent] = useState(false);
  const [busy, setBusy] = useState(false);
  const multi = !!config.data?.multiTenant;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    try {
      if (multi) saveTenant(tenant);
      await api.post('/auth/password/forgot', { login, tenant: multi ? tenant : undefined });
    } catch {
      // ответ одинаковый в любом случае
    } finally {
      setBusy(false);
      setSent(true);
    }
  };

  return (
    <Frame>
      <Stack component="form" onSubmit={submit}>
        <Title order={3}>{t('auth.forgotTitle')}</Title>
        {sent ? (
          <Alert color="blue">{t('auth.forgotSent')}</Alert>
        ) : (
          <>
            <Text size="sm">{t('auth.forgotHint')}</Text>
            {multi && <TextInput label={t('auth.tenant')} value={tenant} onChange={(e) => setTenant(e.currentTarget.value)} required />}
            <TextInput label={t('auth.login')} value={login} onChange={(e) => setLogin(e.currentTarget.value)} required autoFocus />
            <Button type="submit" loading={busy} disabled={!login}>
              {t('auth.forgotSubmit')}
            </Button>
          </>
        )}
        <Anchor component={Link} to="/login" size="sm">
          {t('auth.backToLogin')}
        </Anchor>
      </Stack>
    </Frame>
  );
}

export function ResetPasswordPage() {
  const { t } = useTranslation();
  const [params] = useSearchParams();
  const config = useAuthConfig();
  const [password, setPassword] = useState('');
  const [repeat, setRepeat] = useState('');
  const [error, setError] = useState<string>();
  const [done, setDone] = useState(false);
  const [busy, setBusy] = useState(false);
  const min = config.data?.passwordMinLength ?? 12;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(undefined);
    try {
      await api.post('/auth/password/reset', { token: params.get('token') ?? '', password });
      setDone(true);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : t('auth.unavailable'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Frame>
      <Stack component="form" onSubmit={submit}>
        <Title order={3}>{t('auth.resetTitle')}</Title>
        {done ? (
          <Alert color="green">{t('auth.resetDone')}</Alert>
        ) : (
          <>
            {error && <Alert color="red">{error}</Alert>}
            <PasswordInput
              label={t('security.newPassword')}
              description={t('security.passwordRules', { min })}
              value={password}
              onChange={(e) => setPassword(e.currentTarget.value)}
              autoComplete="new-password"
              required
            />
            <PasswordInput
              label={t('security.repeatPassword')}
              value={repeat}
              onChange={(e) => setRepeat(e.currentTarget.value)}
              autoComplete="new-password"
              error={repeat && repeat !== password ? t('security.mismatch') : undefined}
              required
            />
            <Button type="submit" loading={busy} disabled={password.length < min || password !== repeat}>
              {t('auth.resetSubmit')}
            </Button>
          </>
        )}
        <Anchor component={Link} to="/login" size="sm">
          {t('auth.backToLogin')}
        </Anchor>
      </Stack>
    </Frame>
  );
}
