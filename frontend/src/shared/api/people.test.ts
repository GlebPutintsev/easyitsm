import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { clearPeopleCache, initials, loadPeople, loadPerson, shortId } from './people';

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

const id = (n: number) => `00000000-0000-7000-8000-${String(n).padStart(12, '0')}`;

describe('справочник людей', () => {
  const fetchMock = vi.fn<typeof fetch>();

  beforeEach(() => {
    clearPeopleCache();
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
  });
  afterEach(() => vi.unstubAllGlobals());

  it('собирает id одного кадра в один запрос и кэширует ответ', async () => {
    fetchMock.mockImplementation(async (input) => {
      const ids = new URL(String(input)).searchParams.get('ids')!.split(',');
      return json(ids.filter((x) => x !== id(3)).map((x) => ({ id: x, displayName: `Имя ${x.slice(-1)}`, email: null, active: true })));
    });
    const [a, b, c] = await Promise.all([loadPerson(id(1)), loadPerson(id(2)), loadPerson(id(3))]);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(String(fetchMock.mock.calls[0]?.[0])).toContain('/api/v1/people?ids=');
    expect(a?.displayName).toBe('Имя 1');
    expect(b?.displayName).toBe('Имя 2');
    expect(c).toBeNull();
    await loadPerson(id(1));
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('делит большой список по 100 id', async () => {
    fetchMock.mockImplementation(async () => json([]));
    const people = await loadPeople(Array.from({ length: 150 }, (_, i) => id(i)));
    expect(people.size).toBe(0);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('при ошибке не запоминает пустой ответ и спрашивает снова', async () => {
    fetchMock.mockResolvedValueOnce(json({ status: 503 }, 503));
    expect(await loadPerson(id(7))).toBeNull();
    fetchMock.mockResolvedValueOnce(json([{ id: id(7), displayName: 'Анна Ковалёва', email: 'a@x', active: false }]));
    expect((await loadPerson(id(7)))?.active).toBe(false);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('инициалы и короткий id', () => {
    expect(initials('Анна Ковалёва')).toBe('АК');
    expect(initials('  Глеб ')).toBe('Г');
    expect(initials('')).toBe('?');
    expect(shortId(id(42))).toBe('0000…0042');
  });
});
