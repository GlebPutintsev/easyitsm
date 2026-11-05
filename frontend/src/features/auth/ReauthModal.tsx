import { Alert, Button, Group, Modal, PasswordInput, PinInput, Stack, Text } from '@mantine/core';
import { IconFingerprint } from '@tabler/icons-react';
import { useCallback, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api, ApiError } from '@/shared/api/client';
import type { Me, WebAuthnOptions } from './api';
import { signWithKey, webauthnSupported } from './webauthn';

export interface ReauthController {
  opened: boolean;
  request: () => Promise<boolean>;
  resolve: (ok: boolean) => void;
}

/** Окно подтверждения входа открывается из API-клиента и возвращает результат через промис. */
export function useReauthController(): ReauthController {
  const [opened, setOpened] = useState(false);
  const pending = useRef<((ok: boolean) => void) | null>(null);
  const request = useCallback(
    () =>
      new Promise<boolean>((resolve) => {
        pending.current?.(false);
        pending.current = resolve;
        setOpened(true);
      }),
    [],
  );
  const resolve = useCallback((ok: boolean) => {
    pending.current?.(ok);
    pending.current = null;
    setOpened(false);
  }, []);
  return { opened, request, resolve };
}

/** Повторная аутентификация перед опасным действием (SEC-08): пароль, код приложения или ключ. */
export function ReauthModal({ controller, me }: { controller: ReauthController; me: Me }) {
  const { t } = useTranslation();
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const usePassword = me.security.passwordSet && me.source !== 'oidc';

  const close = (ok: boolean) => {
    setPassword('');
    setCode('');
    setError(undefined);
    controller.resolve(ok);
  };

  const submit = async (body: Record<string, unknown>) => {
    setBusy(true);
    setError(undefined);
    try {
      await api.post('/auth/reauth', body);
      close(true);
    } catch (e) {
      setError(e instanceof ApiError && e.status === 401 ? t('auth.reauth.invalid') : t('auth.unavailable'));
    } finally {
      setBusy(false);
    }
  };

  const withKey = async () => {
    try {
      const options = await api.post<WebAuthnOptions>('/auth/reauth/webauthn/options', {});
      const credential = await signWithKey(options);
      await submit({ ceremonyId: options.ceremonyId, credential });
    } catch (e) {
      if (!(e instanceof Error && e.name === 'NotAllowedError')) setError(t('auth.reauth.invalid'));
    }
  };

  return (
    <Modal opened={controller.opened} onClose={() => close(false)} title={t('auth.reauth.title')} centered>
      <Stack>
        <Text size="sm">{t('auth.reauth.hint')}</Text>
        {error && <Alert color="red">{error}</Alert>}
        {usePassword && (
          <PasswordInput
            label={t('auth.password')}
            value={password}
            onChange={(e) => setPassword(e.currentTarget.value)}
            autoComplete="current-password"
            data-autofocus
          />
        )}
        {!usePassword && me.security.totp && (
          <PinInput length={6} type="number" oneTimeCode value={code} onChange={setCode} aria-label={t('auth.mfa.code')} />
        )}
        <Group justify="space-between">
          {me.security.keys > 0 && webauthnSupported() ? (
            <Button variant="subtle" leftSection={<IconFingerprint size={16} />} onClick={() => void withKey()}>
              {t('auth.reauth.key')}
            </Button>
          ) : (
            <span />
          )}
          <Group>
            <Button variant="default" onClick={() => close(false)}>
              {t('actions.cancel')}
            </Button>
            <Button
              loading={busy}
              disabled={usePassword ? !password : code.length !== 6}
              onClick={() => void submit(usePassword ? { password } : { code })}
            >
              {t('auth.mfa.confirm')}
            </Button>
          </Group>
        </Group>
      </Stack>
    </Modal>
  );
}
