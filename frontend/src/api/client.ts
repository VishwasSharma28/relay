export interface ApiErrorBody {
  code: string;
  message: string;
  details?: Record<string, string> | null;
}

export interface Envelope<T> {
  success: boolean;
  data?: T;
  error?: ApiErrorBody | null;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly details?: Record<string, string> | null;

  constructor(status: number, code: string, message: string, details?: Record<string, string> | null) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.details = details;
  }
}

export interface RequestOptions {
  method?: string;
  body?: unknown;
  headers?: Record<string, string>;
  contentType?: string;
  query?: Record<string, string | number | boolean | undefined | null>;
}

export interface ApiResult<T> {
  data: T;
  headers: Headers;
}

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);
let csrfToken: string | null = null;
let csrfPromise: Promise<string> | null = null;

export function readCookie(name: string): string | null {
  if (typeof document === 'undefined') return null;
  for (const part of document.cookie.split(';')) {
    const [k, ...rest] = part.trim().split('=');
    if (k === name) return decodeURIComponent(rest.join('='));
  }
  return null;
}

/** Forget the cached token (after login/logout the server may rotate it). */
export function resetCsrf(): void {
  csrfToken = null;
  csrfPromise = null;
}

/** Ensure an XSRF-TOKEN cookie exists, returning its value. */
export async function ensureCsrf(force = false): Promise<string> {
  if (!force) {
    const cookie = readCookie('XSRF-TOKEN');
    if (cookie) return (csrfToken = cookie);
    if (csrfToken) return csrfToken;
  }
  if (!csrfPromise) {
    csrfPromise = (async () => {
      const res = await fetch('/api/auth/csrf', { credentials: 'same-origin', headers: { Accept: 'application/json' } });
      let bodyToken: string | undefined;
      try {
        const env = (await res.json()) as Envelope<{ token?: string }>;
        bodyToken = env.data?.token;
      } catch {
        /* ignore body parse errors */
      }
      const token = readCookie('XSRF-TOKEN') ?? bodyToken;
      if (!token) throw new ApiError(res.status, 'CSRF_UNAVAILABLE', 'Could not obtain a CSRF token');
      csrfToken = token;
      return token;
    })().finally(() => {
      csrfPromise = null;
    });
  }
  return csrfPromise;
}

export function buildQuery(query?: RequestOptions['query']): string {
  if (!query) return '';
  const params = new URLSearchParams();
  for (const [k, v] of Object.entries(query)) {
    if (v !== undefined && v !== null && v !== '') params.append(k, String(v));
  }
  const s = params.toString();
  return s ? `?${s}` : '';
}

async function parseEnvelope<T>(res: Response): Promise<Envelope<T> | null> {
  if (res.status === 204) return null;
  const text = await res.text();
  if (!text) return null;
  try {
    return JSON.parse(text) as Envelope<T>;
  } catch {
    return null;
  }
}

export async function requestWithHeaders<T>(path: string, opts: RequestOptions = {}): Promise<ApiResult<T>> {
  const method = (opts.method ?? 'GET').toUpperCase();
  const headers: Record<string, string> = { Accept: 'application/json', ...opts.headers };
  let body: BodyInit | undefined;
  if (opts.body !== undefined) {
    headers['Content-Type'] = opts.contentType ?? 'application/json';
    body = JSON.stringify(opts.body);
  }
  if (!SAFE_METHODS.has(method)) {
    headers['X-CSRF-TOKEN'] = await ensureCsrf();
  }
  let res: Response;
  try {
    res = await fetch(path + buildQuery(opts.query), { method, headers, body, credentials: 'same-origin' });
  } catch (e) {
    throw new ApiError(0, 'NETWORK_ERROR', e instanceof Error ? e.message : 'Network request failed');
  }
  const env = await parseEnvelope<T>(res);
  if (!res.ok || (env && env.success === false)) {
    if (res.status === 403 && !SAFE_METHODS.has(method)) resetCsrf();
    const err = env?.error;
    throw new ApiError(
      res.status,
      err?.code ?? `HTTP_${res.status}`,
      err?.message ?? (res.statusText || `Request failed with status ${res.status}`),
      err?.details,
    );
  }
  // Tolerate non-enveloped JSON (e.g. a raw export bundle): return the body itself.
  if (env && typeof env === 'object' && !('success' in env)) return { data: env as unknown as T, headers: res.headers };
  return { data: (env?.data ?? null) as T, headers: res.headers };
}

export async function request<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  return (await requestWithHeaders<T>(path, opts)).data;
}

export const api = {
  get: <T>(path: string, query?: RequestOptions['query']) => request<T>(path, { query }),
  post: <T>(path: string, body?: unknown) => request<T>(path, { method: 'POST', body: body ?? {} }),
  put: <T>(path: string, body?: unknown) => request<T>(path, { method: 'PUT', body }),
  patch: <T>(path: string, body?: unknown) => request<T>(path, { method: 'PATCH', body }),
  del: <T>(path: string, headers?: Record<string, string>) => request<T>(path, { method: 'DELETE', headers }),
};

export function errorText(e: unknown): string {
  if (e instanceof ApiError) return `${e.code}: ${e.message}`;
  if (e instanceof Error) return e.message;
  return String(e);
}
