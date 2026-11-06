import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { httpTicketsApi, resetDirectoryCache, toRawFilter } from './httpTicketsApi';

describe('toRawFilter', () => {
  const now = Date.parse('2026-11-05T12:00:00Z');

  it('«мои» уходят как scope, без id пользователя', () => {
    expect(toRawFilter({ assignee: ['me'] }, now)).toMatchObject({ scope: 'assigned_to_me', assigneeId: undefined });
  });

  it('«без исполнителя» и списки через запятую', () => {
    const f = toRawFilter({ assignee: ['none'], priority: ['P1', 'P2'], status: ['new', 'assigned'] }, now);
    expect(f).toMatchObject({ scope: undefined, assigneeId: 'none', priority: 'P1,P2', statusCategory: 'new,assigned' });
  });

  it('просроченные и под угрозой — через dueBefore', () => {
    expect(toRawFilter({ sla: ['breached'] }, now).dueBefore).toBe('2026-11-05T12:00:00.000Z');
    expect(toRawFilter({ sla: ['at_risk'] }, now).dueBefore).toBe('2026-11-05T13:00:00.000Z');
    expect(toRawFilter({}, now).dueBefore).toBeUndefined();
  });
});

describe('имена людей и услуг на настоящем API', () => {
  const AGENT = '01930000-0000-7000-8000-0000000000a1';
  const REQUESTER = '01930000-0000-7000-8000-0000000000b1';
  const GONE = '01930000-0000-7000-8000-0000000000c1';
  const ITEM = '01930000-0000-7000-8000-0000000000d1';

  const directory = {
    me: { id: AGENT, name: 'Анна Смирнова' },
    groups: [],
    agents: [{ id: AGENT, name: 'Анна Смирнова', groupIds: [], openTickets: 0, available: true }],
    services: [{ id: ITEM, name: 'Новый ноутбук' }],
    categories: [],
    resolutionCodes: [],
    pendingReasons: [],
    cancelReasons: [],
  };

  const ticket = (id: string, requesterId: string) => ({
    id,
    number: `INC-${id.slice(-3)}`,
    type: 'incident',
    title: 'Не печатает',
    status: { code: 'new', name: 'Новая', category: 'new' },
    priority: 'P3',
    requesterId,
    assigneeId: AGENT,
    groupId: null,
    serviceId: null,
    categoryId: null,
    catalogItemId: ITEM,
    channel: 'portal',
    confidential: false,
    slaDueAt: null,
    dueAt: null,
    awaitingAgent: false,
    createdAt: '2026-11-05T10:00:00Z',
    updatedAt: '2026-11-05T10:00:00Z',
  });

  let calls: string[] = [];
  let peopleFails = false;

  beforeEach(() => {
    resetDirectoryCache();
    calls = [];
    peopleFails = false;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: URL) => {
        const url = new URL(input);
        calls.push(url.pathname + url.search);
        const json = (body: unknown, status = 200) =>
          new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
        if (url.pathname === '/api/v1/agent-workspace/directory') return json(directory);
        if (url.pathname === '/api/v1/people') {
          if (peopleFails) return json({ status: 503 }, 503);
          const ids = url.searchParams.get('ids')!.split(',');
          return json(
            ids.includes(REQUESTER) ? [{ id: REQUESTER, displayName: 'Павел Соколов', email: 'p@example.ru', active: true }] : [],
          );
        }
        if (url.pathname === '/api/v1/tickets/count') return json({ count: 2 });
        if (url.pathname === '/api/v1/tickets') {
          return json({ items: [ticket('t-001', REQUESTER), ticket('t-002', GONE)], nextCursor: null });
        }
        return json({ status: 404 }, 404);
      }),
    );
  });

  afterEach(() => vi.unstubAllGlobals());

  const query = { filter: {}, sort: { field: 'sla', dir: 'asc' } } as const;

  it('заявителя берёт из /people одним запросом, агента — из справочника', async () => {
    const page = await httpTicketsApi.list(query);
    expect(page.items[0]!.requester).toEqual({ id: REQUESTER, name: 'Павел Соколов', email: 'p@example.ru' });
    expect(page.items[0]!.assignee?.name).toBe('Анна Смирнова');
    const peopleCalls = calls.filter((c) => c.startsWith('/api/v1/people'));
    expect(peopleCalls).toHaveLength(1);
    expect(decodeURIComponent(peopleCalls[0]!)).toBe(`/api/v1/people?ids=${REQUESTER},${GONE}`);
  });

  it('неизвестный человек остаётся коротким id и больше не запрашивается', async () => {
    await httpTicketsApi.list(query);
    const page = await httpTicketsApi.list(query);
    expect(page.items[1]!.requester.name).toBe(GONE.slice(0, 8));
    expect(calls.filter((c) => c.startsWith('/api/v1/people'))).toHaveLength(1);
  });

  it('без /people очередь всё равно открывается, имена подгрузятся позже', async () => {
    peopleFails = true;
    const first = await httpTicketsApi.list(query);
    expect(first.items[0]!.requester.name).toBe(REQUESTER.slice(0, 8));
    peopleFails = false;
    const second = await httpTicketsApi.list(query);
    expect(second.items[0]!.requester.name).toBe('Павел Соколов');
  });

  it('услуга — предложение каталога, если serviceId не задан', async () => {
    const page = await httpTicketsApi.list(query);
    expect(page.items[0]!.service).toEqual({ id: ITEM, name: 'Новый ноутбук' });
  });
});
