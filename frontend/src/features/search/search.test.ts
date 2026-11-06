import { describe, expect, it } from 'vitest';
import { hitLink, plainText, type SearchHit } from './api';

const hit = (over: Partial<SearchHit>): SearchHit => ({ id: 'x', kind: 'ticket', title: [], snippet: [], ...over }) as SearchHit;

describe('глобальный поиск', () => {
  it('ведёт заявку в своё пространство по номеру', () => {
    expect(hitLink(hit({ kind: 'ticket', ref: 'INC-000042' }), 'agent')).toBe('/agent/tickets/INC-000042');
    expect(hitLink(hit({ kind: 'ticket', ref: 'INC-000042' }), 'portal')).toBe('/portal/tickets/INC-000042');
    expect(hitLink(hit({ kind: 'article', id: 'a1' }), 'agent')).toBe('/agent/kb/a1');
    expect(hitLink(hit({ kind: 'service', id: 's1' }), 'agent')).toBe('/portal/catalog/s1');
  });

  it('склеивает подсвеченный текст', () => {
    expect(plainText([{ text: 'Не работает ', hl: false }, { text: 'VPN', hl: true }])).toBe('Не работает VPN');
  });
});
