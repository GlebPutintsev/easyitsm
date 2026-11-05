import { diffLines } from 'diff';

export interface DiffRow {
  kind: 'same' | 'added' | 'removed';
  text: string;
  /** Номер строки в старой (removed, same) или новой (added) версии */
  oldNo?: number;
  newNo?: number;
}

/** Построчная разница двух версий статьи (KB-03 «Сравнить версии»). */
export function lineDiff(oldText: string, newText: string): DiffRow[] {
  const rows: DiffRow[] = [];
  let oldNo = 1;
  let newNo = 1;
  // Последняя строка без перевода строки иначе считается изменённой.
  const norm = (t: string) => (t.endsWith('\n') ? t : t + '\n');
  for (const part of diffLines(norm(oldText), norm(newText))) {
    const lines = part.value.replace(/\n$/, '').split('\n');
    for (const text of lines) {
      if (part.added) {
        rows.push({ kind: 'added', text, newNo: newNo++ });
      } else if (part.removed) {
        rows.push({ kind: 'removed', text, oldNo: oldNo++ });
      } else {
        rows.push({ kind: 'same', text, oldNo: oldNo++, newNo: newNo++ });
      }
    }
  }
  return rows;
}

/** Сжимает длинные неизменные участки: оставляет по `context` строк вокруг изменений. */
export function collapseUnchanged(rows: DiffRow[], context = 3): (DiffRow | { kind: 'gap'; count: number })[] {
  const keep = rows.map((r) => r.kind !== 'same');
  const visible = rows.map((_, i) => {
    for (let d = -context; d <= context; d++) {
      if (keep[i + d]) return true;
    }
    return false;
  });
  const out: (DiffRow | { kind: 'gap'; count: number })[] = [];
  let gap = 0;
  rows.forEach((r, i) => {
    if (visible[i]) {
      if (gap) out.push({ kind: 'gap', count: gap });
      gap = 0;
      out.push(r);
    } else {
      gap++;
    }
  });
  if (gap) out.push({ kind: 'gap', count: gap });
  return out;
}
