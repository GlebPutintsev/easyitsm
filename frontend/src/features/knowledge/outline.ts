/** Заголовок статьи для блока «На этой странице». */
export interface OutlineItem {
  id: string;
  text: string;
  level: 2 | 3;
  /** Строка исходного Markdown, начиная с 1: по ней рендер находит id заголовка */
  line: number;
}

const FENCE = /^\s{0,3}(```|~~~)/;
const HEADING = /^\s{0,3}(#{1,6})\s+(.+?)\s*#*\s*$/;

/** Текст заголовка без разметки: **жирный**, `код`, [ссылка](url). */
export function plainHeading(raw: string): string {
  return raw
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/[*_`~]/g, '')
    .trim();
}

export function slugify(text: string): string {
  const slug = text
    .toLowerCase()
    .replace(/[^\p{L}\p{N}\s-]/gu, '')
    .trim()
    .replace(/\s+/g, '-');
  return slug || 'section';
}

/**
 * Все заголовки статьи по порядку с уникальными id (повтор получает суффикс «-2», «-3»).
 * Заголовки внутри блоков кода пропускаются. В оглавление попадают уровни 2 и 3,
 * id нужны всем уровням, поэтому они считаются по всем.
 */
export function headings(body: string): { id: string; text: string; depth: number; line: number }[] {
  const out: { id: string; text: string; depth: number; line: number }[] = [];
  const seen = new Map<string, number>();
  let fenced = false;
  body.split(/\r?\n/).forEach((line, i) => {
    if (FENCE.test(line)) {
      fenced = !fenced;
      return;
    }
    if (fenced) return;
    const m = HEADING.exec(line);
    if (!m) return;
    const text = plainHeading(m[2] ?? '');
    if (!text) return;
    const base = slugify(text);
    const n = (seen.get(base) ?? 0) + 1;
    seen.set(base, n);
    out.push({ id: n === 1 ? base : `${base}-${n}`, text, depth: (m[1] ?? '').length, line: i + 1 });
  });
  return out;
}

/** Оглавление: заголовки второго и третьего уровня. */
export function outline(body: string): OutlineItem[] {
  return headings(body)
    .filter((h) => h.depth === 2 || h.depth === 3)
    .map((h) => ({ id: h.id, text: h.text, level: h.depth as 2 | 3, line: h.line }));
}

/** Время чтения в минутах: 180 слов в минуту, не меньше одной. */
export function readingMinutes(body: string): number {
  const words = body.replace(/```[\s\S]*?```/g, ' ').match(/[\p{L}\p{N}]+/gu)?.length ?? 0;
  return Math.max(1, Math.round(words / 180));
}
