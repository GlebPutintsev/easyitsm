import {
  Alert,
  Anchor,
  Button,
  Center,
  Checkbox,
  Divider,
  Group,
  Paper,
  PasswordInput,
  PinInput,
  Select,
  Stack,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { IconFingerprint, IconLogin2, IconShieldLock } from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { api, ApiError } from '@/shared/api/client';
import { meKey, saveTenant, savedTenant, useAuthConfig, type LoginResult, type WebAuthnOptions } from './api';
import { RecoveryCodes } from './RecoveryCodes';
import { TotpEnrollment, type TotpSetup } from './TotpEnrollment';
import { createKey, signWithKey, webauthnSupported } from './webauthn';

type Step = 'credentials' | 'mfa' | 'mfa_setup' | 'recovery';

/**
 * Экран входа (10.3 ТЗ): логин и пароль, SSO, вход с ключом, второй фактор, обязательная настройка MFA.
 * Ошибка входа всегда одна и та же и не раскрывает причину.
 */
export function LoginPage() {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [params] = useSearchParams();
  const config = useAuthConfig();
  const [chosenStep, setStep] = useState<Step>();
  // После перезагрузки страницы продолжаем незавершённый вход с того шага, на котором он остановился.
  const step: Step = chosenStep ?? config.data?.pendingStep ?? 'credentials';
  const [methods, setMethods] = useState<string[]>([]);
  const [recoveryCodes, setRecoveryCodes] = useState<string[]>([]);
  const [error, setError] = useState<string | undefined>(() =>
    params.get('error') ? t(params.get('error') === 'sso_conflict' ? 'auth.ssoConflict' : 'auth.ssoError') : undefined,
  );

  const next = safeNext(params.get('next'));

  useEffect(() => {
    if (!config.data) return;
    if (config.data.authenticated && step === 'credentials') {
      navigate(next, { replace: true });
    }
  }, [config.data, navigate, next, step]);

  const finish = async () => {
    await queryClient.invalidateQueries({ queryKey: meKey });
    navigate(next, { replace: true });
  };

  const handle = async (result: LoginResult) => {
    setError(undefined);
    if (result.status === 'mfa_required') {
      setMethods(result.methods);
      setStep('mfa');
    } else if (result.status === 'mfa_setup_required') {
      setStep('mfa_setup');
    } else if (result.recoveryCodes?.length) {
      setRecoveryCodes(result.recoveryCodes);
      setStep('recovery');
    } else {
      await finish();
    }
  };

  const fail = (e: unknown) => {
    if (e instanceof ApiError && e.code === 'too_many_attempts') setError(t('auth.tooManyAttempts'));
    else if (e instanceof ApiError && e.status === 401) setError(e.code === 'invalid_code' ? t('auth.mfa.invalid') : t('auth.invalid'));
    else if (e instanceof ApiError && e.code === 'login_required') {
      setStep('credentials');
      setError(t('auth.restart'));
    } else setError(t('auth.unavailable'));
  };

  return (
    <Center mih="100dvh" p="md" bg="var(--mantine-color-body)">
      <Stack w="100%" maw={420} gap="md">
        <Group justify="space-between">
          <Text fw={700} size="xl">
            Easy ITSM
          </Text>
          <Select
            size="xs"
            w={120}
            aria-label={t('auth.language')}
            data={[
              { value: 'ru', label: 'Русский' },
              { value: 'en', label: 'English' },
            ]}
            value={i18n.language}
            onChange={(v) => v && void i18n.changeLanguage(v)}
            allowDeselect={false}
          />
        </Group>
        <Paper withBorder p="xl" radius="md" shadow="sm">
          {step === 'credentials' && (
            <Credentials
              multiTenant={!!config.data?.multiTenant}
              sso={config.data?.sso ?? []}
              passwordLogin={config.data?.passwordLogin ?? true}
              error={error}
              onResult={handle}
              onError={fail}
            />
          )}
          {step === 'mfa' && (
            <SecondFactor
              methods={methods.length ? methods : ['totp', 'webauthn', 'recovery']}
              rememberDays={config.data?.rememberDeviceDays ?? 0}
              error={error}
              onResult={handle}
              onError={fail}
              onBack={() => setStep('credentials')}
            />
          )}
          {step === 'mfa_setup' && <MfaSetup onResult={handle} onError={fail} error={error} />}
          {step === 'recovery' && <RecoveryCodes codes={recoveryCodes} onDone={() => void finish()} />}
        </Paper>
      </Stack>
    </Center>
  );
}

/** Разрешаем возврат только на свои страницы, иначе ссылка входа превращается в открытое перенаправление. */
/** Куда вернуться после входа: только путь внутри приложения, без открытого редиректа на чужой сайт. */
export function safeNext(next: string | null) {
  return next && /^\/(?![/\\])/.test(next) && !next.includes('\\') ? next : '/';
}

interface StepProps {
  error?: string;
  onResult: (r: LoginResult) => Promise<void>;
  onError: (e: unknown) => void;
}

function Credentials({
  multiTenant,
  sso,
  passwordLogin,
  error,
  onResult,
  onError,
}: StepProps & { multiTenant: boolean; sso: { id: string; name: string }[]; passwordLogin: boolean }) {
  const { t } = useTranslation();
  const [tenant, setTenant] = useState(savedTenant);
  const [login, setLogin] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    try {
      if (multiTenant) saveTenant(tenant);
      await onResult(await api.post<LoginResult>('/auth/login', { login, password, tenant: multiTenant ? tenant : undefined }));
    } catch (err) {
      onError(err);
      setPassword('');
    } finally {
      setBusy(false);
    }
  };

  const passkey = async () => {
    setBusy(true);
    try {
      const body = { tenant: multiTenant ? tenant : undefined };
      const options = await api.post<WebAuthnOptions>('/auth/passkey/options', body);
      const credential = await signWithKey(options);
      await onResult(await api.post<LoginResult>('/auth/passkey', { ...body, ceremonyId: options.ceremonyId, credential }));
    } catch (err) {
      if (!(err instanceof Error && err.name === 'NotAllowedError')) onError(err);
    } finally {
      setBusy(false);
    }
  };

  const ssoHref = (id: string) =>
    `/api/v1/auth/oidc/start/${encodeURIComponent(id)}${multiTenant ? `?tenant=${encodeURIComponent(tenant)}` : ''}`;

  return (
    <Stack component="form" onSubmit={submit} gap="md">
      <Title order={3}>{t('auth.title')}</Title>
      {error && (
        <Alert color="red" role="alert">
          {error}
        </Alert>
      )}
      {multiTenant && (
        <TextInput
          label={t('auth.tenant')}
          value={tenant}
          onChange={(e) => setTenant(e.currentTarget.value.trim().toLowerCase())}
          autoComplete="organization"
          required
        />
      )}
      {passwordLogin && (
        <>
          <TextInput
            label={t('auth.login')}
            value={login}
            onChange={(e) => setLogin(e.currentTarget.value)}
            autoComplete="username"
            autoFocus
            required
          />
          <PasswordInput
            label={t('auth.password')}
            value={password}
            onChange={(e) => setPassword(e.currentTarget.value)}
            autoComplete="current-password"
            required
          />
          <Group justify="flex-end" mt={-8}>
            <Anchor component={Link} to="/forgot-password" size="sm">
              {t('auth.forgot')}
            </Anchor>
          </Group>
          <Button
            type="submit"
            loading={busy}
            disabled={!login || !password || (multiTenant && !tenant)}
            leftSection={<IconLogin2 size={18} />}
          >
            {t('auth.submit')}
          </Button>
        </>
      )}
      {(sso.length > 0 || webauthnSupported()) && passwordLogin && <Divider label={t('auth.or')} />}
      {sso.map((p) => (
        <Button
          key={p.id}
          variant="default"
          component="a"
          href={ssoHref(p.id)}
          leftSection={<IconShieldLock size={18} />}
          aria-disabled={multiTenant && !tenant}
        >
          {t('auth.sso', { name: p.name })}
        </Button>
      ))}
      {webauthnSupported() && (
        <Button
          variant="default"
          leftSection={<IconFingerprint size={18} />}
          onClick={() => void passkey()}
          disabled={busy || (multiTenant && !tenant)}
        >
          {t('auth.passkey')}
        </Button>
      )}
    </Stack>
  );
}

function SecondFactor({
  methods,
  rememberDays,
  error,
  onResult,
  onError,
  onBack,
}: StepProps & { methods: string[]; rememberDays: number; onBack: () => void }) {
  const { t } = useTranslation();
  const hasTotp = methods.includes('totp');
  const hasKey = methods.includes('webauthn') && webauthnSupported();
  const [method, setMethod] = useState<'totp' | 'recovery' | 'webauthn'>(hasTotp ? 'totp' : hasKey ? 'webauthn' : 'recovery');
  const [code, setCode] = useState('');
  const [remember, setRemember] = useState(false);
  const [busy, setBusy] = useState(false);

  const submitCode = async (value: string) => {
    setBusy(true);
    try {
      await onResult(await api.post<LoginResult>('/auth/mfa', { method, code: value, rememberDevice: remember }));
    } catch (err) {
      onError(err);
      setCode('');
    } finally {
      setBusy(false);
    }
  };

  const signWithSecurityKey = async () => {
    setBusy(true);
    try {
      const options = await api.post<WebAuthnOptions>('/auth/mfa/webauthn/options', {});
      const credential = await signWithKey(options);
      await onResult(
        await api.post<LoginResult>('/auth/mfa/webauthn', { ceremonyId: options.ceremonyId, credential, rememberDevice: remember }),
      );
    } catch (err) {
      if (!(err instanceof Error && err.name === 'NotAllowedError')) onError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Stack gap="md">
      <Title order={3}>{t('auth.mfa.title')}</Title>
      {error && (
        <Alert color="red" role="alert">
          {error}
        </Alert>
      )}
      {method === 'totp' && (
        <>
          <Text size="sm">{t('auth.mfa.enterCode')}</Text>
          <Center>
            <PinInput
              length={6}
              type="number"
              oneTimeCode
              autoFocus
              value={code}
              onChange={setCode}
              onComplete={(v) => void submitCode(v)}
              disabled={busy}
              aria-label={t('auth.mfa.code')}
            />
          </Center>
        </>
      )}
      {method === 'recovery' && (
        <TextInput
          label={t('auth.mfa.recoveryCode')}
          value={code}
          onChange={(e) => setCode(e.currentTarget.value)}
          autoComplete="one-time-code"
          autoFocus
        />
      )}
      {method === 'webauthn' && <Text size="sm">{t('auth.mfa.keyHint')}</Text>}
      {rememberDays > 0 && (
        <Checkbox
          label={t('auth.mfa.remember', { days: rememberDays })}
          checked={remember}
          onChange={(e) => setRemember(e.currentTarget.checked)}
        />
      )}
      {method === 'webauthn' ? (
        <Button loading={busy} leftSection={<IconFingerprint size={18} />} onClick={() => void signWithSecurityKey()}>
          {t('auth.mfa.useKey')}
        </Button>
      ) : (
        <Button loading={busy} disabled={method === 'totp' ? code.length !== 6 : code.length < 8} onClick={() => void submitCode(code)}>
          {t('auth.mfa.confirm')}
        </Button>
      )}
      <Stack gap={4}>
        {hasKey && method !== 'webauthn' && (
          <Anchor component="button" type="button" size="sm" onClick={() => setMethod('webauthn')}>
            {t('auth.mfa.otherMethod')}
          </Anchor>
        )}
        {hasTotp && method !== 'totp' && (
          <Anchor component="button" type="button" size="sm" onClick={() => setMethod('totp')}>
            {t('auth.mfa.useApp')}
          </Anchor>
        )}
        {method !== 'recovery' && methods.includes('recovery') && (
          <Anchor component="button" type="button" size="sm" onClick={() => setMethod('recovery')}>
            {t('auth.mfa.useRecovery')}
          </Anchor>
        )}
        <Anchor component="button" type="button" size="sm" c="dimmed" onClick={onBack}>
          {t('auth.back')}
        </Anchor>
      </Stack>
    </Stack>
  );
}

function MfaSetup({ error, onResult, onError }: StepProps) {
  const { t } = useTranslation();
  const [setup, setSetup] = useState<TotpSetup>();
  const [busy, setBusy] = useState(false);

  const startApp = async () => {
    setBusy(true);
    try {
      setSetup(await api.post<TotpSetup>('/auth/mfa/setup/totp', {}));
    } catch (err) {
      onError(err);
    } finally {
      setBusy(false);
    }
  };

  const addKey = async () => {
    setBusy(true);
    try {
      const options = await api.post<WebAuthnOptions>('/auth/mfa/setup/webauthn/options', {});
      const credential = await createKey(options);
      await onResult(await api.post<LoginResult>('/auth/mfa/setup/webauthn', { ceremonyId: options.ceremonyId, credential }));
    } catch (err) {
      if (!(err instanceof Error && err.name === 'NotAllowedError')) onError(err);
    } finally {
      setBusy(false);
    }
  };

  if (setup) {
    return (
      <Stack>
        <Title order={3}>{t('auth.setup.appTitle')}</Title>
        <TotpEnrollment
          setup={setup}
          onConfirm={async (code) => onResult(await api.post<LoginResult>('/auth/mfa/setup/totp/confirm', { code }))}
        />
      </Stack>
    );
  }
  return (
    <Stack gap="md">
      <Title order={3}>{t('auth.setup.title')}</Title>
      <Text size="sm">{t('auth.setup.hint')}</Text>
      {error && (
        <Alert color="red" role="alert">
          {error}
        </Alert>
      )}
      {webauthnSupported() && (
        <Button leftSection={<IconFingerprint size={18} />} loading={busy} onClick={() => void addKey()}>
          {t('auth.setup.key')}
        </Button>
      )}
      <Button variant="default" loading={busy} onClick={() => void startApp()}>
        {t('auth.setup.app')}
      </Button>
    </Stack>
  );
}
