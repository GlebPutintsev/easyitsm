import { useEffect, useRef } from 'react';

/**
 * Черновик формы в браузере (UI-05): текст не теряется при сбое связи и перезагрузке.
 * Хранилище может быть недоступно (приватный режим) — тогда форма просто работает без черновика.
 */
const PREFIX = 'easyitsm.draft.';

export function loadDraft<T>(key: string): T | null {
  try {
    const raw = localStorage.getItem(PREFIX + key);
    return raw ? (JSON.parse(raw) as T) : null;
  } catch {
    return null;
  }
}

export function saveDraft(key: string, value: unknown): boolean {
  try {
    localStorage.setItem(PREFIX + key, JSON.stringify(value));
    return true;
  } catch {
    return false;
  }
}

export function clearDraft(key: string) {
  try {
    localStorage.removeItem(PREFIX + key);
  } catch {
    // нечего очищать
  }
}

/** Автосохранение с задержкой, пока пользователь печатает. */
export function useAutosaveDraft(key: string, value: unknown, enabled: boolean) {
  const first = useRef(true);
  useEffect(() => {
    if (first.current) {
      first.current = false;
      return;
    }
    if (!enabled) return;
    const id = window.setTimeout(() => saveDraft(key, value), 600);
    return () => window.clearTimeout(id);
  }, [key, value, enabled]);
}
