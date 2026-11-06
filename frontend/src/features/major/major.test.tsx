import { MantineProvider } from '@mantine/core';
import { Notifications } from '@mantine/notifications';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { theme } from '@/app/theme';
import type { MajorIncident, StatusPage as Status } from './api';
import { MajorTicketAction } from './DeclareDialog';
import { MajorIncidentPage } from './MajorIncidentPage';
import { MajorIncidentsPage } from './MajorIncidentsPage';
import { StatusPage } from './StatusPage';

if (!('fonts' in document)) {
  Object.defineProperty(document, 'fonts', { value: { addEventListener() {}, removeEventListener() {}, ready: Promise.resolve() } });
}
Element.prototype.scrollIntoView ??= function scrollIntoView() {};

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

const MAJOR: MajorIncident = {
  id: 'm1',
  ticket: { id: 't1', number: 'INC-000100', title: 'Почта не работает', statusCode: 'in_progress', statusCategory: 'in_progress', priority: 'P1', visible: true },
  state: 'active',
  publicTitle: 'Сбой почты',
  reason: 'Почта недоступна во всех офисах',
  hypothesis: null,
  services: [{ id: 's1', name: 'Почта' }],
  cmdbServices: [{ id: 'ci-svc', name: 'Корпоративная почта', layer: 'business_service', criticality: 'high', depth: 2 }],
  declaredBy: 'u1',
  declaredAt: '2026-11-06T09:00:00Z',
  approvedBy: 'u1',
  approvedAt: '2026-11-06T09:00:00Z',
  restoredAt: null,
  closedAt: null,
  nextUpdateAt: null,
  roles: [
    { role: 'manager', userId: 'u1' },
    { role: 'tech_lead', userId: null },
    { role: 'comms', userId: null },
    { role: 'scribe', userId: null },
  ],
  timeline: [{ id: 'e1', kind: 'declare_requested', at: '2026-11-06T09:00:00Z', text: 'Объявлен крупным: почта недоступна', actorId: 'u1' }],
  tasks: [],
  updates: [],
  pages: [
    {
      id: 'pg1',
      name: 'Дежурные почты',
      channel: 'center',
      step: 0,
      steps: 2,
      state: 'paging',
      nextStepAt: '2026-11-06T09:10:00Z',
      ackedBy: null,
      ackedAt: null,
      createdAt: '2026-11-06T09:00:00Z',
      targets: [{ step: 0, userId: 'u1', notifiedAt: '2026-11-06T09:00:00Z', ackedAt: null }],
    },
  ],
  children: [],
  pir: null,
  problem: null,
  permissions: { manage: true, approve: false, close: false },
  version: 4,
};

const STATUS: Status = {
  majorIncidents: [
    {
      id: 'm1',
      title: 'Сбой почты',
      state: 'active',
      services: [{ id: 's1', name: 'Почта' }],
      declaredAt: '2026-11-06T09:00:00Z',
      restoredAt: null,
      nextUpdateAt: null,
      lastUpdate: { id: 'up1', audience: 'users', title: 'Чиним почту', body: 'Специалисты работают', statusPage: true, nextUpdateAt: null, publishedAt: '2026-11-06T09:05:00Z', publishedBy: null },
    },
  ],
  announcements: [],
  generatedAt: '2026-11-06T09:06:00Z',
};

describe('крупные инциденты', () => {
  const fetchMock = vi.fn<typeof fetch>();
  let byTicket: MajorIncident | null = null;

  beforeEach(() => {
    byTicket = null;
    fetchMock.mockReset();
    fetchMock.mockImplementation(async (input, init) => {
      const url = new URL(String(input));
      const method = init?.method ?? 'GET';
      const path = url.pathname;
      if (path === '/api/v1/me') return json({ id: 'u1', roles: ['AGT', 'LEAD'], displayName: 'Руководитель' });
      if (path === '/api/v1/major-incidents' && method === 'GET') {
        return json([{ id: 'm1', ticket: MAJOR.ticket, state: 'active', publicTitle: 'Сбой почты', services: MAJOR.services, managerId: 'u1', declaredAt: MAJOR.declaredAt, restoredAt: null, closedAt: null, nextUpdateAt: null, children: 2 }]);
      }
      if (path === '/api/v1/major-incidents' && method === 'POST') return json({ ...MAJOR, id: 'm2' }, 201);
      if (path === '/api/v1/major-incidents/m1' && method === 'GET') return json(MAJOR);
      if (path === '/api/v1/major-incidents/m1' && method === 'PATCH') return json({ ...MAJOR, ...JSON.parse(String(init?.body)), version: 5 });
      if (path === '/api/v1/major-incidents/by-ticket/t1') return byTicket ? json(byTicket) : json({ status: 404, code: 'not_found' }, 404);
      if (path === '/api/v1/major-incidents/m1/render') return json({ title: 'Сбой: Сбой почты', body: 'Мы знаем о сбое', audience: 'users' });
      if (path === '/api/v1/major-incidents/m1/updates') return json(MAJOR);
      if (path === '/api/v1/major-incidents/pages/pg1/ack') return json(MAJOR);
      if (path === '/api/v1/mim/templates') return json([{ id: 'tpl1', name: 'Пользователям: сбой обнаружен', audience: 'users', title: 'Сбой: {{title}}', body: '…', position: 0, active: true, version: 1 }]);
      if (path === '/api/v1/status') return json(STATUS);
      if (path.startsWith('/api/v1/people')) return json([]);
      if (path === '/api/v1/agent-workspace/directory') return json({ groups: [], agents: [], services: [{ id: 's1', name: 'Почта' }], categories: [], resolutionCodes: [], pendingReasons: [] });
      return json({ status: 404, code: 'not_found' }, 404);
    });
    vi.stubGlobal('fetch', fetchMock);
  });
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  function renderAt(path: string) {
    const router = createMemoryRouter(
      [
        { path: '/agent/major', element: <MajorIncidentsPage /> },
        { path: '/agent/major/:id', element: <MajorIncidentPage /> },
        { path: '/agent/tickets/INC-000100', element: <MajorTicketAction ticket={{ id: 't1', number: 'INC-000100', title: 'Почта не работает', serviceId: 's1' }} closed={false} /> },
        { path: '/portal/status', element: <StatusPage /> },
      ],
      { initialEntries: [path] },
    );
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <MantineProvider theme={theme} env="test">
        <Notifications />
        <QueryClientProvider client={qc}>
          <RouterProvider router={router} />
        </QueryClientProvider>
      </MantineProvider>,
    );
    return router;
  }

  const calls = (method: string, suffix: string) =>
    fetchMock.mock.calls.filter(([u, i]) => (i?.method ?? 'GET') === method && new URL(String(u)).pathname.endsWith(suffix));

  it('список показывает текущие крупные инциденты', async () => {
    renderAt('/agent/major');
    expect(await screen.findByText('Сбой почты')).toBeInTheDocument();
    expect(screen.getByText('Идёт восстановление')).toBeInTheDocument();
    const [url] = calls('GET', '/major-incidents')[0]!;
    expect(new URL(String(url)).searchParams.get('state')).toBe('open');
    expect(screen.queryByRole('tab', { name: 'Шаблоны сообщений' })).not.toBeInTheDocument();
  });

  it('командный центр: гипотеза с If-Match, сообщение по шаблону, подтверждение оповещения', async () => {
    renderAt('/agent/major/m1');
    expect(await screen.findByRole('heading', { name: 'Сбой почты' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Корпоративная почта' })).toHaveAttribute('href', '/agent/cmdb/ci-svc');

    fireEvent.change(screen.getByRole('textbox', { name: 'Текущая гипотеза' }), { target: { value: 'Сертификат SMTP' } });
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }));
    await waitFor(() => expect(calls('PATCH', '/major-incidents/m1')).toHaveLength(1));
    const [, patchInit] = calls('PATCH', '/major-incidents/m1')[0]!;
    expect((patchInit?.headers as Record<string, string>)['If-Match']).toBe('"4"');
    expect(JSON.parse(String(patchInit?.body))).toEqual({ hypothesis: 'Сертификат SMTP' });

    fireEvent.click(screen.getByRole('button', { name: 'Подтвердить получение' }));
    await waitFor(() => expect(calls('POST', '/pages/pg1/ack')).toHaveLength(1));

    fireEvent.click(screen.getByRole('button', { name: 'Новое сообщение' }));
    fireEvent.click((await screen.findAllByLabelText('Шаблон'))[0]!);
    fireEvent.click(await screen.findByRole('option', { name: 'Пользователям: сбой обнаружен' }));
    expect(await screen.findByDisplayValue('Сбой: Сбой почты')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Опубликовать' }));
    await waitFor(() => expect(calls('POST', '/updates')).toHaveLength(1));
    const body = JSON.parse(String(calls('POST', '/updates')[0]![1]?.body));
    expect(body).toMatchObject({ audience: 'users', title: 'Сбой: Сбой почты', body: 'Мы знаем о сбое', statusPage: true });
    expect(body.nextUpdateAt).toBeTruthy();
  }, 20_000);

  it('карточка инцидента: «Объявить крупным» отправляет обоснование и открывает командный центр', async () => {
    const router = renderAt('/agent/tickets/INC-000100');
    fireEvent.click(await screen.findByRole('button', { name: 'Объявить крупным' }));
    fireEvent.change(await screen.findByRole('textbox', { name: /Обоснование/ }), { target: { value: 'Не работает почта у всех' } });
    fireEvent.click(screen.getByRole('button', { name: 'Объявить' }));
    await waitFor(() => expect(calls('POST', '/major-incidents')).toHaveLength(1));
    const body = JSON.parse(String(calls('POST', '/major-incidents')[0]![1]?.body));
    expect(body).toMatchObject({ ticketId: 't1', reason: 'Не работает почта у всех', managerId: 'u1', serviceIds: ['s1'] });
    await waitFor(() => expect(router.state.location.pathname).toBe('/agent/major/m2'));
  });

  it('объявленный инцидент ведёт в командный центр вместо кнопки объявления', async () => {
    byTicket = MAJOR;
    renderAt('/agent/tickets/INC-000100');
    expect(await screen.findByRole('link', { name: 'Командный центр' })).toHaveAttribute('href', '/agent/major/m1');
    expect(screen.queryByRole('button', { name: 'Объявить крупным' })).not.toBeInTheDocument();
  });

  it('страница статуса показывает сбой и последнее сообщение', async () => {
    renderAt('/portal/status');
    expect(await screen.findByText('Идёт сбой')).toBeInTheDocument();
    expect(screen.getByText('Чиним почту')).toBeInTheDocument();
    expect(screen.getByText('Затронуто: Почта')).toBeInTheDocument();
  });
});
