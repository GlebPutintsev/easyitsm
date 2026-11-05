import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import '@mantine/core/styles.css';
import '@mantine/notifications/styles.css';
// Шрифты в сборке, без обращений к внешним CDN: продукт работает в закрытом контуре.
import '@fontsource-variable/inter';
import '@fontsource-variable/jetbrains-mono';
import './app/tokens.css';
import './shared/i18n';
import { App } from './app/App';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
