import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { theme } from '@/app/theme';
import type { Delivery, EventEntry, Messenger, MyMessenger, SourceView, Webhook } from './api';
import { IntegrationsPage } from './IntegrationsPage';
import { MyMessengersPage } from './MyMessengersPage';
import { alertmanagerConfig } from './snippets';

if (!('fonts' in document)) {
  Object.defineProperty(document, 'fonts', { value: { addEventListener() {}, removeEventListener() {}, ready: Promise.resolve() } });
}
Element.prototype.scrollIntoView ??= function scrollIntoView() {};

// Выданный токен в ответе-заглушке: собирается из частей, чтобы не походить на настоящий секрет для gitleaks.
const ISSUED = 'eitsm_mon_' + 'x'.repeat(12);

// Ключ подписи в ответе-заглушке: тоже из частей.
const SIGNING = 'whsec_' + 'y'.repeat(12);

const HOOK: Webhook = {
  id: '00000000-0000-7000-8000-00000000w001',
  name: 'Jira',
  url: 'https://jira.example.ru/hooks/itsm',
  enabled: true,
  lastUsedAt: null,
  version: 1,
  createdAt: '2026-11-06T10:00:00Z',
  updatedAt: '2026-11-06T10:00:00Z',
};

const DEAD: Delivery = {
  id: 'd1',
  webhookId: HOOK.id,
  eventType: 'automation.rule',
  status: 'dead',
  attempts: 9,
  nextAttemptAt: null,
  lastStatus: 502,
  lastError: 'ответ HTTP 502',
  deliveredAt: null,
  source: 'P1 в Jira',
  ticketNumber: 'INC-000778',
  createdAt: '2026-11-06T10:06:00Z',
};

const BOT: Messenger = {
  id: '00000000-0000-7000-8000-00000000b001',
  kind: 'telegram',
  name: 'Поддержка',
  tokenHint: '…wxyz',
  appTokenHint: null,
  baseUrl: null,
  botUsername: 'itsm_help_bot',
  botUrl: 'https://t.me/itsm_help_bot',
  enabled: true,
  allowTickets: true,
  ticketType: 'incident',
  groupId: null,
  dataFields: ['title', 'link'],
  lastPollAt: '2026-11-06T10:00:00Z',
  lastError: null,
  lastEventAt: null,
  linkedUsers: 3,
  version: 2,
  createdAt: '2026-11-06T10:00:00Z',
  updatedAt: '2026-11-06T10:00:00Z',
};

const MY_BOT: MyMessenger = {
  id: BOT.id,
  kind: 'telegram',
  name: 'Поддержка',
  botUsername: 'itsm_help_bot',
  linked: false,
  externalName: null,
  linkedAt: null,
};

// Код привязки в ответе-заглушке: тоже из частей.
const LINK_CODE = 'L' + 'k'.repeat(19);

// Токены бота во вводе: тоже из частей.
const BOT_TOKEN = 'bot-' + 'z'.repeat(8);
const APP_TOKEN = 'app-' + 'q'.repeat(8);

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
            token: ISSUED,
          },
          201,
        );
      }
      if (url.pathname === `/api/v1/integrations/monitoring/sources/${SOURCE.source.id}` && method === 'PATCH')
        return json({ ...SOURCE, source: { ...SOURCE.source, ...JSON.parse(String(init?.body)), version: 4 } });
      if (url.pathname === '/api/v1/integrations/monitoring/events') return json({ items: [EVENT], nextCursor: null });
      if (url.pathname.endsWith('/alerts')) return json({ items: [], nextCursor: null });
      if (url.pathname === '/api/v1/integrations/webhooks' && method === 'GET') return json({ items: [HOOK], nextCursor: null });
      if (url.pathname === '/api/v1/integrations/webhooks' && method === 'POST') {
        const body = JSON.parse(String(init?.body));
        return json({ webhook: { ...HOOK, id: 'w2', name: body.name, url: body.url }, secret: SIGNING }, 201);
      }
      if (url.pathname === '/api/v1/integrations/outbound-policy' && method === 'GET')
        return json({ allowedHosts: [], version: 0 });
      if (url.pathname === '/api/v1/integrations/outbound-policy' && method === 'PUT')
        return json({ allowedHosts: JSON.parse(String(init?.body)).allowedHosts, version: 1 });
      if (url.pathname === '/api/v1/integrations/webhook-deliveries') return json({ items: [DEAD], nextCursor: null });
      if (url.pathname === '/api/v1/integrations/webhook-deliveries/d1/retry')
        return json({ ...DEAD, status: 'pending', attempts: 0 });
      if (url.pathname === '/api/v1/integrations/messengers' && method === 'GET') return json({ items: [BOT], nextCursor: null });
      if (url.pathname === '/api/v1/integrations/messengers' && method === 'POST') {
        const body = JSON.parse(String(init?.body));
        return json({ ...BOT, id: 'b2', name: body.name, kind: body.kind }, 201);
      }
      if (url.pathname === `/api/v1/integrations/messengers/${BOT.id}` && method === 'PATCH')
        return json({ ...BOT, ...JSON.parse(String(init?.body)), version: 3 });
      if (url.pathname === `/api/v1/integrations/messengers/${BOT.id}/check`)
        return json({ ok: true, botUsername: 'itsm_help_bot', error: null });
      if (url.pathname === '/api/v1/integrations/my-messengers' && method === 'GET') return json([MY_BOT]);
      if (url.pathname === `/api/v1/integrations/my-messengers/${BOT.id}/link`)
        return json({
          url: `https://t.me/itsm_help_bot?start=${LINK_CODE}`,
          code: LINK_CODE,
          command: `/start ${LINK_CODE}`,
          expiresAt: '2026-11-06T10:15:00Z',
        });
      if (url.pathname === `/api/v1/integrations/my-messengers/${BOT.id}` && method === 'DELETE') return new Response(null, { status: 204 });
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
    const router = createMemoryRouter(
      [
        { path: '/admin/integrations', element: <IntegrationsPage /> },
        { path: '/profile/messengers', element: <MyMessengersPage /> },
      ],
      { initialEntries: [path] },
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

    expect(await screen.findByTestId('issued-token')).toHaveTextContent(ISSUED);
    const body = JSON.parse(String(calls('POST', '/monitoring/sources')[0]![1]?.body));
    expect(body).toMatchObject({ name: 'Prometheus', kind: 'alertmanager', minSeverity: 'warning', autoResolve: true });
    expect(body.requesterId).toBeNull();
    expect(screen.getByText(new RegExp(`credentials: ${ISSUED}`))).toBeInTheDocument();
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

  it('вебхуки: пустой список адресов, сохранение с If-Match и ключ подписи один раз', async () => {
    renderAt('/admin/integrations?tab=webhooks');
    expect(await screen.findByText('Jira')).toBeInTheDocument();
    expect(await screen.findByText(/Список пуст: вебхуки никуда не отправляются/)).toBeInTheDocument();

    const hosts = await screen.findByPlaceholderText(/hooks\.example\.ru/);
    await waitFor(() => expect(hosts).not.toBeDisabled());
    // Поле выбора Mantine принимает ввод только в фокусе.
    hosts.focus();
    fireEvent.change(hosts, { target: { value: 'jira.example.ru' } });
    fireEvent.keyDown(hosts, { key: 'Enter' });
    fireEvent.click(await screen.findByRole('button', { name: 'Сохранить список' }));
    await waitFor(() => expect(calls('PUT', '/outbound-policy')).toHaveLength(1));
    const [, put] = calls('PUT', '/outbound-policy')[0]!;
    expect((put?.headers as Record<string, string>)['If-Match']).toBe('"0"');
    expect(JSON.parse(String(put?.body))).toEqual({ allowedHosts: ['jira.example.ru'] });

    fireEvent.click(screen.getByRole('button', { name: 'Добавить вебхук' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText(/Название/), { target: { value: 'ServiceDesk' } });
    fireEvent.change(within(dialog).getByLabelText(/Адрес/), { target: { value: 'https://sd.example.ru/in' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Создать' }));
    expect(await screen.findByTestId('issued-secret')).toHaveTextContent(SIGNING);
    expect(JSON.parse(String(calls('POST', '/integrations/webhooks')[0]![1]?.body))).toEqual({
      name: 'ServiceDesk',
      url: 'https://sd.example.ru/in',
    });
  });

  it('доставки: неудачную можно повторить', async () => {
    renderAt('/admin/integrations?tab=deliveries');
    const table = await screen.findByTestId('deliveries-table');
    expect(within(table).getByText('Не доставлено')).toBeInTheDocument();
    expect(within(table).getByText('ответ HTTP 502')).toBeInTheDocument();
    expect(within(table).getByText('INC-000778')).toBeInTheDocument();
    fireEvent.click(within(table).getByRole('button', { name: 'Повторить' }));
    await waitFor(() => expect(calls('POST', '/d1/retry')).toHaveLength(1));
  });

  it('мессенджеры: список ботов, перечень данных и проверка связи', async () => {
    renderAt('/admin/integrations?tab=messengers');
    const table = await screen.findByTestId('messengers-table');
    expect(within(table).getByText('Поддержка')).toBeInTheDocument();
    expect(within(table).getByText('@itsm_help_bot')).toBeInTheDocument();
    expect(within(table).getByText('3')).toBeInTheDocument();

    fireEvent.click(within(table).getByRole('button', { name: 'Поддержка' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/Сохранён токен …wxyz/)).toBeInTheDocument();
    fireEvent.click(within(dialog).getByLabelText('Текст уведомления'));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Проверить связь' }));
    expect(await within(dialog).findByTestId('messenger-check')).toHaveTextContent('@itsm_help_bot');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Сохранить' }));
    await waitFor(() => expect(calls('PATCH', `/messengers/${BOT.id}`)).toHaveLength(1));
    const [, patch] = calls('PATCH', `/messengers/${BOT.id}`)[0]!;
    expect((patch?.headers as Record<string, string>)['If-Match']).toBe('"2"');
    const body = JSON.parse(String(patch?.body));
    expect(body.dataFields).toEqual(['title', 'link', 'text']);
    expect(body).not.toHaveProperty('token');
  });

  it('новый бот требует токен', async () => {
    renderAt('/admin/integrations?tab=messengers');
    fireEvent.click(await screen.findByRole('button', { name: 'Подключить бота' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByText('MAX'));
    fireEvent.change(within(dialog).getByLabelText(/Название/), { target: { value: 'MAX поддержка' } });
    const connect = within(dialog).getByRole('button', { name: 'Подключить' });
    expect(connect).toBeDisabled();
    fireEvent.change(within(dialog).getByLabelText(/Токен бота/), { target: { value: BOT_TOKEN } });
    fireEvent.click(connect);
    await waitFor(() => expect(calls('POST', '/integrations/messengers')).toHaveLength(1));
    const body = JSON.parse(String(calls('POST', '/integrations/messengers')[0]![1]?.body));
    expect(body).toMatchObject({ kind: 'max', name: 'MAX поддержка', token: BOT_TOKEN, clearGroup: true });
  });

  it('бот Slack требует ещё и токен приложения', async () => {
    renderAt('/admin/integrations?tab=messengers');
    fireEvent.click(await screen.findByRole('button', { name: 'Подключить бота' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByText('Slack'));
    fireEvent.change(within(dialog).getByLabelText(/Название/), { target: { value: 'Slack поддержка' } });
    fireEvent.change(within(dialog).getByLabelText(/Токен бота/), { target: { value: BOT_TOKEN } });
    const connect = within(dialog).getByRole('button', { name: 'Подключить' });
    expect(connect).toBeDisabled();
    fireEvent.change(within(dialog).getByLabelText(/Токен приложения/), { target: { value: APP_TOKEN } });
    fireEvent.click(connect);
    await waitFor(() => expect(calls('POST', '/integrations/messengers')).toHaveLength(1));
    expect(JSON.parse(String(calls('POST', '/integrations/messengers')[0]![1]?.body))).toMatchObject({
      kind: 'slack',
      token: BOT_TOKEN,
      appToken: APP_TOKEN,
    });
  });

  it('профиль: привязка выдаёт ссылку на бота и команду', async () => {
    renderAt('/profile/messengers');
    expect(await screen.findByText('Не привязан')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Привязать' }));
    const box = await screen.findByTestId('link-code');
    expect(within(box).getByText(`/start ${LINK_CODE}`)).toBeInTheDocument();
    expect(within(box).getByRole('link', { name: 'Открыть бота' })).toHaveAttribute(
      'href',
      `https://t.me/itsm_help_bot?start=${LINK_CODE}`,
    );
    fireEvent.click(within(box).getByRole('button', { name: 'Я нажал «Старт»' }));
    await waitFor(() => expect(calls('GET', '/my-messengers').length).toBeGreaterThan(1));
  });
});
