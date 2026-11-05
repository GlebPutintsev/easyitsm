import { describe, expect, it } from 'vitest';
import { mainActionOf, priorityOf, scanSecrets, slaToneOf } from './domain';
import type { AvailableTransition, StatusCategory } from './model';

const tr = (category: StatusCategory): AvailableTransition => ({ to: category, name: category, category, requiredFields: [] });
const ticket = (category: StatusCategory, assigneeId: string | null, transitions: AvailableTransition[]) => ({
  status: { code: category, category, name: category },
  assignee: assigneeId ? { id: assigneeId, name: 'x' } : null,
  transitions,
});

describe('матрица приоритета (5.2)', () => {
  it('считает приоритет по влиянию и срочности', () => {
    expect(priorityOf('high', 'high')).toBe('P1');
    expect(priorityOf('medium', 'high')).toBe('P2');
    expect(priorityOf('low', 'medium')).toBe('P4');
    expect(priorityOf('high', 'low')).toBe('P3');
  });
});

describe('главное действие карточки (10.7)', () => {
  it('новую заявку предлагает взять в работу', () => {
    expect(mainActionOf(ticket('new', null, [tr('in_progress')]), 'me')).toBe('take');
  });
  it('свою заявку в работе предлагает решить, чужую — взять', () => {
    expect(mainActionOf(ticket('in_progress', 'me', [tr('pending'), tr('resolved')]), 'me')).toBe('resolve');
    expect(mainActionOf(ticket('in_progress', 'other', [tr('resolved')]), 'me')).toBe('take');
  });
  it('ожидание и решённую предлагает возобновить, если переход есть', () => {
    expect(mainActionOf(ticket('pending', 'me', [tr('in_progress')]), 'me')).toBe('resume');
    expect(mainActionOf(ticket('resolved', 'me', []), 'me')).toBeNull();
  });
});

describe('поиск секретов (TCK-17)', () => {
  it('находит пароль и маскирует его', () => {
    const r = scanSecrets('Логин ivanov, пароль: Qwerty123!');
    expect(r.findings).toHaveLength(1);
    expect(r.masked).not.toContain('Qwerty123');
    expect(r.masked).toContain('Логин ivanov');
  });
  it('находит токены и закрытые ключи', () => {
    expect(scanSecrets('ghp_abcdefghijklmnopqrstuvwxyz0123').findings[0]?.kind).toBe('token');
    expect(scanSecrets('-----BEGIN RSA PRIVATE KEY-----\nabc\n-----END RSA PRIVATE KEY-----').findings[0]?.kind).toBe('private_key');
  });
  it('не срабатывает на обычный текст про пароль', () => {
    expect(scanSecrets('Сменила пароль по требованию системы, теперь не могу войти').findings).toHaveLength(0);
  });
});

describe('цвет таймера SLA', () => {
  const now = Date.parse('2026-11-05T12:00:00Z');
  it('просрочен, под угрозой, в норме, на паузе', () => {
    expect(slaToneOf({ kind: 'resolution', state: 'running', dueAt: '2026-11-05T11:00:00Z' }, now)).toBe('breached');
    expect(slaToneOf({ kind: 'resolution', state: 'running', dueAt: '2026-11-05T12:30:00Z' }, now)).toBe('risk');
    expect(slaToneOf({ kind: 'resolution', state: 'running', dueAt: '2026-11-05T18:00:00Z' }, now)).toBe('ok');
    expect(slaToneOf({ kind: 'resolution', state: 'paused', dueAt: '2026-11-05T18:00:00Z', remainingMs: 1000 }, now)).toBe('paused');
  });
});
