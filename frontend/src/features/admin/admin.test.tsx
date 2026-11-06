import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cssVariablesResolver, theme } from '@/app/theme';
import '@/shared/i18n';
import { recordVersion } from './api';
import { ImportPage } from './ImportPage';
import { DiffTable, SaveBar } from './ui/History';

function renderPage(ui: ReactElement) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MantineProvider theme={theme} cssVariablesResolver={cssVariablesResolver} env="test">
        <MemoryRouter>{ui}</MemoryRouter>
      </MantineProvider>
    </QueryClientProvider>,
  );
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

const job = {
  id: 'job-1', kind: 'users', status: 'preview', fileName: 'staff.csv', totalRows: 2, validRows: 1, errorRows: 1, skipRows: 0,
  columns: ['login', 'display_name'], appliedAt: null, rolledBackAt: null, createdAt: '2026-11-06T08:00:00Z', result: {},
  rows: [
    { line: 2, values: { login: 'anna', display_name: 'Анна' }, action: 'create', errors: [], warnings: [] },
    { line: 3, values: { login: 'bad login', display_name: 'Плохой' }, action: 'error', errors: ['Логин без пробелов'], warnings: [] },
  ],
};

describe('«Управление»', () => {
  const fetchMock = vi.fn<typeof fetch>();
  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
  });
  afterEach(() => vi.unstubAllGlobals());

  it('импорт показывает ошибки строк и применяет только с пропуском ошибочных', async () => {
    fetchMock.mockImplementation(async (input, init) => {
      const url = new URL(String(input));
      if (url.pathname === '/api/v1/admin/imports' && init?.method === 'GET') return json({ items: [], nextCursor: null });
      if (url.pathname === '/api/v1/admin/imports') return json(job, 201);
      if (url.pathname === '/api/v1/admin/imports/job-1/apply') {
        expect(url.searchParams.get('skipInvalid')).toBe('true');
        return json({ ...job, status: 'applied', result: { user: 1 } });
      }
      return json({}, 404);
    });
    renderPage(<ImportPage />);
    const file = new File(['login;display_name\nanna;Анна\n'], 'staff.csv', { type: 'text/csv' });
    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    fireEvent.change(input, { target: { files: [file] } });
    fireEvent.click(await screen.findByRole('button', { name: 'Проверить файл' }));

    expect(await screen.findByText('Логин без пробелов')).toBeInTheDocument();
    const apply = screen.getByRole('button', { name: 'Применить (1)' });
    expect(apply).toBeDisabled();
    fireEvent.click(screen.getByLabelText('Пропустить строки с ошибками'));
    fireEvent.click(apply);
    expect(await screen.findByRole('button', { name: 'Откатить импорт' })).toBeInTheDocument();
  });

  it('сравнение версий показывает было и стало', () => {
    renderPage(<DiffTable changes={[{ path: 'cells.high.high', kind: 'changed', before: 'P2', after: 'P1' }]} />);
    expect(screen.getByText('cells.high.high')).toBeInTheDocument();
    expect(screen.getByText('P2')).toBeInTheDocument();
    expect(screen.getByText('P1')).toBeInTheDocument();
  });

  it('«Сохранить» и «Отменить» доступны только при изменениях', () => {
    const { rerender } = renderPage(<SaveBar dirty={false} onSave={() => {}} onCancel={() => {}} onHistory={() => {}} />);
    expect(screen.getByRole('button', { name: 'Сохранить' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'История' })).toBeEnabled();
    rerender(
      <QueryClientProvider client={new QueryClient()}>
        <MantineProvider theme={theme} env="test">
          <SaveBar dirty onSave={() => {}} onCancel={() => {}} />
        </MantineProvider>
      </QueryClientProvider>,
    );
    expect(screen.getByRole('button', { name: 'Сохранить' })).toBeEnabled();
  });

  it('версия чужой настройки уходит в журнал, а ошибка записи не ломает сохранение', async () => {
    fetchMock.mockResolvedValueOnce(json({}, 500));
    await expect(recordVersion('tickets.priority_matrix', '', 'Матрица', 'update', { cells: {} })).resolves.toBeUndefined();
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(String(url)).toContain('/api/v1/admin/config-versions');
    expect(JSON.parse(String(init?.body))).toMatchObject({ entity: 'tickets.priority_matrix', action: 'update' });
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
  });
});
