import { ActionIcon, Select, useComputedColorScheme, useMantineColorScheme } from '@mantine/core';
import { IconMoon, IconSun } from '@tabler/icons-react';
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { BrandMark } from '@/shared/ui/BrandMark';
import './auth.css';

/**
 * Раскладка экранов входа (макет «Адмирал», вход): слева тёмно-синяя панель со знаком и назначением
 * системы, справа язык, тема и форма в карточке. На телефоне панель сжимается в полосу над формой.
 */
export function AuthLayout({ children }: { children: ReactNode }) {
  const { t, i18n } = useTranslation();
  const { setColorScheme } = useMantineColorScheme();
  const dark = useComputedColorScheme('light') === 'dark';
  return (
    <div className="ea-shell">
      <section className="ea-side ei-chrome" aria-label={t('auth.side.label')}>
        <svg className="ea-rings" width="420" height="420" viewBox="0 0 420 420" aria-hidden="true">
          <circle cx="210" cy="210" r="200" />
          <circle cx="210" cy="210" r="150" />
          <circle cx="210" cy="210" r="100" />
          <path d="M210 10v400M10 210h400" />
          <circle cx="210" cy="60" r="5" className="ea-rings-dot" />
        </svg>
        <div className="ea-brand">
          <BrandMark size={34} />
          Easy ITSM
        </div>
        <div className="ea-pitch">
          <h1>{t('auth.side.title')}</h1>
          <p>{t('auth.side.text')}</p>
        </div>
        <div className="ea-rule" aria-hidden="true" />
      </section>

      <section className="ea-main">
        <div className="ea-tools">
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
          <ActionIcon
            variant="default"
            size={30}
            aria-label={t(dark ? 'auth.side.lightTheme' : 'auth.side.darkTheme')}
            onClick={() => setColorScheme(dark ? 'light' : 'dark')}
          >
            {dark ? <IconSun size={16} /> : <IconMoon size={16} />}
          </ActionIcon>
        </div>
        <div className="ea-center">
          <div className="ea-card">{children}</div>
        </div>
        <p className="ea-foot">Easy ITSM</p>
      </section>
    </div>
  );
}
