import { Alert } from '@mantine/core';
import { useLocalStorage } from '@mantine/hooks';
import { IconFlask } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { Outlet } from 'react-router';
import { usingMocks } from './api';
import { ShortcutsHelp } from './ShortcutsHelp';
import './agent.css';

/** Обёртка маршрутов рабочего места: справка по клавишам и пометка демо-данных. */
export function AgentWorkspace() {
  const { t } = useTranslation();
  const [hideDemo, setHideDemo] = useLocalStorage({ key: 'easyitsm.agent.hideDemo', defaultValue: false, getInitialValueInEffect: false });
  return (
    <>
      {usingMocks && !hideDemo && (
        <Alert variant="light" color="grape" icon={<IconFlask size={18} />} mb="sm" py={6} withCloseButton onClose={() => setHideDemo(true)} closeButtonLabel={t('workspace.actions.close')}>
          {t('workspace.demo')}
        </Alert>
      )}
      <Outlet />
      <ShortcutsHelp />
    </>
  );
}
