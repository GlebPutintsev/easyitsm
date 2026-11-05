import { describe, expect, it } from 'vitest';
import { ApiError } from '@/shared/api/client';
import { etagOf, renderTemplate, secretFindings, ticketQuery } from './api';

describe('ticketQuery', () => {
  it('пропускает пустые значения и повторяет custom', () => {
    expect(
      ticketQuery(
        { scope: 'assigned_to_me', q: '  vpn ', groupId: '', custom: ['os:linux', 'floor:3'], awaitingAgent: false },
        { sort: 'due', limit: 50, cursor: undefined },
      ),
    ).toBe('?scope=assigned_to_me&q=vpn&custom=os%3Alinux&custom=floor%3A3&awaitingAgent=false&sort=due&limit=50');
    expect(ticketQuery({})).toBe('');
  });
});

describe('помощники', () => {
  it('ETag из версии', () => {
    expect(etagOf({ version: 3 })).toBe('"3"');
  });

  it('подставляет поля заявки в готовый ответ', () => {
    expect(renderTemplate('Заявка {{ticket.number}}: {{ ticket.title }} {{ticket.unknown}}', { number: 'INC-000001', title: 'VPN' }))
      .toBe('Заявка INC-000001: VPN {{ticket.unknown}}');
  });

  it('достаёт найденные секреты из ошибки 422', () => {
    const error = new ApiError({ status: 422, code: 'secrets_detected', findings: [{ kind: 'password', start: 0, end: 3, masked: '***' }] });
    expect(secretFindings(error)).toHaveLength(1);
    expect(secretFindings(new Error('x'))).toBeNull();
  });
});
