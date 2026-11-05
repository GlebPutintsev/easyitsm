import { useEffect, useState } from 'react';

/** Текущее время, обновляется раз в интервал: таймеры SLA тикают без перерисовки всего списка по секундам. */
export function useNow(intervalMs = 30_000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(id);
  }, [intervalMs]);
  return now;
}
