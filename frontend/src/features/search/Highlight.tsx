import type { Segment } from './api';

/** Текст с подсвеченными совпадениями. Куски приходят данными, HTML с сервера не вставляется. */
export function Highlight({ segments }: { segments: Segment[] }) {
  return (
    <>
      {segments.map((s, i) =>
        s.hl ? (
          <mark key={i} className="es-mark">
            {s.text}
          </mark>
        ) : (
          <span key={i}>{s.text}</span>
        ),
      )}
    </>
  );
}
