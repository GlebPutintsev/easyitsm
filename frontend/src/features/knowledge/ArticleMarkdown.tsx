import { useMemo, type ReactNode } from 'react';
import ReactMarkdown, { type Components, type ExtraProps } from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { headings } from './outline';
import './knowledge.css';

/** Разрешённые схемы ссылок и изображений: никаких javascript: и data: из текста статьи. */
const SAFE_URL = /^(https?:|mailto:|tel:|\/|#)/i;

function safeUrl(url: string): string {
  return SAFE_URL.test(url.trim()) ? url : '';
}

type HeadingProps = { children?: ReactNode } & ExtraProps;
type Tag = 'h1' | 'h2' | 'h3' | 'h4' | 'h5' | 'h6';

const baseComponents: Components = {
  a: ({ href, title, children }) => {
    const external = !!href && /^https?:/i.test(href);
    return (
      <a href={href} title={title} {...(external ? { target: '_blank', rel: 'noopener noreferrer nofollow' } : {})}>
        {children}
      </a>
    );
  },
  img: ({ src, alt }) => (
    <img src={typeof src === 'string' ? src : undefined} alt={alt ?? ''} loading="lazy" referrerPolicy="no-referrer" />
  ),
  table: ({ children }) => (
    <div style={{ overflowX: 'auto' }}>
      <table>{children}</table>
    </div>
  ),
};

/**
 * Текст статьи (KB-01): Markdown с таблицами, кодом, изображениями и ссылками (GFM), набор 16 / 26.
 * HTML в тексте не исполняется: react-markdown без rehype-raw выводит его как текст.
 * Заголовки получают id из {@link headings}, на них ведёт блок «На этой странице».
 */
export function ArticleMarkdown({ children }: { children: string }) {
  const components = useMemo<Components>(() => {
    const ids = new Map(headings(children).map((h) => [h.line, h.id]));
    const heading = (Tag: Tag) =>
      function Heading({ node, children: content }: HeadingProps) {
        const line = node?.position?.start.line;
        return <Tag id={line != null ? ids.get(line) : undefined}>{content}</Tag>;
      };
    return {
      ...baseComponents,
      h1: heading('h1'),
      h2: heading('h2'),
      h3: heading('h3'),
      h4: heading('h4'),
      h5: heading('h5'),
      h6: heading('h6'),
    };
  }, [children]);

  return (
    <div className="kb-prose">
      <ReactMarkdown remarkPlugins={[remarkGfm]} components={components} urlTransform={safeUrl}>
        {children}
      </ReactMarkdown>
    </div>
  );
}
