import { Alert, Button, Code, CopyButton, Group, SimpleGrid, Stack, Text } from '@mantine/core';
import { IconCopy, IconDownload, IconKey } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';

/** Резервные коды показываются один раз: их нужно сохранить. */
export function RecoveryCodes({ codes, onDone }: { codes: string[]; onDone?: () => void }) {
  const { t } = useTranslation();
  const text = codes.join('\n');
  const download = () => {
    const url = URL.createObjectURL(new Blob([text + '\n'], { type: 'text/plain' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = 'easyitsm-recovery-codes.txt';
    a.click();
    URL.revokeObjectURL(url);
  };
  return (
    <Stack>
      <Alert icon={<IconKey />} color="yellow" title={t('auth.recovery.title')}>
        {t('auth.recovery.hint')}
      </Alert>
      <SimpleGrid cols={2} spacing="xs" data-testid="recovery-codes">
        {codes.map((c) => (
          <Code key={c} fz="md" ta="center">
            {c}
          </Code>
        ))}
      </SimpleGrid>
      <Group>
        <CopyButton value={text}>
          {({ copied, copy }) => (
            <Button variant="default" leftSection={<IconCopy size={16} />} onClick={copy}>
              {copied ? t('auth.recovery.copied') : t('auth.recovery.copy')}
            </Button>
          )}
        </CopyButton>
        <Button variant="default" leftSection={<IconDownload size={16} />} onClick={download}>
          {t('auth.recovery.download')}
        </Button>
      </Group>
      {onDone && (
        <Button onClick={onDone}>
          <Text inherit>{t('auth.recovery.saved')}</Text>
        </Button>
      )}
    </Stack>
  );
}
