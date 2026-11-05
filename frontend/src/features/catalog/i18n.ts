import i18n from '@/shared/i18n';
import en from './locales/en.json';
import ru from './locales/ru.json';

/**
 * Тексты каталога, портала и согласований подключаются отдельным пакетом поверх общих файлов локализации:
 * параллельные модули не правят один и тот же ru.json.
 */
i18n.addResourceBundle('ru', 'translation', ru, true, false);
i18n.addResourceBundle('en', 'translation', en, true, false);
