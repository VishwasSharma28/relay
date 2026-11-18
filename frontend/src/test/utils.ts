import { vi } from 'vitest';

export function jsonResponse(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } });
}

export function setCookie(value: string) {
  document.cookie = value;
}

export function clearCookies() {
  for (const c of document.cookie.split(';')) {
    const name = c.split('=')[0].trim();
    if (name) document.cookie = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`;
  }
}

export type Route = (url: string, init: RequestInit) => Response | undefined;

/** Installs a fetch mock dispatching to `routes`; unmatched requests fail loudly. */
export function mockFetch(routes: Route) {
  const fn = vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = typeof input === 'string' ? input : input.toString();
    const res = routes(url, init);
    if (!res) throw new Error(`Unmocked fetch ${init.method ?? 'GET'} ${url}`);
    return res;
  });
  vi.stubGlobal('fetch', fn);
  return fn;
}
