import { Alert, Button, Code, Group, Image, PinInput, Stack, Text } from '@mantine/core';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import QRCode from 'qrcode';
import { ApiError } from '@/shared/api/client';

export interface TotpSetup {
  secret: string;
  otpauthUri: string;
}

interface Props {
  setup: TotpSetup;
  onConfirm: (code: string) => Promise<unknown>;
}

/** «Настроить приложение»: QR-код, секрет для ручного ввода и первый код для подтверждения. */
export function TotpEnrollment({ setup, onConfirm }: Props) {
  const { t } = useTranslation();
  const [qr, setQr] = useState<string>();
  const [code, setCode] = useState('');
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    void QRCode.toDataURL(setup.otpauthUri, { margin: 1, width: 200 }).then(setQr);
  }, [setup.otpauthUri]);

  const submit = async (value: string) => {
    setBusy(true);
    setError(undefined);
    try {
      await onConfirm(value);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : t('states.errorText'));
      setCode('');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Stack align="center">
      <Text size="sm" ta="center">
        {t('auth.totp.scan')}
      </Text>
      {qr && <Image src={qr} w={200} h={200} alt={t('auth.totp.qrAlt')} />}
      <Text size="xs" c="dimmed">
        {t('auth.totp.manual')}
      </Text>
      <Code>{setup.secret.replace(/(.{4})/g, '$1 ').trim()}</Code>
      <Text size="sm">{t('auth.totp.enterCode')}</Text>
      <PinInput
        length={6}
        type="number"
        oneTimeCode
        value={code}
        onChange={setCode}
        onComplete={(v) => void submit(v)}
        disabled={busy}
        aria-label={t('auth.mfa.code')}
      />
      {error && (
        <Alert color="red" w="100%">
          {error}
        </Alert>
      )}
      <Group>
        <Button loading={busy} disabled={code.length !== 6} onClick={() => void submit(code)}>
          {t('auth.mfa.confirm')}
        </Button>
      </Group>
    </Stack>
  );
}
