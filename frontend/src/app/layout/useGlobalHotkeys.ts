import { useHotkeys } from '@mantine/hooks';
import { useRef } from 'react';
import { useNavigate } from 'react-router';

/** Глобальные клавиши (UI-11): «/» поиск, «g» затем «q» очередь. «n» и «?» добавят модули заявок и справки. */
export function useGlobalHotkeys() {
  const navigate = useNavigate();
  const gPressedAt = useRef(0);

  useHotkeys([
    ['/', () => document.getElementById('global-search')?.focus()],
    ['g', () => (gPressedAt.current = Date.now())],
    [
      'q',
      () => {
        if (Date.now() - gPressedAt.current < 1000) navigate('/agent/queues');
      },
    ],
  ]);
}
