import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { theme } from '@/app/theme';
import { clearPeopleCache } from '@/shared/api/people';
import { AuditPage } from './AuditPage';
import { dayBound, type AuditEvent } from './api';

const ME = '00000000-0000-7000-8000-000000000001';
const ACTOR = '00000000-0000-7000-8000-000000000002';

const event = (over: Partial<AuditEvent> = {}): AuditEvent => ({
  id: '00000000-0000-7000-8000-0000000000e1',
  seq: 42,
  at: '2026-11-06T08:00:00Z',
  category: 'access',
  action: 'access.denied',
  result: 'denied',
  actorId: ACTOR,
  actorLogin: 'ivanov',
  objectType: 'tickets/{ref}',
  objectId: 'INC-000777',
  ip: '10.0.0.5',
  userAgent: 'Firefox',
  traceId: 'abc123',
  details: { method: 'GET', status: 404 },
  prevHash: '0'.repeat(64),
  hash: 'f'.repeat(64),
  ...over,
});

if (!('fonts' in document)) {
  Object.defineProperty(document, 'fonts', { value: { addEventListener() {}, removeEventListener() {}, ready: Promise.resolve() } });
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

describe('Журнал аудита', () => {
  const fetchMock = vi.fn<typeof fetch>();
  let verifyResult: unknown = { valid: true, checked: 42, headSeq: 42, headHash: 'f'.repeat(64), brokenAtSeq: null, problem: null, verifiedAt: '2026-11-06T08:01:00Z' };

  beforeEach(() => {
    clearPeopleCache();
    fetchMock.mockReset();
    fetchMock.mockImplementation(async (input, init) => {
      const url = new URL(String(input));
      const method = init?.method ?? 'GET';
      if (url.pathname === '/api/v1/me') return json({ id: ME, displayName: 'Безопасник', roles: ['SEC'] });
      if (url.pathname === '/api/v1/people') return json([{ id: ACTOR, displayName: 'Иван Иванов', email: null, active: true }]);
      if (url.pathname === '/api/v1/audit/events') {
        const items = url.searchParams.get('result') === 'success' ? [] : [event()];
        return json({ items, nextCursor: null });
      }
      if (url.pathname === '/api/v1/audit/siem') {
        return json({ enabled: true, transport: 'TLS', format: 'CEF', target: 'siem.corp:6514', headSeq: 42, deliveredSeq: 40, lag: 2, lastError: null });
      }
      if (url.pathname === '/api/v1/audit/verify' && method === 'POST') return json(verifyResult);
      return json({ status: 404, code: 'not_found' }, 404);
    });
    vi.stubGlobal('fetch', fetchMock);
  });
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  function renderPage() {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    return render(
      <MantineProvider theme={theme} env="test">
        <QueryClientProvider client={qc}>
          <AuditPage />
        </QueryClientProvider>
      </MantineProvider>,
    );
  }

  it('показывает записи с понятными названиями, именем и подробностями', async () => {
    renderPage();
    expect(await screen.findByText('Отказ в доступе')).toBeInTheDocument();
    expect(await screen.findByText('Иван Иванов')).toBeInTheDocument();
    const table = screen.getByRole('table');
    expect(within(table).getByText('tickets/{ref} · INC-000777')).toBeInTheDocument();
    expect(within(table).getByText('Отказано')).toBeInTheDocument();
    expect(await screen.findByText('siem.corp:6514')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('row', { name: 'Запись № 42' }));
    const drawer = await screen.findByRole('dialog');
    expect(within(drawer).getByText('access.denied')).toBeInTheDocument();
    expect(within(drawer).getByText('abc123')).toBeInTheDocument();
    expect(within(drawer).getByText('404')).toBeInTheDocument();
  });

  it('фильтр по результату уходит на сервер, пустой результат предлагает сбросить фильтры', async () => {
    renderPage();
    await screen.findByText('Отказ в доступе');
    fireEvent.click(within(screen.getByRole('search')).getAllByLabelText('Результат')[0]!);
    fireEvent.click(await screen.findByRole('option', { name: 'Успешно', hidden: true }));
    expect(await screen.findByRole('button', { name: 'Сбросить фильтры' })).toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([u]) => String(u).includes('result=success'))).toBe(true);
  });

  it('проверка целостности сообщает о нарушении', async () => {
    verifyResult = { valid: false, checked: 10, headSeq: 42, headHash: 'a', brokenAtSeq: 11, problem: 'hash_mismatch', verifiedAt: '2026-11-06T08:01:00Z' };
    renderPage();
    fireEvent.click(screen.getByRole('button', { name: 'Проверить целостность' }));
    expect(await screen.findByText('Цепочка нарушена на записи № 11: запись изменена')).toBeInTheDocument();
  });

  it('выгрузка требует причину', async () => {
    renderPage();
    fireEvent.click(screen.getByRole('button', { name: 'Экспорт' }));
    const submit = await screen.findByRole('button', { name: 'Выгрузить CSV' });
    expect(submit).toBeDisabled();
    fireEvent.change(screen.getByRole('textbox', { name: /Причина выгрузки/ }), { target: { value: 'Запрос СБ № 42' } });
    await waitFor(() => expect(submit).toBeEnabled());
  });

  it('граница периода включает весь день «по»', () => {
    const from = new Date(dayBound('2026-11-06')!);
    const to = new Date(dayBound('2026-11-06', true)!);
    expect(to.getTime() - from.getTime()).toBe(24 * 3600 * 1000);
    expect(dayBound(undefined)).toBeUndefined();
  });
});
