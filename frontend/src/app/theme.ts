import {
  ActionIcon,
  Button,
  Card,
  createTheme,
  Paper,
  type CSSVariablesResolver,
  type MantineColorsTuple,
} from '@mantine/core';

/**
 * Тема Mantine по дизайн-системе «Ясность» (DESIGN.md). Цвета смысла (статусы, SLA, заметки)
 * живут в CSS-токенах src/app/tokens.css, здесь только то, что нужно компонентам Mantine.
 * Брендирование заказчика (UI-12, PRT-04) заменяет палитру sapphire и логотип.
 */

/** Акцент. Светлая тема берёт оттенок 6 (#2B59C3), тёмная — 3 (#7D9BF4). */
const sapphire: MantineColorsTuple = [
  '#eef2fc',
  '#d9e2f8',
  '#b5c6f3',
  '#7d9bf4',
  '#5f82e2',
  '#4269d1',
  '#2b59c3',
  '#224aa5',
  '#1b3c86',
  '#142d66',
];

/** Нейтральные тёмной темы: Mantine берёт отсюда фон полей, рамки и текст. */
const dark: MantineColorsTuple = [
  '#e7eaf0',
  '#b3bac7',
  '#8e96a5',
  '#5f6779',
  '#364051',
  '#262c38',
  '#1b2029',
  '#151921',
  '#0e1117',
  '#0a0d12',
];

/** Залитая кнопка акцентного цвета: текст из токена, чтобы в тёмной теме он был тёмным. */
const onPrimary = (props: { variant?: string; color?: string }) =>
  (props.variant ?? 'filled') === 'filled' && (props.color === undefined || props.color === 'sapphire');

const sans = '"Inter Variable", Inter, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif';

export const theme = createTheme({
  primaryColor: 'sapphire',
  primaryShade: { light: 6, dark: 3 },
  colors: { sapphire, dark },
  fontFamily: sans,
  fontFamilyMonospace: '"JetBrains Mono Variable", "JetBrains Mono", ui-monospace, Menlo, monospace',
  fontSizes: { xs: '12px', sm: '13px', md: '14px', lg: '16px', xl: '18px' },
  lineHeights: { xs: '1.34', sm: '1.38', md: '1.43', lg: '1.5', xl: '1.45' },
  headings: {
    fontFamily: sans,
    fontWeight: '600',
    sizes: {
      h1: { fontSize: '24px', lineHeight: '32px' },
      h2: { fontSize: '18px', lineHeight: '26px' },
      h3: { fontSize: '15px', lineHeight: '22px' },
      h4: { fontSize: '14px', lineHeight: '20px' },
    },
  },
  defaultRadius: 'sm',
  radius: { xs: '4px', sm: '6px', md: '8px', lg: '12px', xl: '16px' },
  shadows: {
    xs: '0 1px 2px rgb(21 25 34 / 6%)',
    sm: 'var(--ei-shadow)',
    md: 'var(--ei-shadow)',
  },
  components: {
    Button: Button.extend({
      vars: (_t, props) => ({ root: onPrimary(props) ? { '--button-color': 'var(--mantine-primary-color-contrast)' } : {} }),
    }),
    ActionIcon: ActionIcon.extend({
      vars: (_t, props) => ({ root: onPrimary(props) ? { '--ai-color': 'var(--mantine-primary-color-contrast)' } : {} }),
    }),
    Card: Card.extend({ defaultProps: { radius: 'lg', withBorder: true } }),
    Paper: Paper.extend({ defaultProps: { radius: 'lg' } }),
  },
});

/** Подменяет базовые цвета Mantine токенами, чтобы фон, текст и рамки совпадали с макетами. */
export const cssVariablesResolver: CSSVariablesResolver = () => {
  const shared = {
    '--mantine-color-body': 'var(--ei-surface)',
    '--mantine-color-text': 'var(--ei-text)',
    '--mantine-color-dimmed': 'var(--ei-text-3)',
    '--mantine-color-default-border': 'var(--ei-border)',
    '--mantine-color-default-hover': 'var(--ei-hover)',
    // Выбранный пункт меню, светлые кнопки и метки: мягкий акцент, заметный в обеих темах.
    '--mantine-primary-color-light': 'var(--ei-accent-soft)',
    '--mantine-primary-color-light-color': 'var(--ei-accent-text)',
  };
  return {
    variables: {},
    light: shared,
    // Акцент тёмной темы светлый (#7D9BF4): белый текст на нём не проходит AA, нужен тёмный.
    dark: { ...shared, '--mantine-primary-color-contrast': 'var(--ei-on-accent)' },
  };
};
