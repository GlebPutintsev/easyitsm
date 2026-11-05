import { Typography } from '@mantine/core';
import ReactMarkdown, { type Components } from 'react-markdown';
import remarkGfm from 'remark-gfm';

/** Разрешённые схемы ссылок и изображений: никаких javascript: и data: из текста статьи. */
const SAFE_URL = /^(https?:|mailto:|tel:|\/|#)/i;

function safeUrl(url: string): string {
  return SAFE_URL.test(url.trim()) ? url : '';
}

const components: Components = {
  a: ({ href, children, ...rest }) => {
    const external = !!href && /^https?:/i.test(href);
    return (
      <a href={href} {...rest} {...(external ? { target: '_blank', rel: 'noopener noreferrer nofollow' } : {})}>
        {children}
      </a>
    );
  },
  img: ({ src, alt }) => (
    <img src={typeof src === 'string' ? src : undefined} alt={alt ?? ''} loading="lazy" referrerPolicy="no-referrer" style={{ maxWidth: '100%' }} />
  ),
  table: ({ children }) => (
    <div style={{ overflowX: 'auto' }}>
      <table>{children}</table>
    </div>
  ),
};

/**
 * Текст статьи (KB-01): Markdown с таблицами, кодом, изображениями и ссылками (GFM).
 * HTML в тексте не исполняется: react-markdown без rehype-raw выводит его как текст.
 */
export function ArticleMarkdown({ children }: { children: string }) {
  return (
    <Typography>
      <ReactMarkdown remarkPlugins={[remarkGfm]} components={components} urlTransform={safeUrl}>
        {children}
      </ReactMarkdown>
    </Typography>
  );
}
