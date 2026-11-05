import { MantineProvider } from '@mantine/core';
import { render } from '@testing-library/react';
import type { ReactElement } from 'react';
import { cssVariablesResolver, theme } from '@/app/theme';

export function renderWithProviders(ui: ReactElement) {
  return render(
    <MantineProvider theme={theme} cssVariablesResolver={cssVariablesResolver} env="test">
      {ui}
    </MantineProvider>,
  );
}
