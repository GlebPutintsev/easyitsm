import { describe, expect, it } from 'vitest';
import type { SavedView } from '../api/model';
import { csvCell } from './exportCsv';
import { filterCount, filterFromParams, paramsFromFilter, sameFilter } from './filters';

const view: SavedView = {
  id: 'mine',
  name: 'mine',
  visibility: 'personal',
  builtIn: true,
  filter: { assignee: ['me'], status: ['new', 'in_progress'] },
  sort: { field: 'sla', dir: 'asc' },
};

describe('фильтр в адресе страницы', () => {
  it('без параметров берёт фильтр представления', () => {
    expect(filterFromParams(new URLSearchParams(), view)).toEqual(view.filter);
  });
  it('изменённый фильтр переживает переход по ссылке', () => {
    const changed = { ...view.filter, priority: ['P1' as const], q: 'почта' };
    const params = paramsFromFilter(changed, new URLSearchParams());
    const restored = filterFromParams(new URLSearchParams(params.toString()), view);
    expect(sameFilter(restored, changed)).toBe(true);
    expect(filterCount(restored)).toBe(3);
  });
  it('поиск поверх представления не делает его изменённым', () => {
    const f = filterFromParams(new URLSearchParams('q=vpn'), view);
    expect(f.q).toBe('vpn');
    expect(sameFilter({ ...f, q: undefined }, view.filter)).toBe(true);
  });
});

describe('экспорт CSV', () => {
  it('защищает от формул и экранирует разделители', () => {
    expect(csvCell('=HYPERLINK("http://x")')).toBe(`"'=HYPERLINK(""http://x"")"`);
    expect(csvCell('+79001234567')).toBe("'+79001234567");
    expect(csvCell('Иванов; Петров')).toBe('"Иванов; Петров"');
    expect(csvCell('обычный текст')).toBe('обычный текст');
  });
});
