import { Alert, Button, Code, CopyButton, Group, Modal, Stack } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import type { WithToken } from './api';
import { SetupGuide } from './SetupGuide';

/** Токен источника виден только сейчас: после закрытия окна его не узнать, только перевыпустить. */
export function TokenModal({ issued, onClose }: { issued: WithToken; onClose: () => void }) {
  const { t } = useTranslation();
  const s = issued.source.source;
  return (
    <Modal opened onClose={onClose} size="xl" title={t('integrations.token.title', { name: s.name })}>
      <Stack gap="sm">
        <Alert color="yellow" variant="light">
          {t('integrations.token.once')}
        </Alert>
        <Group gap="xs" wrap="nowrap">
          <Code style={{ overflowWrap: 'anywhere', flex: 1 }} data-testid="issued-token">
            {issued.token}
          </Code>
          <CopyButton value={issued.token}>
            {({ copied, copy }) => (
              <Button size="xs" onClick={copy}>
                {copied ? t('integrations.copied') : t('integrations.copy')}
              </Button>
            )}
          </CopyButton>
        </Group>
        <SetupGuide kind={s.kind} token={issued.token} />
        <Group justify="flex-end">
          <Button onClick={onClose}>{t('integrations.token.done')}</Button>
        </Group>
      </Stack>
    </Modal>
  );
}
