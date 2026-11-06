import i18n from '@/shared/i18n';
import en from './locales/en.json';
import ru from './locales/ru.json';

/** Тексты автоматизации подключаются отдельным пакетом поверх общих файлов локализации, как у каталога. */
i18n.addResourceBundle('ru', 'translation', ru, true, false);
i18n.addResourceBundle('en', 'translation', en, true, false);
