/**
 * HTTP-клиент API /api/v1. Ошибки приходят в формате RFC 9457 и превращаются в ApiError
 * с кодом трассировки, который показывается пользователю (UI-06).
 *
 * Когда появится docs/api/openapi.yaml, типы запросов генерируются командой `npm run api:generate`,
 * а этот модуль останется общим местом для заголовков, ошибок и идемпотентности.
 */

export interface Problem {
  type?: string;
  title?: string;
  status: number;
  detail?: string;
  code?: string;
  trace_id?: string;
  /** Ошибки по полям: ключ поля → код ошибки (UI-06) */
  errors?: Record<string, string>;
  /** Дополнительные поля ошибки, например список найденных секретов */
  [extension: string]: unknown;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly traceId?: string;
  /** Полный ответ RFC 9457: дополнительные поля вроде errors, field, findings */
  readonly problem: Problem;

  constructor(problem: Problem) {
    super(problem.detail ?? problem.title ?? `HTTP ${problem.status}`);
    this.status = problem.status;
    this.code = problem.code ?? 'unknown';
    this.traceId = problem.trace_id;
    this.problem = problem;
  }
}

export interface CursorPage<T> {
  items: T[];
  nextCursor: string | null;
}

type Method = 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE';

interface RequestOptions {
  body?: unknown;
  query?: Record<string, string | number | boolean | undefined>;
  /** ETag записи для If-Match при изменении (API-05) */
  ifMatch?: string;
  /** Ключ идемпотентности для создания (API-04). Генерируется один раз на действие пользователя. */
  idempotencyKey?: string;
  signal?: AbortSignal;
  /** Вернуть Response без разбора JSON: скачивание файлов (выгрузки отчётов). */
  raw?: boolean;
}

/**
 * Вход по куке сессии (модуль identity). Кука HttpOnly, браузер отправляет её сам.
 * Изменяющие запросы несут CSRF-токен: сервер кладёт его в куку XSRF-TOKEN, клиент возвращает в заголовке.
 */
export function csrfToken(): string | undefined {
  const match = document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='));
  return match ? decodeURIComponent(match.slice('XSRF-TOKEN='.length)) : undefined;
}

/**
 * Обработчики ответов 401. Модуль входа подписывается на них: при reauth_required показывает окно
 * повторной аутентификации (SEC-08) и повторяет запрос, при истёкшей сессии отправляет на экран входа.
 */
export interface AuthHandlers {
  /** Возвращает true, если пользователь подтвердил вход и запрос можно повторить. */
  reauthenticate?: () => Promise<boolean>;
  sessionExpired?: () => void;
}

const authHandlers: AuthHandlers = {};

export function setAuthHandlers(handlers: AuthHandlers) {
  Object.assign(authHandlers, handlers);
}

export async function request<T>(method: Method, path: string, options: RequestOptions = {}): Promise<T> {
  try {
    return await send<T>(method, path, options);
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      if (error.code === 'reauth_required' && authHandlers.reauthenticate && (await authHandlers.reauthenticate())) {
        return send<T>(method, path, options);
      }
      if (error.code !== 'reauth_required' && !path.startsWith('/auth/')) authHandlers.sessionExpired?.();
    }
    throw error;
  }
}

async function send<T>(method: Method, path: string, options: RequestOptions): Promise<T> {
  const url = new URL(`/api/v1${path}`, window.location.origin);
  for (const [key, value] of Object.entries(options.query ?? {})) {
    if (value !== undefined) url.searchParams.set(key, String(value));
  }
  const headers: Record<string, string> = { Accept: options.raw ? '*/*' : 'application/json' };
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  const csrf = method === 'GET' ? undefined : csrfToken();
  if (csrf) headers['X-XSRF-TOKEN'] = csrf;
  if (options.ifMatch) headers['If-Match'] = options.ifMatch;
  if (options.idempotencyKey) headers['Idempotency-Key'] = options.idempotencyKey;

  const response = await fetch(url, {
    method,
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    credentials: 'same-origin',
    signal: options.signal,
  });

  if (!response.ok) {
    let problem: Problem = { status: response.status };
    try {
      problem = { ...problem, ...((await response.json()) as Problem) };
    } catch {
      problem.trace_id = response.headers.get('X-Trace-Id') ?? undefined;
    }
    throw new ApiError(problem);
  }
  if (options.raw) return response as T;
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export const api = {
  get: <T>(path: string, options?: RequestOptions) => request<T>('GET', path, options),
  post: <T>(path: string, body: unknown, options?: RequestOptions) => request<T>('POST', path, { ...options, body }),
  patch: <T>(path: string, body: unknown, options?: RequestOptions) => request<T>('PATCH', path, { ...options, body }),
  put: <T>(path: string, body?: unknown, options?: RequestOptions) => request<T>('PUT', path, { ...options, body }),
  delete: <T = void>(path: string, options?: RequestOptions) => request<T>('DELETE', path, options),
};

/**
 * Скачать файл, который сервер отдаёт на POST (выгрузка отчёта). Повторная аутентификация (SEC-08) и ошибки
 * RFC 9457 обрабатываются как у остальных запросов. Имя файла берётся из Content-Disposition.
 */
export async function downloadFile(path: string, body: unknown, fallbackName: string): Promise<void> {
  const response = await request<Response>('POST', path, { body, raw: true });
  const blob = await response.blob();
  const disposition = response.headers.get('Content-Disposition') ?? '';
  const utf8 = /filename\*=UTF-8''([^;]+)/i.exec(disposition);
  const plain = /filename="?([^";]+)"?/i.exec(disposition);
  const name = utf8?.[1] ? decodeURIComponent(utf8[1]) : (plain?.[1] ?? fallbackName);
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** ETag записи для If-Match: сервер отдаёт версию как "N" (API-05). */
export function etagOf(version: number): string {
  return `"${version}"`;
}

/** Ключ идемпотентности на одно действие пользователя (API-04). */
export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}
