import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { theme } from '@/app/theme';
import type { EventEntry, SourceView } from './api';
import { IntegrationsPage } from './IntegrationsPage';
import { alertmanagerConfig } from './snippets';

if (!('fonts' in document)) {
  Object.defineProperty(document, 'fonts', { value: { addEventListener() {}, removeEventListener() {}, ready: Promise.resolve() } });
}
Element.prototype.scrollIntoView ??= function scrollIntoView() {};

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

const SOURCE: SourceView = {
  source: {
    id: '00000000-0000-7000-8000-00000000s001',
    name: 'Zabbix прод',
    kind: 'zabbix',
    tokenHint: '…a1b2',
    enabled: true,
    requesterId: '00000000-0000-7000-8000-00000000u001',
    groupId: null,
    minSeverity: 'warning',
    severityMap: {
      critical: { impact: 'high', urgency: 'high' },
      high: { impact: 'high', urgency: 'medium' },
      warning: { impact: 'medium', urgency: 'medium' },
      info: { impact: 'low', urgency: 'low' },
    },
    autoResolve: true,
    resolutionCode: 'fixed',
    lastEventAt: null,
    version: 3,
    createdAt: '2026-11-06T10:00:00Z',
    updatedAt: '2026-11-06T10:00:00Z',
  },
  requesterName: 'Мониторинг: Zabbix прод',
  groupName: null,
};

const EVENT: EventEntry = {
  id: 'e1',
  sourceId: SOURCE.source.id,
  receivedAt: '2026-11-06T10:05:00Z',
  fingerprint: 'trigger:77:db-1',
  alertStatus: 'firing',
  severity: 'critical',
  title: 'db-1: Нет связи',
  outcome: 'created',
  ticketId: 't1',
  ticketNumber: 'INC-000777',
  detail: null,
};

describe('экран интеграций', () => {
  const fetchMock = vi.fn<typeof fetch>();

  beforeEach(() => {
    fetchMock.mockReset();
    fetchMock.mockImplementation(async (input, init) => {
      const url = new URL(String(input));
      const method = init?.method ?? 'GET';
      if (url.pathname === '/api/v1/me') return json({ id: 'u', roles: ['ADM'], security: {}, session: null });
      if (url.pathname === '/api/v1/integrations/monitoring/sources' && method === 'GET')
        return json({ items: [SOURCE], nextCursor: null });
      if (url.pathname === '/api/v1/integrations/monitoring/sources' && method === 'POST') {
        const body = JSON.parse(String(init?.body));
        return json(
          {
            source: { ...SOURCE, source: { ...SOURCE.source, id: 'new', name: body.name, kind: body.kind } },
            token: 'eitsm_mon_SECRET123',
          },
          201,
        );
      }
      if (url.pathname === `/api/v1/integrations/monitoring/sources/${SOURCE.source.id}` && method === 'PATCH')
        return json({ ...SOURCE, source: { ...SOURCE.source, ...JSON.parse(String(init?.body)), version: 4 } });
      if (url.pathname === '/api/v1/integrations/monitoring/events') return json({ items: [EVENT], nextCursor: null });
      if (url.pathname.endsWith('/alerts')) return json({ items: [], nextCursor: null });
      if (url.pathname === '/api/v1/groups') return json({ items: [], nextCursor: null });
      if (url.pathname === '/api/v1/admin/dictionaries')
        return json([{ kind: 'resolution', version: 0, items: [{ code: 'fixed', name: 'Устранено', active: true }] }]);
      return json({ status: 404, code: 'not_found' }, 404);
    });
    vi.stubGlobal('fetch', fetchMock);
  });
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  function renderAt(path: string) {
    const router = createMemoryRouter([{ path: '/admin/integrations', element: <IntegrationsPage /> }], {
      initialEntries: [path],
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

  const calls = (method: string, suffix: string) =>
    fetchMock.mock.calls.filter(([u, i]) => (i?.method ?? 'GET') === method && new URL(String(u)).pathname.endsWith(suffix));

  it('список источников и выключение с If-Match', async () => {
    renderAt('/admin/integrations');
    expect(await screen.findByText('Zabbix прод')).toBeInTheDocument();
    expect(screen.getByText('…a1b2')).toBeInTheDocument();
    expect(screen.getByText('По правилам маршрутизации')).toBeInTheDocument();
    const toggle = screen.getByRole('switch', { name: 'Источник включён' });
    await waitFor(() => expect(toggle).not.toBeDisabled());
    fireEvent.click(toggle);
    await waitFor(() => expect(calls('PATCH', SOURCE.source.id)).toHaveLength(1));
    const [, init] = calls('PATCH', SOURCE.source.id)[0]!;
    expect((init?.headers as Record<string, string>)['If-Match']).toBe('"3"');
    expect(JSON.parse(String(init?.body))).toEqual({ enabled: false });
  });

  it('новый источник показывает токен один раз вместе с настройкой Alertmanager', async () => {
    renderAt('/admin/integrations');
    await screen.findByText('Zabbix прод');
    const add = screen.getByRole('button', { name: 'Добавить источник' });
    await waitFor(() => expect(add).toBeInTheDocument());
    fireEvent.click(add);
    fireEvent.change(await screen.findByLabelText(/Название/), { target: { value: 'Prometheus' } });
    fireEvent.click(screen.getByText('Alertmanager / Grafana'));
    fireEvent.click(screen.getByRole('button', { name: 'Создать и получить токен' }));

    expect(await screen.findByTestId('issued-token')).toHaveTextContent('eitsm_mon_SECRET123');
    const body = JSON.parse(String(calls('POST', '/monitoring/sources')[0]![1]?.body));
    expect(body).toMatchObject({ name: 'Prometheus', kind: 'alertmanager', minSeverity: 'warning', autoResolve: true });
    expect(body.requesterId).toBeNull();
    expect(screen.getByText(/credentials: eitsm_mon_SECRET123/)).toBeInTheDocument();
  });

  it('журнал событий: итог и ссылка на инцидент', async () => {
    renderAt('/admin/integrations?tab=events');
    expect(await screen.findByText('db-1: Нет связи')).toBeInTheDocument();
    expect(within(screen.getByRole('table')).getByText('Создан инцидент')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'INC-000777' })).toHaveAttribute('href', '/agent/tickets/INC-000777');
  });

  it('фрагмент Alertmanager отправляет и восстановление', () => {
    const yaml = alertmanagerConfig('https://itsm.local/api/v1/integrations/events', 'T');
    expect(yaml).toContain('send_resolved: true');
    expect(yaml).toContain('credentials: T');
  });
});
