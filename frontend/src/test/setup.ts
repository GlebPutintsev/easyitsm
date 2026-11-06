import '@testing-library/jest-dom/vitest';
import { configure } from '@testing-library/react';
import '@/shared/i18n';

// По умолчанию findBy*/waitFor ждут 1 с. Первая отрисовка очереди с таблицей на загруженном раннере CI
// занимает дольше (1,4 с в PR #24), и тест падал, хотя экран был верным. 5 с хватает с запасом.
configure({ asyncUtilTimeout: 5000 });

// jsdom не реализует matchMedia и ResizeObserver, которые нужны Mantine.
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  }),
});
globalThis.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
};
