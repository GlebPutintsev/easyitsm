import { MantineProvider } from '@mantine/core';
import { Notifications } from '@mantine/notifications';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { theme } from '@/app/theme';
import type { Rule } from './api';
import { AutomationPage } from './AutomationPage';
import { RuleEditorPage } from './RuleEditorPage';
import { coerceValue, conditionComplete, fieldsFor, opsFor, fieldDef } from './model';

if (!('fonts' in document)) {
  Object.defineProperty(document, 'fonts', { value: { addEventListener() {}, removeEventListener() {}, ready: Promise.resolve() } });
}

// jsdom не умеет прокручивать: выпадающий список Mantine прокручивает к выбранному пункту.
Element.prototype.scrollIntoView ??= function scrollIntoView() {};

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

const RULE: Rule = {
  id: '00000000-0000-7000-8000-0000000000r1',
  name: 'P1 для всей организации',
  description: '',
  trigger: 'status_changed',
  conditions: {
    match: 'all',
    items: [
      { field: 'impact', op: 'eq', value: 'high' },
      { field: 'fromStatusCategory', op: 'eq', value: 'new' },
    ],
  },
  actions: [{ type: 'set_priority', priority: 'P1' }],
  enabled: true,
  position: 0,
  version: 2,
  createdAt: '2026-11-06T07:00:00Z',
  updatedAt: '2026-11-06T07:30:00Z',
  updatedBy: null,
};

const NOTIFY_RULE: Rule = {
  ...RULE,
  id: '00000000-0000-7000-8000-0000000000r2',
  name: 'Сообщить в Jira',
  actions: [
    { type: 'notify', target: 'group', groupId: null, title: '', body: 'Новая {{number}}', email: true },
    { type: 'webhook', webhookId: 'w1' },
  ],
};

describe('модель конструктора', () => {
  it('поля события зависят от триггера, операции — от вида поля', () => {
    expect(fieldsFor('status_changed').map((f) => f.key)).toContain('fromStatusCategory');
    expect(fieldsFor('ticket_created').map((f) => f.key)).not.toContain('fromStatusCategory');
    expect(opsFor(fieldDef('sla_warning', 'slaPercent'))).toContain('gte');
    expect(opsFor(fieldDef('ticket_created', 'priority'))).not.toContain('gte');
    expect(opsFor(fieldDef('ticket_updated', 'changedFields'))).toEqual(['contains', 'not_contains', 'empty', 'not_empty']);
  });

  it('значение превращается в список для «одно из» и обратно', () => {
    expect(coerceValue('in', 'p1')).toEqual(['p1']);
    expect(coerceValue('eq', ['p1', 'p2'])).toBe('p1');
    expect(coerceValue('empty', 'x')).toBeUndefined();
    expect(conditionComplete({ op: 'in', value: [] })).toBe(false);
    expect(conditionComplete({ op: 'not_empty' })).toBe(true);
  });
});

describe('экраны автоматизации', () => {
  const fetchMock = vi.fn<typeof fetch>();

  beforeEach(() => {
    fetchMock.mockReset();
    fetchMock.mockImplementation(async (input, init) => {
      const url = new URL(String(input));
      const method = init?.method ?? 'GET';
      if (url.pathname === '/api/v1/automation/rules' && method === 'GET') return json({ items: [RULE], nextCursor: null });
      if (url.pathname === `/api/v1/automation/rules/${RULE.id}` && method === 'GET') return json(RULE);
      if (url.pathname === `/api/v1/automation/rules/${NOTIFY_RULE.id}` && method === 'GET') return json(NOTIFY_RULE);
      if (url.pathname === '/api/v1/integrations/webhooks')
        return json({ items: [{ id: 'w1', name: 'Jira', url: 'https://jira.example.ru/h', enabled: true }], nextCursor: null });
      if (url.pathname === `/api/v1/automation/rules/${RULE.id}` && method === 'PATCH') {
        return json({ ...RULE, ...JSON.parse(String(init?.body)), version: RULE.version + 1 });
      }
      if (url.pathname === '/api/v1/automation/test') {
        return json({
          ticket: { id: 't1', number: 'INC-000042', title: 'Не работает VPN', statusCode: 'new', priority: 'P4' },
          matched: null,
          conditions: [true, null],
          unknownFields: ['fromStatusCategory'],
          actions: [{ type: 'set_priority', outcome: 'change' }],
        });
      }
      if (url.pathname === '/api/v1/groups') return json({ items: [], nextCursor: null });
      if (url.pathname === '/api/v1/ticket-settings/workflows') return json([]);
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
        { path: '/admin/automation', element: <AutomationPage /> },
        { path: '/admin/automation/rules/:id', element: <RuleEditorPage /> },
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

  it('список правил: событие, действия и выключение с If-Match', async () => {
    renderAt('/admin/automation');
    expect(await screen.findByText('P1 для всей организации')).toBeInTheDocument();
    expect(screen.getByText('Изменён статус')).toBeInTheDocument();
    expect(screen.getByText('Установить приоритет')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('switch', { name: 'Выключить' }));
    await waitFor(() => expect(calls('PATCH', RULE.id)).toHaveLength(1));
    const [, init] = calls('PATCH', RULE.id)[0]!;
    expect((init?.headers as Record<string, string>)['If-Match']).toBe('"2"');
    expect(JSON.parse(String(init?.body))).toEqual({ enabled: false });
  });

  it('конструктор показывает условия, проверяет правило на заявке и убирает поля чужого события', async () => {
    renderAt(`/admin/automation/rules/${RULE.id}`);
    expect(await screen.findByRole('heading', { name: 'P1 для всей организации' })).toBeInTheDocument();
    expect(screen.getByTestId('condition-1')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Номер заявки'), { target: { value: 'INC-000042' } });
    fireEvent.click(screen.getByRole('button', { name: 'Проверить' }));
    expect(await screen.findByText('Зависит от события: Прежняя категория статуса')).toBeInTheDocument();
    const body = JSON.parse(String(calls('POST', '/automation/test')[0]![1]?.body));
    expect(body.ticket).toBe('INC-000042');
    expect(body.rule.trigger).toBe('status_changed');

    // Смена события на «Заявка создана» убирает условие по прежней категории статуса.
    const when = screen.getByRole('region', { name: 'Когда' });
    fireEvent.click(within(when).getByLabelText('Событие', { selector: 'input' }));
    fireEvent.click(await screen.findByRole('option', { name: 'Заявка создана' }));
    await waitFor(() => expect(screen.queryByTestId('condition-1')).not.toBeInTheDocument());
    expect(screen.getByTestId('condition-0')).toBeInTheDocument();
    expect(screen.getByText('Есть несохранённые изменения')).toBeInTheDocument();
  });

  it('действия «уведомление» и «вебхук»: получатель, письмо и выбор вебхука', async () => {
    renderAt(`/admin/automation/rules/${NOTIFY_RULE.id}`);
    expect(await screen.findByRole('heading', { name: 'Сообщить в Jira' })).toBeInTheDocument();
    const notify = screen.getByTestId('action-0');
    expect(within(notify).getByDisplayValue('Отправить уведомление')).toBeInTheDocument();
    expect(within(notify).getByDisplayValue('Группе')).toBeInTheDocument();
    expect(within(notify).getByText('Пусто — группа заявки')).toBeInTheDocument();
    expect(within(notify).getByRole('checkbox', { name: 'Ещё и письмом на почту' })).toBeChecked();
    const hook = screen.getByTestId('action-1');
    expect(within(hook).getByDisplayValue('Отправить вебхук')).toBeInTheDocument();
    await waitFor(() => expect(within(hook).getByDisplayValue('Jira')).toBeInTheDocument());
    expect(within(hook).getByRole('link', { name: 'Настроить вебхуки' })).toHaveAttribute('href', '/admin/integrations?tab=webhooks');
  });
});
