import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import en from './locales/en.json';
import ru from './locales/ru.json';

/** Локализация (UI-09). Новый язык — новый файл в locales без изменения кода. */
const STORAGE_KEY = 'easyitsm.lang';

function savedLanguage(): string {
  try {
    return localStorage.getItem(STORAGE_KEY) ?? 'ru';
  } catch {
    return 'ru';
  }
}

void i18n.use(initReactI18next).init({
  resources: { ru: { translation: ru }, en: { translation: en } },
  lng: savedLanguage(),
  fallbackLng: 'ru',
  interpolation: { escapeValue: false },
});

i18n.on('languageChanged', (lng) => {
  document.documentElement.lang = lng;
  try {
    localStorage.setItem(STORAGE_KEY, lng);
  } catch {
    // хранилище недоступно: язык действует до перезагрузки
  }
});

export default i18n;
