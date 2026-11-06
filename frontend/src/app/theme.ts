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
 * Тема Mantine по дизайн-системе «Адмирал» (DESIGN.md). Цвета смысла (статусы, SLA, заметки)
 * живут в CSS-токенах src/app/tokens.css, здесь только то, что нужно компонентам Mantine.
 * Брендирование заказчика (UI-12, PRT-04) заменяет палитру admiral, токены --ei-accent*, --ei-nav-* и логотип.
 */

/** Акцент. Светлая тема берёт оттенок 8 (#14213D, тёмно-синий), тёмная — 3 (#A9BCEB). */
const admiral: MantineColorsTuple = [
  '#eef1f7',
  '#e7ebf3',
  '#c9d3e8',
  '#a9bceb',
  '#7f96c9',
  '#5a74ab',
  '#3f5a8f',
  '#2a4170',
  '#14213d',
  '#0c1529',
];

/** Нейтральные тёмной темы: Mantine берёт отсюда фон полей, рамки и текст. */
const dark: MantineColorsTuple = [
  '#e8ebf2',
  '#b5bdcd',
  '#8d97ab',
  '#5a6584',
  '#33426a',
  '#243150',
  '#18223a',
  '#121a2b',
  '#0c1220',
  '#080d18',
];

/** Залитая кнопка акцентного цвета: текст из токена, чтобы в тёмной теме он был тёмным. */
const onPrimary = (props: { variant?: string; color?: string }) =>
  (props.variant ?? 'filled') === 'filled' && (props.color === undefined || props.color === 'admiral');

const sans = '"Manrope Variable", Manrope, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif';

export const theme = createTheme({
  primaryColor: 'admiral',
  primaryShade: { light: 8, dark: 3 },
  colors: { admiral, dark },
  fontFamily: sans,
  fontFamilyMonospace: '"JetBrains Mono Variable", "JetBrains Mono", ui-monospace, Menlo, monospace',
  fontSizes: { xs: '12px', sm: '13px', md: '14px', lg: '16px', xl: '18px' },
  lineHeights: { xs: '1.34', sm: '1.38', md: '1.43', lg: '1.5', xl: '1.45' },
  headings: {
    fontFamily: sans,
    fontWeight: '700',
    sizes: {
      h1: { fontSize: '24px', lineHeight: '32px' },
      h2: { fontSize: '18px', lineHeight: '26px' },
      h3: { fontSize: '15px', lineHeight: '22px' },
      h4: { fontSize: '14px', lineHeight: '20px' },
    },
  },
  defaultRadius: 'sm',
  radius: { xs: '4px', sm: '8px', md: '10px', lg: '12px', xl: '16px' },
  shadows: {
    xs: '0 1px 2px rgb(18 26 43 / 6%)',
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
    '--mantine-color-anchor': 'var(--ei-link)',
    // Выбранный пункт меню, светлые кнопки и метки: мягкий акцент, заметный в обеих темах.
    '--mantine-primary-color-light': 'var(--ei-accent-soft)',
    '--mantine-primary-color-light-color': 'var(--ei-accent-text)',
  };
  return {
    variables: {},
    light: shared,
    // Акцент тёмной темы светлый (#A9BCEB): белый текст на нём не проходит AA, нужен тёмный.
    dark: { ...shared, '--mantine-primary-color-contrast': 'var(--ei-on-accent)' },
  };
};
