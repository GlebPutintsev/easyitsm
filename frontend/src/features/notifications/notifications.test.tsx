import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { theme } from '@/app/theme';
import { NotificationBell } from './NotificationBell';

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

const items = [
  {
    id: 'n1', kind: 'ticket_assigned', title: 'Вам назначена заявка INC-000042', body: 'Не работает VPN',
    link: '/agent/tickets/INC-000042', ticketId: 't1', read: false, createdAt: new Date().toISOString(),
  },
  {
    id: 'n2', kind: 'ticket_reply', title: 'Ответ по заявке INC-000040', body: '', link: null, ticketId: 't2',
    read: true, createdAt: '2026-11-01T08:00:00Z',
  },
];

describe('Центр уведомлений', () => {
  const fetchMock = vi.fn<typeof fetch>();

  beforeEach(() => {
    fetchMock.mockReset();
    fetchMock.mockImplementation(async (input, init) => {
      const url = new URL(String(input));
      const method = init?.method ?? 'GET';
      if (url.pathname === '/api/v1/notifications/unread-count') return json({ count: 1 });
      if (url.pathname === '/api/v1/notifications' && method === 'GET') return json({ items, nextCursor: null });
      if (url.pathname === '/api/v1/notifications/push') return json({ enabled: true, publicKey: 'BPk', subscriptions: 0 });
      if (url.pathname.endsWith('/read') && method === 'POST') return new Response(null, { status: 204 });
      if (url.pathname === '/api/v1/notifications/read-all') return json({ updated: 1 });
      return json({ status: 404, code: 'not_found' }, 404);
    });
    vi.stubGlobal('fetch', fetchMock);
  });
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  function renderBell() {
    const router = createMemoryRouter(
      [
        { path: '/', element: <NotificationBell /> },
        { path: '/agent/tickets/:number', element: <p data-testid="ticket-page" /> },
      ],
      { initialEntries: ['/'] },
    );
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    return render(
      <MantineProvider theme={theme} env="test">
        <QueryClientProvider client={qc}>
          <RouterProvider router={router} />
        </QueryClientProvider>
      </MantineProvider>,
    );
  }

  it('показывает счётчик, список и по нажатию отмечает прочитанным и открывает ссылку', async () => {
    renderBell();
    const bell = await screen.findByRole('button', { name: '1 непрочитанное уведомление' });
    fireEvent.click(bell);
    const item = await screen.findByText('Вам назначена заявка INC-000042');
    expect(screen.getByText('Ответ по заявке INC-000040')).toBeInTheDocument();
    fireEvent.click(item);
    expect(await screen.findByTestId('ticket-page')).toBeInTheDocument();
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some(([u, i]) => String(u).endsWith('/notifications/n1/read') && i?.method === 'POST'),
      ).toBe(true),
    );
  });

  it('«Прочитать все» уходит одним запросом', async () => {
    renderBell();
    fireEvent.click(await screen.findByRole('button', { name: '1 непрочитанное уведомление' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Прочитать все' }));
    await waitFor(() =>
      expect(fetchMock.mock.calls.some(([u, i]) => String(u).endsWith('/notifications/read-all') && i?.method === 'POST')).toBe(true),
    );
  });
});
