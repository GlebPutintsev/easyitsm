import { Button, Group, Menu, Modal, Stack, Text, Textarea } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconDownload, IconFileSpreadsheet, IconFileText, IconFileTypePdf } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@/shared/api/client';
import type { ExportFormat } from './api';

const FORMATS: { id: ExportFormat; icon: typeof IconFileText }[] = [
  { id: 'xlsx', icon: IconFileSpreadsheet },
  { id: 'csv', icon: IconFileText },
  { id: 'pdf', icon: IconFileTypePdf },
];

/**
 * Выгрузка (RPT-04): формат из меню. Сервер может попросить подтвердить вход (SEC-08) — это делает
 * API-клиент — или причину, если строк больше лимита (10.6).
 */
export function ExportMenu({
  onExport,
  disabled,
  size = 'sm',
}: {
  onExport: (format: ExportFormat, reason?: string) => Promise<void>;
  disabled?: boolean;
  size?: 'xs' | 'sm';
}) {
  const { t } = useTranslation();
  const [busy, setBusy] = useState<ExportFormat | null>(null);
  const [askReason, setAskReason] = useState<{ format: ExportFormat; detail: string } | null>(null);
  const [reason, setReason] = useState('');

  const run = async (format: ExportFormat, why?: string) => {
    setBusy(format);
    try {
      await onExport(format, why);
      setAskReason(null);
      setReason('');
    } catch (e) {
      if (e instanceof ApiError && e.code === 'export_reason_required') {
        setAskReason({ format, detail: e.message });
      } else if (!(e instanceof ApiError && e.code === 'reauth_required')) {
        notifications.show({
          color: 'red',
          title: t('reports.export.failed'),
          message: e instanceof ApiError ? `${e.message}${e.traceId ? ` · ${e.traceId}` : ''}` : t('states.errorText'),
        });
      }
    } finally {
      setBusy(null);
    }
  };

  return (
    <>
      <Menu position="bottom-end" withinPortal>
        <Menu.Target>
          <Button variant="default" size={size} leftSection={<IconDownload size={16} />} loading={busy !== null} disabled={disabled}>
            {t('reports.export.button')}
          </Button>
        </Menu.Target>
        <Menu.Dropdown>
          {FORMATS.map((f) => (
            <Menu.Item key={f.id} leftSection={<f.icon size={16} />} onClick={() => run(f.id)}>
              {t(`reports.export.${f.id}`)}
            </Menu.Item>
          ))}
        </Menu.Dropdown>
      </Menu>
      <Modal opened={askReason !== null} onClose={() => setAskReason(null)} title={t('reports.export.reasonTitle')}>
        <Stack>
          <Text size="sm">{askReason?.detail}</Text>
          <Textarea
            label={t('reports.export.reasonLabel')}
            value={reason}
            onChange={(e) => setReason(e.currentTarget.value)}
            maxLength={500}
            autosize
            minRows={2}
            data-autofocus
          />
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setAskReason(null)}>
              {t('actions.cancel')}
            </Button>
            <Button
              disabled={!reason.trim()}
              loading={busy !== null}
              onClick={() => askReason && run(askReason.format, reason.trim())}
            >
              {t('reports.export.confirm')}
            </Button>
          </Group>
        </Stack>
      </Modal>
    </>
  );
}
