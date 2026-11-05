import { describe, expect, it } from 'vitest';
import { toRawFilter } from './httpTicketsApi';

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
