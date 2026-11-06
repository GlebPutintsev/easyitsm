import i18n from '@/shared/i18n';
import en from './locales/en.json';
import ru from './locales/ru.json';

/** Тексты глобального поиска отдельным пакетом: общие ru.json/en.json не правим. */
i18n.addResourceBundle('ru', 'translation', ru, true, false);
i18n.addResourceBundle('en', 'translation', en, true, false);
