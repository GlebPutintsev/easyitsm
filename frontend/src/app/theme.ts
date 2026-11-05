import { createTheme } from '@mantine/core';

/**
 * Дизайн-токены продукта. Брендирование заказчика (UI-12, PRT-04) переопределяет
 * primaryColor и логотип, контрастность проверяется автоматически.
 */
export const theme = createTheme({
  primaryColor: 'blue',
  defaultRadius: 'md',
  fontFamily: 'Inter, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif',
  headings: { fontWeight: '600' },
  components: {
    Button: { defaultProps: { radius: 'md' } },
  },
});
