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
}

/** Вход для разработки: заголовки X-Dev-*, работают только в профиле dev на сервере. */
function devAuthHeaders(): Record<string, string> {
  if (!import.meta.env.DEV) return {};
  const user = import.meta.env.VITE_DEV_USER as string | undefined;
  const tenant = import.meta.env.VITE_DEV_TENANT as string | undefined;
  const roles = import.meta.env.VITE_DEV_ROLES as string | undefined;
  if (!user || !tenant) return {};
  return { 'X-Dev-User': user, 'X-Dev-Tenant': tenant, ...(roles ? { 'X-Dev-Roles': roles } : {}) };
}

export async function request<T>(method: Method, path: string, options: RequestOptions = {}): Promise<T> {
  const url = new URL(`/api/v1${path}`, window.location.origin);
  for (const [key, value] of Object.entries(options.query ?? {})) {
    if (value !== undefined) url.searchParams.set(key, String(value));
  }
  const headers: Record<string, string> = { Accept: 'application/json', ...devAuthHeaders() };
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
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
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}

export const api = {
  get: <T>(path: string, options?: RequestOptions) => request<T>('GET', path, options),
  post: <T>(path: string, body: unknown, options?: RequestOptions) => request<T>('POST', path, { ...options, body }),
  patch: <T>(path: string, body: unknown, options?: RequestOptions) => request<T>('PATCH', path, { ...options, body }),
  put: <T>(path: string, body?: unknown, options?: RequestOptions) => request<T>('PUT', path, { ...options, body }),
  delete: <T = void>(path: string, options?: RequestOptions) => request<T>('DELETE', path, options),
};

/** ETag записи для If-Match: сервер отдаёт версию как "N" (API-05). */
export function etagOf(version: number): string {
  return `"${version}"`;
}

/** Ключ идемпотентности на одно действие пользователя (API-04). */
export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}
