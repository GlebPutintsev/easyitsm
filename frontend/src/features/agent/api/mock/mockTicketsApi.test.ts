import { beforeEach, describe, expect, it } from 'vitest';
import { ApiError } from '@/shared/api/client';
import { mockTicketsApi as api, resetMockState } from './mockTicketsApi';

describe('моки API заявок ведут себя как сервер', () => {
  beforeEach(() => resetMockState());

  it('фильтрует и сортирует по сроку SLA', async () => {
    const page = await api.list({ filter: { status: ['new'] }, sort: { field: 'sla', dir: 'asc' }, limit: 200 });
    expect(page.items.length).toBeGreaterThan(0);
    expect(page.items.every((t) => t.status.category === 'new')).toBe(true);
    const due = page.items.map((t) => (t.sla ? Date.parse(t.sla.dueAt) : Infinity));
    expect([...due].sort((a, b) => a - b)).toEqual(due);
  });

  it('отклоняет изменение с устаревшей версией (412)', async () => {
    const [first] = (await api.list({ filter: {}, sort: { field: 'number', dir: 'asc' }, limit: 1 })).items;
    const ticket = await api.get(first!.number);
    await api.update(ticket.id, { urgency: 'low' }, ticket.version);
    await expect(api.update(ticket.id, { urgency: 'high' }, ticket.version)).rejects.toMatchObject({ status: 412, code: 'version_conflict' });
  });

  it('решение требует кода и описания', async () => {
    const page = await api.list({ filter: { status: ['in_progress'] }, sort: { field: 'number', dir: 'asc' }, limit: 1 });
    const ticket = await api.get(page.items[0]!.number);
    await expect(api.transition(ticket.id, { to: 'resolved' }, ticket.version)).rejects.toBeInstanceOf(ApiError);
    const resolved = await api.transition(ticket.id, { to: 'resolved', resolutionCode: 'fixed', resolutionText: 'Готово' }, ticket.version);
    expect(resolved.status.category).toBe('resolved');
  });

  it('массовое действие можно отменить', async () => {
    const page = await api.list({ filter: { status: ['new'] }, sort: { field: 'number', dir: 'asc' }, limit: 3 });
    const ids = page.items.map((t) => t.id);
    const preview = await api.bulkPreview(ids, { kind: 'priority', priority: 'P1', reason: 'тест' });
    expect(preview.items).toHaveLength(3);
    const result = await api.bulkApply(ids, { kind: 'priority', priority: 'P1', reason: 'тест' }, 'k');
    expect((await api.get(page.items[0]!.number)).priority).toBe('P1');
    await api.bulkUndo(result.operationId);
    expect((await api.get(page.items[0]!.number)).priority).toBe(page.items[0]!.priority);
  });

  it('предупреждает о секрете в ответе', async () => {
    const [first] = (await api.list({ filter: { status: ['in_progress'] }, sort: { field: 'number', dir: 'asc' }, limit: 1 })).items;
    await expect(api.addActivity(first!.id, { kind: 'note', body: 'пароль: Secret123' }, 'k')).rejects.toMatchObject({ code: 'secrets_detected' });
    const masked = await api.addActivity(first!.id, { kind: 'note', body: 'пароль: Secret123', secrets: 'mask' }, 'k2');
    expect(masked.body).not.toContain('Secret123');
  });
});
