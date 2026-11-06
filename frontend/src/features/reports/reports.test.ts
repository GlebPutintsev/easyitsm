import { describe, expect, it } from 'vitest';
import type { Widget } from './api';
import { niceMax, presetOf, presetRange } from './format';
import { spans } from './ReportsPage';

const widget = (id: string, kind: Widget['kind']): Widget => ({ id, kind, title: id, columns: [], rows: [] });

describe('отчёты', () => {
  it('периоды: пресеты и узнавание пресета по датам', () => {
    const today = new Date(2026, 10, 6);
    expect(presetRange('last7', today)).toEqual({ from: '2026-10-31', to: '2026-11-06' });
    expect(presetRange('prevMonth', today)).toEqual({ from: '2026-10-01', to: '2026-10-31' });
    expect(presetRange('thisMonth', today)).toEqual({ from: '2026-11-01', to: '2026-11-06' });
    expect(presetOf('2026-10-08', '2026-11-06', today)).toBe('last30');
    expect(presetOf('2026-10-02', '2026-11-06', today)).toBe('custom');
  });

  it('верх шкалы округляется вверх до «красивого» числа', () => {
    expect(niceMax(0)).toBe(4);
    expect(niceMax(7)).toBe(10);
    expect(niceMax(23)).toBe(25);
    expect(niceMax(180)).toBe(200);
  });

  it('столбчатые графики по два в ряд, непарный — во всю ширину', () => {
    expect(spans([widget('k', 'kpi'), widget('a', 'bar'), widget('b', 'bar')])).toEqual([true, false, false]);
    expect(spans([widget('k', 'kpi'), widget('a', 'bar'), widget('l', 'line'), widget('t', 'table')])).toEqual([
      true,
      true,
      true,
      true,
    ]);
    expect(spans([widget('a', 'bar'), widget('b', 'bar'), widget('c', 'bar')])).toEqual([false, false, true]);
  });
});
