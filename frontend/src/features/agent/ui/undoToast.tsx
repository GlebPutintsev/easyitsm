import { Button, Group, Text } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import i18n from '@/shared/i18n';

let seq = 0;

/**
 * Тост с кнопкой «Отменить» на 10 секунд (UI-04). Полоса показывает, сколько осталось.
 * onUndo вызывается не более одного раза.
 */
export function showUndoToast({ message, onUndo }: { message: string; onUndo: () => Promise<void> | void }) {
  const id = `undo-${++seq}`;
  let done = false;
  notifications.show({
    id,
    autoClose: 10_000,
    withAutoCloseProgress: true,
    message: (
      <Group justify="space-between" wrap="nowrap" gap="sm">
        <Text size="sm">{message}</Text>
        <Button
          size="compact-sm"
          variant="light"
          onClick={() => {
            if (done) return;
            done = true;
            notifications.hide(id);
            void onUndo();
          }}
        >
          {i18n.t('workspace.actions.undo')}
        </Button>
      </Group>
    ),
  });
}

export function showError(error: unknown) {
  const traceId = typeof error === 'object' && error && 'traceId' in error ? (error.traceId as string | undefined) : undefined;
  notifications.show({
    color: 'red',
    title: error instanceof Error ? error.message : i18n.t('states.errorText'),
    message: traceId ? `${i18n.t('states.traceId')}: ${traceId}` : i18n.t('states.errorText'),
  });
}
