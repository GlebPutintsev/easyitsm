import { Anchor, Code, List, Text } from '@mantine/core';
import { Fragment, type ReactNode } from 'react';

/**
 * Безопасный рендер Markdown без HTML (контракт tickets: описание и лента — Markdown без HTML).
 * Строит React-элементы, dangerouslySetInnerHTML не используется, ссылки только http(s), mailto и пути
 * внутри приложения (/agent/…, /portal/…), например на статью базы знаний из заметки в ленте.
 * Поддерживается то, что даёт панель редактора: абзацы, списки, **жирный**, *курсив*, `код`,
 * блоки ```кода```, [ссылки](url), автоссылки и @упоминания.
 */
export function Markdown({ text, size = 'sm' }: { text: string; size?: 'xs' | 'sm' | 'md' }) {
  return <div className="ew-md">{renderBlocks(text, size)}</div>;
}

function renderBlocks(text: string, size: 'xs' | 'sm' | 'md'): ReactNode[] {
  const lines = text.replace(/\r\n?/g, '\n').split('\n');
  const out: ReactNode[] = [];
  let i = 0;
  let key = 0;
  while (i < lines.length) {
    const line = lines[i] ?? '';
    if (line.startsWith('```')) {
      const body: string[] = [];
      i += 1;
      while (i < lines.length && !(lines[i] ?? '').startsWith('```')) body.push(lines[i++] ?? '');
      i += 1;
      out.push(
        <Code block key={key++} my={6}>
          {body.join('\n')}
        </Code>,
      );
      continue;
    }
    const bullet = /^\s*[-*]\s+/;
    const ordered = /^\s*\d+[.)]\s+/;
    if (bullet.test(line) || ordered.test(line)) {
      const isOrdered = ordered.test(line);
      const re = isOrdered ? ordered : bullet;
      const items: string[] = [];
      while (i < lines.length && re.test(lines[i] ?? '')) items.push((lines[i++] ?? '').replace(re, ''));
      out.push(
        <List key={key++} type={isOrdered ? 'ordered' : 'unordered'} size={size} my={4} withPadding>
          {items.map((item, n) => (
            <List.Item key={n}>{renderInline(item)}</List.Item>
          ))}
        </List>,
      );
      continue;
    }
    if (!line.trim()) {
      i += 1;
      continue;
    }
    const para: string[] = [];
    while (i < lines.length && (lines[i] ?? '').trim() && !(lines[i] ?? '').startsWith('```') && !bullet.test(lines[i] ?? '') && !ordered.test(lines[i] ?? '')) {
      para.push(lines[i++] ?? '');
    }
    out.push(
      <Text key={key++} size={size} my={4} style={{ overflowWrap: 'anywhere' }}>
        {para.map((p, n) => (
          <Fragment key={n}>
            {n > 0 && <br />}
            {renderInline(p)}
          </Fragment>
        ))}
      </Text>,
    );
  }
  return out;
}

const INLINE = /(`[^`]+`)|(\*\*[^*]+\*\*)|(\*[^*\s][^*]*\*)|(\[[^\]]+\]\([^)\s]+\))|(https?:\/\/[^\s<>()]+[^\s<>().,;:!?])|(@[\p{L}][\p{L}.-]*(?:\s[\p{Lu}][\p{L}-]+)?)/gu;

export function safeHref(url: string): string | null {
  return /^(https?:\/\/|mailto:)/i.test(url) || isInternal(url) ? url : null;
}

/** Путь внутри приложения: открывается в той же вкладке. */
export function isInternal(url: string): boolean {
  return /^\/(agent|portal)\/[\w\-/]*$/.test(url);
}

export function renderInline(text: string): ReactNode[] {
  const out: ReactNode[] = [];
  let last = 0;
  let key = 0;
  for (const m of text.matchAll(INLINE)) {
    const start = m.index ?? 0;
    if (start > last) out.push(text.slice(last, start));
    const [token] = m;
    if (m[1]) out.push(<Code key={key++}>{token.slice(1, -1)}</Code>);
    else if (m[2]) out.push(<strong key={key++}>{token.slice(2, -2)}</strong>);
    else if (m[3]) out.push(<em key={key++}>{token.slice(1, -1)}</em>);
    else if (m[4]) {
      const [, label = '', url = ''] = /^\[([^\]]+)\]\(([^)\s]+)\)$/.exec(token) ?? [];
      const href = safeHref(url);
      out.push(
        href && isInternal(href) ? (
          <Anchor key={key++} href={href}>
            {label}
          </Anchor>
        ) : href ? (
          <Anchor key={key++} href={href} target="_blank" rel="noopener noreferrer nofollow">
            {label}
          </Anchor>
        ) : (
          label
        ),
      );
    } else if (m[5]) {
      out.push(
        <Anchor key={key++} href={token} target="_blank" rel="noopener noreferrer nofollow" style={{ wordBreak: 'break-all' }}>
          {token}
        </Anchor>,
      );
    } else if (m[6]) {
      out.push(
        <span key={key++} className="ew-mention">
          {token}
        </span>,
      );
    }
    last = start + token.length;
  }
  if (last < text.length) out.push(text.slice(last));
  return out;
}
