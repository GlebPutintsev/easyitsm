/**
 * Операции панели инструментов редактора над текстом Markdown. Чистые функции: принимают текст и выделение,
 * возвращают новый текст и выделение. Так их легко проверить тестами и отменить через историю браузера.
 */
export interface EditState {
  text: string;
  start: number;
  end: number;
}

export type EditCommand =
  | 'heading'
  | 'bold'
  | 'italic'
  | 'code'
  | 'codeBlock'
  | 'bulletList'
  | 'numberedList'
  | 'quote'
  | 'link'
  | 'image'
  | 'table';

/** Обрамляет выделение маркерами, например **жирный**. Без выделения вставляет заготовку и выделяет её. */
function wrap(s: EditState, before: string, after: string, placeholder: string): EditState {
  const selected = s.text.slice(s.start, s.end) || placeholder;
  const text = s.text.slice(0, s.start) + before + selected + after + s.text.slice(s.end);
  const start = s.start + before.length;
  return { text, start, end: start + selected.length };
}

/** Добавляет префикс к каждой строке, которой касается выделение. */
function prefixLines(s: EditState, prefix: (i: number) => string): EditState {
  const lineStart = s.text.lastIndexOf('\n', s.start - 1) + 1;
  const nextBreak = s.text.indexOf('\n', s.end);
  const lineEnd = nextBreak === -1 ? s.text.length : nextBreak;
  const lines = s.text.slice(lineStart, lineEnd).split('\n');
  const replaced = lines.map((l, i) => prefix(i) + l).join('\n');
  const text = s.text.slice(0, lineStart) + replaced + s.text.slice(lineEnd);
  return { text, start: lineStart, end: lineStart + replaced.length };
}

/** Вставляет блок отдельными абзацами: пустая строка до и после, если её нет. */
function insertBlock(s: EditState, block: string, selectFrom: number, selectTo: number): EditState {
  const before = s.text.slice(0, s.start);
  const after = s.text.slice(s.end);
  const lead = before.length === 0 || before.endsWith('\n\n') ? '' : before.endsWith('\n') ? '\n' : '\n\n';
  const trail = after.length === 0 || after.startsWith('\n\n') ? '' : after.startsWith('\n') ? '\n' : '\n\n';
  const text = before + lead + block + trail + after;
  const base = before.length + lead.length;
  return { text, start: base + selectFrom, end: base + selectTo };
}

/** Заготовки текста на языке интерфейса для вставки без выделения. */
export interface EditLabels {
  text: string;
  code: string;
  linkText: string;
  imageAlt: string;
  column1: string;
  column2: string;
}

export function applyCommand(s: EditState, cmd: EditCommand, labels: EditLabels): EditState {
  const selected = s.text.slice(s.start, s.end);
  switch (cmd) {
    case 'heading':
      return prefixLines(s, () => '## ');
    case 'bold':
      return wrap(s, '**', '**', labels.text);
    case 'italic':
      return wrap(s, '_', '_', labels.text);
    case 'code':
      return wrap(s, '`', '`', labels.code);
    case 'quote':
      return prefixLines(s, () => '> ');
    case 'bulletList':
      return prefixLines(s, () => '- ');
    case 'numberedList':
      return prefixLines(s, (i) => `${i + 1}. `);
    case 'link':
      return wrap(s, '[', `](https://)`, labels.linkText);
    case 'image': {
      const alt = selected || labels.imageAlt;
      const block = `![${alt}](https://)`;
      const urlFrom = alt.length + 4;
      return insertBlock(s, block, urlFrom, urlFrom + 8);
    }
    case 'codeBlock': {
      const body = selected || labels.code;
      const block = '```\n' + body + '\n```';
      return insertBlock(s, block, 4, 4 + body.length);
    }
    case 'table': {
      const [a, b] = [labels.column1, labels.column2];
      const block = `| ${a} | ${b} |\n| --- | --- |\n|  |  |\n|  |  |`;
      return insertBlock(s, block, 2, 2 + a.length);
    }
  }
}
