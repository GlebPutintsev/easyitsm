import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { theme } from '@/app/theme';
import { clearPeopleCache } from '@/shared/api/people';
import type { ActivityEntry, TicketDetails } from '@/features/tickets/api';
import { progressIndex, progressSteps } from './requester';
import { TicketPage } from './TicketPage';

const ME = '00000000-0000-7000-8000-000000000001';
const AGENT = '00000000-0000-7000-8000-000000000002';
const TID = '00000000-0000-7000-8000-0000000000aa';

function ticket(over: Partial<TicketDetails> = {}): TicketDetails {
  return {
    id: TID, number: 'INC-000042', type: 'incident', title: 'Не работает VPN',
    status: { code: 'resolved', name: 'Решена', category: 'resolved' },
    priority: 'P3', impact: 'low', urgency: 'low', priorityManual: false,
    requesterId: ME, createdForId: null, organizationId: null, groupId: null, assigneeId: AGENT,
    serviceId: null, ciId: null, categoryId: null, catalogItemId: null, parentId: null, channel: 'portal',
    confidential: false, securityGroupId: null, dueAt: null, slaDueAt: null, awaitingAgent: false,
    pendingReason: null, pendingUntil: null, resolutionCode: null, resolutionText: 'Перезапустили шлюз',
    reopenCount: 0, escalationLevel: 0,
    identityCheck: { required: false, verifiedAt: null, verifiedBy: null, method: null },
    custom: {}, firstResponseAt: null, resolvedAt: '2026-11-06T08:00:00Z', closedAt: null,
    createdAt: '2026-11-06T07:00:00Z', createdBy: ME, updatedAt: '2026-11-06T08:00:00Z', updatedBy: AGENT, version: 3,
    description: 'С утра не подключается',
    transitions: [
      { to: 'closed', name: 'Подтвердить решение', category: 'closed', requiredFields: [] },
      { to: 'in_progress', name: 'Возобновить', category: 'in_progress', requiredFields: ['comment'] },
    ],
    permissions: { edit: false, assign: false, comment: true, internalNotes: false, merge: false, setConfidential: false },
    ...over,
  };
}

const feed: ActivityEntry[] = [
  { id: 'a1', kind: 'reply', visibility: 'public', authorId: AGENT, fromRequester: false, body: 'Проверьте, пожалуйста, ещё раз', meta: {}, mentions: [], createdAt: '2026-11-06T07:30:00Z' },
  { id: 'a2', kind: 'system', visibility: 'public', authorId: AGENT, fromRequester: false, body: '', meta: { event: 'status_changed', toName: 'Решена' }, mentions: [], createdAt: '2026-11-06T08:00:00Z' },
];

// jsdom не знает document.fonts, а его слушает Textarea autosize из Mantine.
if (!('fonts' in document)) {
  Object.defineProperty(document, 'fonts', { value: { addEventListener() {}, removeEventListener() {}, ready: Promise.resolve() } });
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

describe('шкала статуса', () => {
  it('ожидание остаётся «В работе», назначенная — «Принята»', () => {
    const steps = progressSteps('incident', 'pending', false);
    expect(steps).toEqual(['new', 'in_progress', 'resolved', 'closed']);
    expect(progressIndex(steps, 'pending')).toBe(1);
    expect(progressIndex(steps, 'assigned')).toBe(0);
    expect(progressIndex(steps, 'closed')).toBe(4);
    expect(progressSteps('request', 'new', true)[0]).toBe('approval');
  });
});

describe('Карточка заявки на портале', () => {
  const fetchMock = vi.fn<typeof fetch>();

  beforeEach(() => {
    clearPeopleCache();
    fetchMock.mockReset();
    fetchMock.mockImplementation(async (input, init) => {
      const url = new URL(String(input));
      const method = init?.method ?? 'GET';
      if (url.pathname === '/api/v1/me') return json({ id: ME, displayName: 'Анна Ковалёва', roles: ['REQ'] });
      if (url.pathname === '/api/v1/people') {
        return json([
          { id: ME, displayName: 'Анна Ковалёва', email: null, active: true },
          { id: AGENT, displayName: 'Елена Миронова', email: null, active: true },
        ]);
      }
      if (url.pathname === '/api/v1/tickets/INC-000042') return json(ticket());
      if (url.pathname === `/api/v1/tickets/${TID}/activity`) return json({ items: feed, nextCursor: null });
      if (url.pathname === `/api/v1/tickets/${TID}/transitions` && method === 'POST') {
        return json(ticket({ status: { code: 'closed', name: 'Закрыта', category: 'closed' }, version: 4, transitions: [] }));
      }
      return json({ status: 404, code: 'not_found' }, 404);
    });
    vi.stubGlobal('fetch', fetchMock);
  });
  afterEach(() => vi.unstubAllGlobals());

  function renderPage() {
    const router = createMemoryRouter([{ path: '/portal/tickets/:ref', element: <TicketPage /> }], {
      initialEntries: ['/portal/tickets/INC-000042'],
    });
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    return render(
      <MantineProvider theme={theme} env="test">
        <QueryClientProvider client={qc}>
          <RouterProvider router={router} />
        </QueryClientProvider>
      </MantineProvider>,
    );
  }

  it('показывает имена из справочника и ленту, подтверждение решения уходит с If-Match', async () => {
    renderPage();
    expect(await screen.findByRole('heading', { name: 'Не работает VPN' })).toBeInTheDocument();
    expect(await screen.findAllByText('Елена Миронова')).not.toHaveLength(0);
    expect(screen.getByText('Проверьте, пожалуйста, ещё раз')).toBeInTheDocument();
    expect(screen.getByText(/Статус: Решена/)).toBeInTheDocument();
    // Заявитель — это я
    expect(screen.getByText('Вы')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Подтвердить решение' }));
    await waitFor(() =>
      expect(fetchMock.mock.calls.some(([u, i]) => String(u).endsWith('/transitions') && i?.method === 'POST')).toBe(true),
    );
    const [, init] = fetchMock.mock.calls.find(([u, i]) => String(u).endsWith('/transitions') && i?.method === 'POST')!;
    expect((init?.headers as Record<string, string>)['If-Match']).toBe('"3"');
    expect(JSON.parse(String(init?.body))).toEqual({ to: 'closed' });
  });

  it('«Это не помогло» требует комментарий и возвращает в работу', async () => {
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Это не помогло' }));
    const send = screen.getByRole('button', { name: 'Вернуть в работу' });
    expect(send).toBeDisabled();
    fireEvent.change(screen.getByLabelText(/Что осталось не так/), { target: { value: 'VPN снова отваливается' } });
    fireEvent.click(send);
    await waitFor(() => {
      const call = fetchMock.mock.calls.find(([u, i]) => String(u).endsWith('/transitions') && i?.method === 'POST');
      expect(call && JSON.parse(String(call[1]?.body))).toEqual({ to: 'in_progress', comment: 'VPN снова отваливается', reasonCode: 'reopened' });
    });
  });
});
