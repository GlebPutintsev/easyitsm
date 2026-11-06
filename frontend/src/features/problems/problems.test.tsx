import { MantineProvider } from '@mantine/core';
import { Notifications } from '@mantine/notifications';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { theme } from '@/app/theme';
import type { KnownError, Problem } from './api';
import { KnownErrorsHint } from './KnownErrorsHint';
import { ProblemPage } from './ProblemPage';
import { ProblemsPage } from './ProblemsPage';

if (!('fonts' in document)) {
  Object.defineProperty(document, 'fonts', { value: { addEventListener() {}, removeEventListener() {}, ready: Promise.resolve() } });
}
Element.prototype.scrollIntoView ??= function scrollIntoView() {};

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

const KE: KnownError = {
  id: 'ke1',
  problemId: 'p1',
  problemNumber: 'PRB-000007',
  title: 'Шлюз VPN отклоняет сессии',
  symptoms: 'Обрыв через минуту',
  workaround: 'Подключиться через резервный шлюз',
  serviceId: null,
  ciId: null,
  categoryId: null,
  published: true,
  publishedAt: '2026-11-06T09:00:00Z',
  fixed: false,
  updatedAt: '2026-11-06T09:00:00Z',
  version: 2,
};

const PROBLEM: Problem = {
  id: 'p1',
  number: 'PRB-000007',
  title: 'Обрывы VPN',
  statusCode: 'in_progress',
  statusCategory: 'in_progress',
  priority: 'P2',
  groupId: null,
  assigneeId: null,
  serviceId: null,
  ciId: null,
  categoryId: null,
  createdAt: '2026-11-05T10:00:00Z',
  resolvedAt: null,
  source: 'incident',
  rootCause: '',
  workaround: '',
  analysis: { timeline: [{ at: '2026-11-05T09:00:00Z', text: 'Инцидент INC-000010: VPN рвётся' }], whys: [], ishikawa: {}, conclusion: '' },
  closeIncidents: true,
  risk: null,
  knownError: KE,
  incidents: [
    { id: 'i1', number: 'INC-000010', title: 'VPN рвётся', statusCode: 'in_progress', statusCategory: 'in_progress', priority: 'P3', createdAt: '2026-11-05T09:00:00Z', visible: true },
    { id: 'i2', number: 'INC-000011', title: null, statusCode: null, statusCategory: null, priority: null, createdAt: null, visible: false },
  ],
  changes: [],
  permissions: { edit: true, publishKnownError: false, createChange: true, resolveIncidents: false },
  version: 3,
};

describe('проблемы', () => {
  const fetchMock = vi.fn<typeof fetch>();

  beforeEach(() => {
    fetchMock.mockReset();
    fetchMock.mockImplementation(async (input, init) => {
      const url = new URL(String(input));
      const method = init?.method ?? 'GET';
      if (url.pathname === '/api/v1/me') return json({ id: 'u1', roles: ['AGT', 'LEAD'], displayName: 'Агент' });
      if (url.pathname === '/api/v1/tickets' && url.searchParams.get('type') === 'problem') {
        return json({
          items: [{ id: 'p1', number: 'PRB-000007', title: 'Обрывы VPN', status: { code: 'in_progress', name: 'В работе', category: 'in_progress' }, priority: 'P2', assigneeId: null, groupId: null, createdAt: '2026-11-05T10:00:00Z', updatedAt: '2026-11-05T10:00:00Z' }],
          nextCursor: null,
        });
      }
      if (url.pathname === '/api/v1/problems/PRB-000007' && method === 'GET') return json(PROBLEM);
      if (url.pathname === '/api/v1/problems/p1/analysis' && method === 'PUT') {
        return json({ ...PROBLEM, analysis: JSON.parse(String(init?.body)).analysis, version: 4 });
      }
      if (url.pathname === '/api/v1/known-errors') return json([KE]);
      if (url.pathname.startsWith('/api/v1/people')) return json([]);
      if (url.pathname === '/api/v1/agent-workspace/directory') return json({ groups: [], agents: [] });
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
        { path: '/agent/problems', element: <ProblemsPage /> },
        { path: '/agent/problems/:number', element: <ProblemPage /> },
        { path: '/agent/tickets/:number', element: <KnownErrorsHint ticketId="i1" /> },
      ],
      { initialEntries: [path] },
    );
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    return render(
      <MantineProvider theme={theme} env="test">
        <Notifications />
        <QueryClientProvider client={qc}>
          <RouterProvider router={router} />
        </QueryClientProvider>
      </MantineProvider>,
    );
  }

  const calls = (method: string, suffix: string) =>
    fetchMock.mock.calls.filter(([u, i]) => (i?.method ?? 'GET') === method && new URL(String(u)).pathname.endsWith(suffix));

  it('список проблем открывает активные заявки типа problem', async () => {
    renderAt('/agent/problems');
    expect(await screen.findByText('Обрывы VPN')).toBeInTheDocument();
    const [url] = calls('GET', '/tickets')[0]!;
    expect(new URL(String(url)).searchParams.get('state')).toBe('active');
    expect(screen.getByRole('tab', { name: 'Повторяющиеся инциденты' })).toBeInTheDocument();
  });

  it('карточка: невидимый инцидент только номером, анализ сохраняется с If-Match', async () => {
    renderAt('/agent/problems/PRB-000007?tab=incidents');
    expect(await screen.findByText('VPN рвётся')).toBeInTheDocument();
    expect(screen.getByText('INC-000011')).toBeInTheDocument();
    expect(screen.getByText('Нет доступа')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('tab', { name: 'Анализ причин' }));
    const why = await screen.findByRole('textbox', { name: 'Почему? 1' });
    fireEvent.change(why, { target: { value: 'Почему рвётся VPN?' } });
    fireEvent.change(screen.getByRole('textbox', { name: 'Потому что… 1' }), { target: { value: 'Истёк сертификат' } });
    fireEvent.click(screen.getByRole('button', { name: 'Причина: Технологии' }));
    fireEvent.change(screen.getByRole('textbox', { name: 'Технологии: 1' }), { target: { value: 'Сертификат шлюза' } });
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить' }));

    await waitFor(() => expect(calls('PUT', '/analysis')).toHaveLength(1));
    const [, init] = calls('PUT', '/analysis')[0]!;
    expect((init?.headers as Record<string, string>)['If-Match']).toBe('"3"');
    const body = JSON.parse(String(init?.body));
    expect(body.analysis.whys).toEqual([{ question: 'Почему рвётся VPN?', answer: 'Истёк сертификат' }]);
    expect(body.analysis.ishikawa).toEqual({ technology: ['Сертификат шлюза'] });
    expect(body.analysis.timeline).toHaveLength(1);
  });

  it('публикация известной ошибки недоступна без роли менеджера', async () => {
    renderAt('/agent/problems/PRB-000007?tab=knownError');
    expect(await screen.findByDisplayValue('Шлюз VPN отклоняет сессии')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Снять с публикации' })).not.toBeInTheDocument();
    expect(screen.getByText('Опубликована')).toBeInTheDocument();
  });

  it('подсказка в карточке инцидента показывает обходное решение', async () => {
    renderAt('/agent/tickets/INC-000010');
    expect(await screen.findByText('Подключиться через резервный шлюз')).toBeInTheDocument();
    const [url] = calls('GET', '/known-errors')[0]!;
    expect(new URL(String(url)).searchParams.get('ticketId')).toBe('i1');
  });
});
