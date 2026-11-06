import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { theme } from '@/app/theme';
import type { Ci, CiClass, CiRef, Conflict } from './api';
import { CiPage } from './CiPage';
import { CmdbPage } from './CmdbPage';
import { QualityPage } from './QualityPage';
import { ReconciliationPage } from './ReconciliationPage';
import { SourcesPage } from './SourcesPage';

const ME = '00000000-0000-7000-8000-000000000001';
const SRV = '00000000-0000-7000-8000-0000000000c1';
const APP = '00000000-0000-7000-8000-0000000000c2';
const SVC = '00000000-0000-7000-8000-0000000000c3';
const DUP = '00000000-0000-7000-8000-0000000000c4';

const server = (over: Partial<Ci> = {}): Ci => ({
  id: SRV,
  name: 'srv-db-01',
  classCode: 'physical_server',
  className: 'Физический сервер',
  layer: 'infrastructure',
  status: 'active',
  environment: 'production',
  criticality: 'high',
  ownerGroupId: null,
  description: '',
  attributes: { serial_number: 'SN-42', hostname: 'srv-db-01' },
  attributeSources: { serial_number: 'discovery', hostname: 'manual' },
  lastSeenAt: '2026-11-06T08:00:00Z',
  createdAt: '2026-11-01T08:00:00Z',
  createdBy: ME,
  updatedAt: '2026-11-06T08:00:00Z',
  updatedBy: ME,
  version: 2,
  mergedInto: null,
  ...over,
});

const ref = (id: string, name: string, over: Partial<CiRef> = {}): CiRef => ({
  id,
  name,
  classCode: 'physical_server',
  className: 'Физический сервер',
  layer: 'infrastructure',
  status: 'active',
  environment: null,
  criticality: 'medium',
  ownerGroupId: null,
  ...over,
});

const physical: CiClass = {
  id: 'k1',
  parentId: null,
  code: 'physical_server',
  name: 'Физический сервер',
  layer: 'infrastructure',
  isAbstract: false,
  system: true,
  active: true,
  attributes: [
    { code: 'serial_number', name: 'Серийный номер', type: 'string', required: false, options: [] },
    { code: 'hostname', name: 'Имя хоста', type: 'string', required: false, options: [] },
  ],
  ownAttributes: [],
  identificationRules: [['serial_number']],
  version: 0,
};

const attributeConflict: Conflict = {
  id: 'x1',
  kind: 'attribute',
  status: 'open',
  source: { code: 'discovery', name: 'Агент обнаружения' },
  externalId: 'hw-1',
  ci: ref(SRV, 'srv-db-01'),
  attribute: 'hostname',
  currentValue: 'srv-db-01',
  proposedValue: 'srv-db-01.corp',
  currentSource: 'manual',
  candidates: [],
  itemName: null,
  itemClassCode: null,
  createdAt: '2026-11-06T08:00:00Z',
  updatedAt: '2026-11-06T08:00:00Z',
  resolvedAt: null,
  resolvedBy: null,
  version: 0,
};

if (!('fonts' in document)) {
  Object.defineProperty(document, 'fonts', { value: { addEventListener() {}, removeEventListener() {}, ready: Promise.resolve() } });
}

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

describe('CMDB', () => {
  const fetchMock = vi.fn<typeof fetch>();
  let roles = ['CFG'];

  beforeEach(() => {
    roles = ['CFG'];
    fetchMock.mockReset();
    fetchMock.mockImplementation(async (input, init) => {
      const url = new URL(String(input));
      const method = init?.method ?? 'GET';
      const path = url.pathname;
      if (path === '/api/v1/me') return json({ id: ME, displayName: 'Менеджер', roles });
      if (path === '/api/v1/cmdb/classes') return json([physical]);
      if (path === '/api/v1/cmdb/relation-types') return json([]);
      if (path === '/api/v1/groups') return json({ items: [], nextCursor: null });
      if (path === '/api/v1/cmdb/cis' && method === 'GET') return json({ items: [server()], nextCursor: null });
      if (path === `/api/v1/cmdb/cis/${SRV}`) return json(server());
      if (path === `/api/v1/cmdb/cis/${SRV}/relations`) {
        return json([
          {
            id: 'r1',
            typeCode: 'runs_on',
            label: 'Выполняет',
            direction: 'incoming',
            impactDirection: 'target_to_source',
            other: { id: APP, name: 'Биллинг', classCode: 'application', className: 'Приложение', layer: 'application', status: 'active' },
            origin: 'manual',
            validFrom: '2026-11-01T08:00:00Z',
          },
        ]);
      }
      if (path === `/api/v1/cmdb/cis/${SRV}/impact`) {
        return json({
          roots: [ref(SRV, 'srv-db-01')],
          impacted: [
            { ci: ref(APP, 'Биллинг', { layer: 'application', className: 'Приложение' }), depth: 1, path: [SRV, APP] },
            { ci: ref(SVC, 'Расчёты с клиентами', { layer: 'business_service', className: 'Бизнес-услуга', criticality: 'critical' }), depth: 2, path: [SRV, APP, SVC] },
          ],
          services: [{ ci: ref(SVC, 'Расчёты с клиентами', { layer: 'business_service', criticality: 'critical' }), depth: 2, path: [SRV, APP, SVC] }],
          tickets: [{ id: 't1', number: 'INC-000042', title: 'Биллинг не отвечает', type: 'incident', priority: 'p2', statusCategory: 'in_progress', ciId: APP, serviceId: null }],
          hiddenTickets: 2,
          depth: 3,
          truncated: false,
        });
      }
      if (path === '/api/v1/cmdb/conflicts') return json({ items: [attributeConflict], nextCursor: null });
      if (path === '/api/v1/cmdb/conflicts/x1/resolve') return json({ ...attributeConflict, status: 'accepted' });
      if (path === '/api/v1/cmdb/quality') return json({ total: 10, incomplete: 2, stale: 0, orphans: 1, duplicateGroups: 1, duplicates: 2, score: 70 });
      if (path === '/api/v1/cmdb/quality/issues') return json([ref(APP, 'Биллинг')]);
      if (path === '/api/v1/cmdb/quality/duplicates') return json([{ key: 'serial_number', value: 'SN-42', cis: [ref(SRV, 'srv-db-01'), ref(DUP, 'srv-db-01 (копия)')] }]);
      if (path === `/api/v1/cmdb/cis/${SRV}/merge`) return json(server({ version: 3 }));
      if (path === '/api/v1/cmdb/sources' && method === 'GET') return json([]);
      if (path === '/api/v1/cmdb/sources' && method === 'POST') return json({ id: 's1', code: 'disc', name: 'Агент', kind: 'discovery', priority: 20, staleDays: 30, active: true, records: 0, lastBatchAt: null, createdAt: '', updatedAt: '', version: 0 }, 201);
      return json({ status: 404, code: 'not_found' }, 404);
    });
    vi.stubGlobal('fetch', fetchMock);
  });
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  function renderAt(path: string) {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    return render(
      <MantineProvider theme={theme} env="test">
        <QueryClientProvider client={qc}>
          <MemoryRouter initialEntries={[path]}>
            <Routes>
              <Route path="/agent/cmdb" element={<CmdbPage />} />
              <Route path="/agent/cmdb/reconciliation" element={<ReconciliationPage />} />
              <Route path="/agent/cmdb/quality" element={<QualityPage />} />
              <Route path="/agent/cmdb/sources" element={<SourcesPage />} />
              <Route path="/agent/cmdb/:id" element={<CiPage />} />
            </Routes>
          </MemoryRouter>
        </QueryClientProvider>
      </MantineProvider>,
    );
  }

  const calls = (part: string, method = 'GET') =>
    fetchMock.mock.calls.filter(([u, init]) => String(u).includes(part) && (init?.method ?? 'GET') === method);

  it('список показывает класс, статус и критичность словами, CFG может добавить КЕ', async () => {
    renderAt('/agent/cmdb');
    const table = await screen.findByRole('table');
    expect(within(table).getByText('srv-db-01')).toBeInTheDocument();
    expect(within(table).getByText('Физический сервер')).toBeInTheDocument();
    expect(within(table).getByText('В работе')).toBeInTheDocument();
    expect(within(table).getByText('Высокая')).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Добавить КЕ' })).toBeInTheDocument();
  });

  it('агент без CFG смотрит, но не добавляет', async () => {
    roles = ['AGT'];
    renderAt('/agent/cmdb');
    await screen.findByRole('table');
    await waitFor(() => expect(calls('/api/v1/me').length).toBeGreaterThan(0));
    expect(screen.queryByRole('button', { name: 'Добавить КЕ' })).not.toBeInTheDocument();
  });

  it('карточка показывает атрибуты с источником, связи и анализ влияния', async () => {
    renderAt(`/agent/cmdb/${SRV}`);
    expect(await screen.findByRole('heading', { name: 'srv-db-01' })).toBeInTheDocument();
    expect(await screen.findByText('SN-42')).toBeInTheDocument();
    expect(screen.getByText('источник: discovery')).toBeInTheDocument();
    expect(screen.getByText('источник: вручную')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('tab', { name: 'Связи' }));
    expect(await screen.findByText('Выполняет')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Биллинг' })).toHaveAttribute('href', `/agent/cmdb/${APP}`);

    fireEvent.click(screen.getByRole('tab', { name: 'Влияние' }));
    expect(await screen.findByText('Затронутые услуги')).toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: 'Расчёты с клиентами' }).length).toBeGreaterThan(0);
    expect(screen.getByRole('link', { name: 'INC-000042' })).toHaveAttribute('href', '/agent/tickets/INC-000042');
    expect(screen.getByText('Ещё 2 заявки скрыты правами доступа')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('radio', { name: '5' }));
    await waitFor(() => expect(calls('depth=5').length).toBe(1));
  });

  it('сверка: «Принять источник» отправляет решение по конфликту', async () => {
    renderAt('/agent/cmdb/reconciliation');
    expect(await screen.findByText('Атрибут «hostname»')).toBeInTheDocument();
    expect(screen.getByText('srv-db-01.corp')).toBeInTheDocument();
    expect(screen.getByText(/владелец: вручную/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Принять источник' }));
    await waitFor(() => expect(calls('/conflicts/x1/resolve', 'POST').length).toBe(1));
    expect(JSON.parse(String(calls('/conflicts/x1/resolve', 'POST')[0]?.[1]?.body))).toEqual({ action: 'accept' });
  });

  it('качество: оценка, замечания и слияние дубликатов в выбранную КЕ', async () => {
    renderAt('/agent/cmdb/quality');
    expect(await screen.findByText('70')).toBeInTheDocument();
    expect(await screen.findByRole('link', { name: 'Биллинг' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Дубликаты/ }));
    expect(await screen.findByText('serial_number: SN-42')).toBeInTheDocument();
    fireEvent.click(screen.getAllByRole('button', { name: 'Оставить эту' })[0]!);
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Слить остальные в «srv-db-01»' }));
    await waitFor(() => expect(calls(`/cmdb/cis/${SRV}/merge`, 'POST').length).toBe(1));
    expect(JSON.parse(String(calls(`/cmdb/cis/${SRV}/merge`, 'POST')[0]?.[1]?.body))).toEqual({ mergedId: DUP });
  });

  it('источники: CFG заводит источник с приоритетом', async () => {
    renderAt('/agent/cmdb/sources');
    expect(await screen.findByText('Источников пока нет')).toBeInTheDocument();
    fireEvent.click((await screen.findAllByRole('button', { name: 'Добавить источник' }))[0]!);
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByRole('textbox', { name: /Код/ }), { target: { value: 'disc' } });
    fireEvent.change(within(dialog).getByRole('textbox', { name: /Название/ }), { target: { value: 'Агент' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Создать' }));
    await waitFor(() => expect(calls('/api/v1/cmdb/sources', 'POST').length).toBe(1));
    expect(JSON.parse(String(calls('/api/v1/cmdb/sources', 'POST')[0]?.[1]?.body))).toMatchObject({ code: 'disc', name: 'Агент', kind: 'discovery', priority: 20 });
  });
});
