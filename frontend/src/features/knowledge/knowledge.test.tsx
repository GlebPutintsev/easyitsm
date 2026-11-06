import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderWithProviders } from '@/test/render';
import { ArticleMarkdown } from './ArticleMarkdown';
import { patchOf } from './ArticleEditor';
import { collapseUnchanged, lineDiff } from './lineDiff';
import { applyCommand, type EditLabels } from './markdownEditing';
import { outline, readingMinutes } from './outline';

const labels: EditLabels = { text: 'текст', code: 'код', linkText: 'ссылка', imageAlt: 'схема', column1: 'A', column2: 'B' };

describe('applyCommand', () => {
  it('обрамляет выделение и выделяет результат', () => {
    const r = applyCommand({ text: 'один два', start: 5, end: 8 }, 'bold', labels);
    expect(r.text).toBe('один **два**');
    expect(r.text.slice(r.start, r.end)).toBe('два');
  });

  it('без выделения вставляет заготовку', () => {
    const r = applyCommand({ text: '', start: 0, end: 0 }, 'link', labels);
    expect(r.text).toBe('[ссылка](https://)');
    expect(r.text.slice(r.start, r.end)).toBe('ссылка');
  });

  it('нумерует все выделенные строки', () => {
    const r = applyCommand({ text: 'а\nб\nв', start: 0, end: 5 }, 'numberedList', labels);
    expect(r.text).toBe('1. а\n2. б\n3. в');
  });

  it('вставляет таблицу отдельным абзацем', () => {
    const r = applyCommand({ text: 'Текст', start: 5, end: 5 }, 'table', labels);
    expect(r.text).toBe('Текст\n\n| A | B |\n| --- | --- |\n|  |  |\n|  |  |');
    expect(r.text.slice(r.start, r.end)).toBe('A');
  });

  it('оборачивает выделение в блок кода', () => {
    const r = applyCommand({ text: 'ls -la', start: 0, end: 6 }, 'codeBlock', labels);
    expect(r.text).toBe('```\nls -la\n```');
  });
});

describe('lineDiff', () => {
  it('находит добавленные и удалённые строки', () => {
    const rows = lineDiff('a\nb\nc', 'a\nB\nc\nd');
    expect(rows.map((r) => `${r.kind}:${r.text}`)).toEqual(['same:a', 'removed:b', 'added:B', 'same:c', 'added:d']);
  });

  it('сворачивает длинные неизменные участки', () => {
    const old = Array.from({ length: 20 }, (_, i) => `l${i}`).join('\n');
    const rows = collapseUnchanged(lineDiff(old, old.replace('l10', 'x')), 2);
    expect(rows.filter((r) => r.kind === 'gap')).toHaveLength(2);
    expect(rows.filter((r) => r.kind !== 'gap')).toHaveLength(6);
  });
});

describe('patchOf', () => {
  const saved = { title: 'T', body: 'B', categoryId: 'c1', audience: 'all' as const, groupId: '' };

  it('отправляет только изменённые поля', () => {
    expect(patchOf(saved, { ...saved, body: 'B2' })?.input).toEqual({ body: 'B2' });
    expect(patchOf(saved, saved)).toBeNull();
    expect(patchOf(saved, { ...saved, categoryId: null })?.input).toEqual({ clearCategory: true });
  });

  it('не отправляет группу без идентификатора и пустое название', () => {
    expect(patchOf(saved, { ...saved, audience: 'group' })).toBeNull();
    expect(patchOf(saved, { ...saved, audience: 'group', groupId: 'g' })?.input).toEqual({ audience: 'group', audienceGroupId: 'g' });
    expect(patchOf(saved, { ...saved, title: '  ' })).toBeNull();
  });
});

describe('ArticleMarkdown', () => {
  it('рисует таблицы и не исполняет HTML и javascript-ссылки', () => {
    renderWithProviders(
      <ArticleMarkdown>{'| A | B |\n|---|---|\n| 1 | 2 |\n\n<img src=x onerror=alert(1)>\n\n[клик](javascript:alert(1))'}</ArticleMarkdown>,
    );
    expect(screen.getByRole('table')).toBeInTheDocument();
    expect(document.querySelector('img[onerror]')).toBeNull();
    expect(screen.getByText('клик').getAttribute('href') ?? '').not.toContain('javascript');
  });
});

describe('оглавление статьи', () => {
  const body = '# Заголовок\n\nТекст\n\n## Быстрая **проверка**\n\n```\n## не заголовок\n```\n\n### Шаг [1](https://x.ru)\n\n## Быстрая проверка\n';

  it('берёт уровни 2 и 3, пропускает код и делает id уникальными', () => {
    expect(outline(body)).toEqual([
      { id: 'быстрая-проверка', text: 'Быстрая проверка', level: 2, line: 5 },
      { id: 'шаг-1', text: 'Шаг 1', level: 3, line: 11 },
      { id: 'быстрая-проверка-2', text: 'Быстрая проверка', level: 2, line: 13 },
    ]);
  });

  it('ставит те же id заголовкам в тексте статьи', () => {
    renderWithProviders(<ArticleMarkdown>{body}</ArticleMarkdown>);
    const h2 = screen.getAllByRole('heading', { level: 2 });
    expect(h2.map((h) => h.id)).toEqual(['быстрая-проверка', 'быстрая-проверка-2']);
    expect(screen.getByRole('heading', { level: 3 }).id).toBe('шаг-1');
  });

  it('считает время чтения без кода, не меньше минуты', () => {
    expect(readingMinutes('коротко')).toBe(1);
    expect(readingMinutes(Array(540).fill('слово').join(' '))).toBe(3);
  });
});
