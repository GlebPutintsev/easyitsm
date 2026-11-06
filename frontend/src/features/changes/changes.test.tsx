import { MantineProvider } from '@mantine/core';
import { Notifications } from '@mantine/notifications';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { theme } from '@/app/theme';
import type { Change, ChangeItem } from './api';
import { CalendarPage } from './CalendarPage';
import { ChangePage } from './ChangePage';
import { ChangesPage } from './ChangesPage';
import { minutesLabel, monthGrid, overlapsDay, pendingImplementSteps, stateCategory, weekGrid } from './model';

if (!('fonts' in document)) {
  Object.defineProperty(document, 'fonts', { value: { addEventListener() {}, removeEventListener() {}, ready: Promise.resolve() } });
}
Element.prototype.scrollIntoView ??= function scrollIntoView() {};

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

const ID = '01930000-0000-7000-8000-00000000c001';
const AUTHOR = '01930000-0000-7000-8000-00000000a001';

const ITEM: ChangeItem = {
  id: ID,
  number: 'CHG-000012',
  title: 'Обновление портала до 2.4',
  type: 'normal',
  state: 'assessment',
  riskLevel: 'medium',
  windowStart: '2026-11-20T23:00:00Z',
  windowEnd: '2026-11-21T01:00:00Z',
  authorId: AUTHOR,
  groupId: null,
  assigneeId: null,
  ciIds: [],
  serviceIds: [],
  outcome: null,
  updatedAt: '2026-11-06T10:00:00Z',
};

const PERMISSIONS = {
  edit: false,
  editPlan: false,
  submit: false,
  assess: true,
  sendToApproval: true,
  returnToDraft: true,
  reschedule: false,
  start: false,
  recordSteps: false,
  startRollback: false,
  complete: false,
  close: false,
  cancel: true,
};

const CHANGE: Change = {
  ...ITEM,
  templateId: null,
  sourceTicketId: null,
  confidential: false,
  goal: 'Закрыть уязвимость',
  justification: 'Требование ИБ',
  impact: 'Портал недоступен 30 минут',
  risks: null,
  impactRows: [],
  systemCriticality: 'high',
  timeZone: 'Europe/Moscow',
  prepStart: null,
  downtimeMinutes: 30,
  cis: [],
  risk: { answers: {}, score: 7, level: 'medium', manual: false, reason: null, assessedAt: null, assessedBy: null, computedLevel: 'medium' },
  conflictsAck: null,
  submittedAt: '2026-11-06T10:00:00Z',
  approvedAt: null,
  postApprovalDueAt: null,
  postApprovalOverdue: false,
  actualStart: null,
  actualEnd: null,
  rollbackStartedAt: null,
  conclusion: null,
  review: {},
  closedAt: null,
  cancelReason: null,
  pipeline: null,
  steps: [],
  plan: { prepareMinutes: 0, implementMinutes: 35, verifyMinutes: 10, rollbackMinutes: 20, planEnd: null, fitsWindow: true },
  approvals: [],
  cab: [],
  missing: [],
  permissions: PERMISSIONS,
  createdAt: '2026-11-06T09:00:00Z',
  version: 3,
};

describe('модель изменений', () => {
  it('статус изменения красится категорией заявки', () => {
    expect(stateCategory.approval).toBe('approval');
    expect(stateCategory.implementation).toBe('in_progress');
    expect(stateCategory.rejected).toBe('cancelled');
  });

  it('длительность и сетки календаря', () => {
    const t = (k: string, o?: Record<string, unknown>) => `${k}:${JSON.stringify(o)}`;
    expect(minutesLabel(95, t)).toContain('changes.duration.hm');
    expect(minutesLabel(null, t)).toBe('—');
    const month = monthGrid(new Date(2026, 10, 15));
    expect(month.days).toHaveLength(42);
    expect(month.days[0]!.getDay()).toBe(1);
    expect(weekGrid(new Date(2026, 10, 15)).days[0]!.getDay()).toBe(1);
    expect(overlapsDay('2026-11-20T10:00:00', '2026-11-20T12:00:00', new Date(2026, 10, 20))).toBe(true);
    expect(overlapsDay('2026-11-20T10:00:00', '2026-11-20T12:00:00', new Date(2026, 10, 21))).toBe(false);
  });

  it('шаги внедрения без отметки не дают перейти к проверке', () => {
    const step = { phase: 'implement', status: 'pending' } as Change['steps'][number];
    expect(pendingImplementSteps([step, { ...step, status: 'done' }, { ...step, phase: 'rollback' }])).toBe(1);
  });
});

describe('экраны изменений', () => {
  const fetchMock = vi.fn<typeof fetch>();
  const calls: { method: string; path: string; body?: string; ifMatch?: string | null }[] = [];

  beforeEach(() => {
    calls.length = 0;
    fetchMock.mockReset();
    fetchMock.mockImplementation(async (input, init) => {
      const url = new URL(String(input));
      const method = init?.method ?? 'GET';
      const headers = new Headers(init?.headers);
      calls.push({ method, path: url.pathname, body: init?.body as string | undefined, ifMatch: headers.get('If-Match') });
      if (url.pathname === '/api/v1/changes' && method === 'GET') return json({ items: [ITEM], nextCursor: null });
      if (url.pathname === `/api/v1/changes/${ID}` && method === 'GET') return json(CHANGE);
      if (url.pathname === `/api/v1/changes/${ID}/send-to-approval`) {
        if (!JSON.parse(String(init?.body)).acknowledgeConflicts) {
          return json(
            {
              status: 409,
              code: 'change_conflicts',
              detail: 'Пересечения',
              conflicts: [
                { kind: 'service', changeId: 'other', number: 'CHG-000010', title: 'Миграция БД', state: 'scheduled', freezeId: null, sharedCiIds: [], sharedServiceIds: [], from: ITEM.windowStart, to: ITEM.windowEnd },
              ],
            },
            409,
          );
        }
        return json({ ...CHANGE, state: 'approval', version: 4 });
      }
      if (url.pathname === '/api/v1/changes/calendar') {
        return json({
          changes: [ITEM],
          freezes: [{ id: 'f1', title: 'Годовой отчёт', reason: null, startsAt: ITEM.windowStart, endsAt: ITEM.windowEnd, scope: 'all', serviceIds: [], ciIds: [], version: 0 }],
        });
      }
      if (url.pathname.startsWith('/api/v1/changes/risk-questions')) return json([]);
      if (url.pathname.endsWith('/conflicts') || url.pathname.endsWith('/related-incidents')) return json([]);
      if (url.pathname.startsWith('/api/v1/change-templates')) return json([]);
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
        { path: '/agent/changes', element: <ChangesPage /> },
        { path: '/agent/changes/calendar', element: <CalendarPage /> },
        { path: '/agent/changes/:id', element: <ChangePage /> },
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

  it('список показывает номер, тип, статус и риск', async () => {
    renderAt('/agent/changes');
    const row = (await screen.findByText('CHG-000012')).closest('tr')!;
    expect(within(row).getByText('Обновление портала до 2.4')).toBeInTheDocument();
    expect(within(row).getByText('Обычное')).toBeInTheDocument();
    expect(within(row).getByText('Оценка')).toBeInTheDocument();
    expect(within(row).getByText(/Средний/)).toBeInTheDocument();
    const list = calls.find((c) => c.path === '/api/v1/changes');
    expect(list).toBeDefined();
  });

  it('пересечение при отправке на согласование требует подтверждения причиной (AT-06)', async () => {
    renderAt(`/agent/changes/${ID}`);
    fireEvent.click(await screen.findByRole('button', { name: 'Отправить на согласование' }));
    expect(await screen.findByText('CHG-000010')).toBeInTheDocument();
    const proceed = screen.getByRole('button', { name: 'Продолжить' });
    expect(proceed).toBeDisabled();
    fireEvent.change(screen.getByLabelText(/Почему пересечение допустимо/), { target: { value: 'Разные узлы кластера' } });
    fireEvent.click(proceed);
    await waitFor(() => {
      const sent = calls.filter((c) => c.path.endsWith('/send-to-approval'));
      expect(sent).toHaveLength(2);
      expect(JSON.parse(sent[1]!.body!).acknowledgeConflicts).toBe('Разные узлы кластера');
      expect(sent[1]!.ifMatch).toBe('"3"');
    });
  });

  it('календарь показывает изменение и заморозку', async () => {
    renderAt('/agent/changes/calendar');
    await waitFor(() => expect(calls.some((c) => c.path === '/api/v1/changes/calendar')).toBe(true));
  });
});
