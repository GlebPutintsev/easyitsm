import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { theme } from '@/app/theme';
import { clearPeopleCache } from '@/shared/api/people';
import type { Asset, AssetRow } from './api';
import { AssetPage } from './AssetPage';
import { AssetsPage } from './AssetsPage';
import { patchOf } from './ui';

const ME = '00000000-0000-7000-8000-000000000001';
const HOLDER = '00000000-0000-7000-8000-000000000002';
const ID = '00000000-0000-7000-8000-0000000000a1';

const row = (over: Partial<AssetRow> = {}): AssetRow => ({
  id: ID,
  tag: 'NB-0042',
  name: 'Ноутбук Lenovo',
  kind: 'laptop',
  model: 'ThinkPad T14',
  serial: 'SN123',
  state: 'in_use',
  holder: { id: HOLDER, name: 'Мария Сотрудник' },
  location: { id: 'l1', name: 'Офис, 3 этаж' },
  warrantyTo: '2028-01-15',
  cost: 95000,
  currency: 'RUB',
  ...over,
});

const asset = (over: Partial<Asset> = {}): Asset => ({
  ...row(),
  manufacturer: 'Lenovo',
  ci: null,
  vendor: null,
  contract: null,
  purchasedOn: '2026-01-15',
  warrantyExpired: false,
  serviceLifeMonths: 36,
  endOfLife: '2029-01-15',
  endOfLifeReached: false,
  notes: '',
  retireReason: null,
  retiredAt: null,
  createdAt: '2026-11-06T08:00:00Z',
  updatedAt: '2026-11-06T08:00:00Z',
  version: 3,
  ...over,
});

if (!('fonts' in document)) {
  Object.defineProperty(document, 'fonts', { value: { addEventListener() {}, removeEventListener() {}, ready: Promise.resolve() } });
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

describe('Активы', () => {
  const fetchMock = vi.fn<typeof fetch>();
  let roles = ['AST'];
  let current = asset();

  beforeEach(() => {
    clearPeopleCache();
    roles = ['AST'];
    current = asset();
    fetchMock.mockReset();
    fetchMock.mockImplementation(async (input, init) => {
      const url = new URL(String(input));
      const method = init?.method ?? 'GET';
      const path = url.pathname;
      if (path === '/api/v1/me') return json({ id: ME, displayName: 'Менеджер', roles });
      if (path === '/api/v1/assets/stats') {
        return json({ total: 3, inStock: 1, inUse: 1, inRepair: 1, retired: 0, warrantyExpiring: 2, endOfLife: 0, cost: [] });
      }
      if (path === '/api/v1/assets') {
        const items = url.searchParams.get('state') === 'retired' ? [] : [row()];
        return json({ items, nextCursor: null });
      }
      if (path === '/api/v1/asset-locations' || path === '/api/v1/vendors' || path === '/api/v1/contracts') return json([]);
      if (path === `/api/v1/assets/${ID}`) return json(current);
      if (path === `/api/v1/assets/${ID}/history`) {
        return json([
          { id: 'e1', kind: 'issued', fromState: 'in_stock', toState: 'in_use', fromHolder: null, toHolder: { id: HOLDER, name: 'Мария Сотрудник' }, ticket: null, comment: 'Новому сотруднику', actor: { id: ME, name: 'Менеджер' }, at: '2026-11-06T08:00:00Z' },
        ]);
      }
      if (path === `/api/v1/assets/${ID}/tickets` && method === 'GET') return json([]);
      if (path === `/api/v1/assets/${ID}/licenses`) return json([]);
      if (path === `/api/v1/assets/${ID}/retire` && method === 'POST') {
        current = asset({ state: 'retired', holder: null, retireReason: 'Утерян', retiredAt: '2026-11-06T09:00:00Z', version: 4 });
        return json(current);
      }
      return json({ status: 404, code: 'not_found' }, 404);
    });
    vi.stubGlobal('fetch', fetchMock);
  });
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  function renderAt(path: string) {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    return render(
      <MantineProvider theme={theme} env="test">
        <QueryClientProvider client={qc}>
          <MemoryRouter initialEntries={[path]}>
            <Routes>
              <Route path="/agent/assets" element={<AssetsPage />} />
              <Route path="/agent/assets/:id" element={<AssetPage />} />
            </Routes>
          </MemoryRouter>
        </QueryClientProvider>
      </MantineProvider>,
    );
  }

  it('список показывает номер, состояние словом, сотрудника и сводку', async () => {
    renderAt('/agent/assets');
    const table = await screen.findByRole('table');
    expect(within(table).getByText('NB-0042')).toBeInTheDocument();
    expect(within(table).getByText('Выдан')).toBeInTheDocument();
    expect(within(table).getByText('Мария Сотрудник')).toBeInTheDocument();
    expect(screen.getByText('Гарантия истекает за 30 дней')).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Добавить актив' })).toBeInTheDocument();
  });

  it('плитка сводки фильтрует по состоянию на сервере', async () => {
    renderAt('/agent/assets');
    await screen.findByRole('table');
    fireEvent.click(screen.getByRole('button', { name: /В ремонте/ }));
    await waitFor(() => expect(fetchMock.mock.calls.some(([u]) => String(u).includes('state=in_repair'))).toBe(true));
  });

  it('агент видит список без кнопки добавления', async () => {
    roles = ['AGT'];
    renderAt('/agent/assets');
    await screen.findByRole('table');
    await waitFor(() => expect(fetchMock.mock.calls.some(([u]) => String(u).includes('/api/v1/me'))).toBe(true));
    expect(screen.queryByRole('button', { name: 'Добавить актив' })).not.toBeInTheDocument();
  });

  it('карточка выданного актива предлагает действия по состоянию, списание требует причину', async () => {
    renderAt(`/agent/assets/${ID}`);
    expect(await screen.findByRole('heading', { name: 'Ноутбук Lenovo' })).toBeInTheDocument();
    expect(await screen.findByText('Выдан: Мария Сотрудник')).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Передать' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Вернуть' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Выдать' })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Списать' }));
    const dialog = await screen.findByRole('dialog');
    const submit = within(dialog).getByRole('button', { name: 'Списать' });
    expect(submit).toBeDisabled();
    fireEvent.change(within(dialog).getByRole('textbox', { name: /Причина списания/ }), { target: { value: 'Утерян' } });
    await waitFor(() => expect(submit).toBeEnabled());
    fireEvent.click(submit);
    expect(await screen.findByText(/Списан .*: Утерян/)).toBeInTheDocument();
    const call = fetchMock.mock.calls.find(([u]) => String(u).endsWith('/retire'));
    expect(new Headers(call?.[1]?.headers).get('If-Match')).toBe('"3"');
  });

  it('изменение отправляет только изменённое, очищенное — в clear', () => {
    const initial = { name: 'Ноутбук', serial: 'SN1', cost: 100 as number | '', model: '' };
    expect(patchOf({ name: 'Ноутбук', serial: '', cost: 100, model: 'T14' }, initial, ['serial', 'cost', 'model'])).toEqual({
      model: 'T14',
      clear: ['serial'],
    });
    expect(patchOf({ name: 'Новый', serial: '' }, null, ['serial'])).toEqual({ name: 'Новый' });
  });
});
