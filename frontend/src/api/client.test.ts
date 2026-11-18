import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, api, errorText, resetCsrf } from './client';
import { records } from './endpoints';
import { clearCookies, jsonResponse, mockFetch, setCookie } from '../test/utils';

describe('api client', () => {
  beforeEach(() => { resetCsrf(); clearCookies(); });
  afterEach(() => vi.unstubAllGlobals());

  it('unwraps the success envelope', async () => {
    mockFetch(() => jsonResponse({ success: true, data: [{ id: 'p1', name: 'One' }] }));
    await expect(api.get('/api/projects')).resolves.toEqual([{ id: 'p1', name: 'One' }]);
  });

  it('sends same-origin credentials and serializes query params', async () => {
    const f = mockFetch(() => jsonResponse({ success: true, data: { ok: 1 } }));
    await api.get('/api/x', { a: 1, b: undefined, c: 'z y' });
    const [url, init] = f.mock.calls[0];
    expect(url).toBe('/api/x?a=1&c=z+y');
    expect(init?.credentials).toBe('same-origin');
  });

  it('fetches the CSRF token before a POST and sends it as X-CSRF-TOKEN', async () => {
    const f = mockFetch((url) => {
      if (url === '/api/auth/csrf') {
        setCookie('XSRF-TOKEN=tok-123; path=/');
        return jsonResponse({ success: true, data: { token: 'tok-123' } });
      }
      return jsonResponse({ success: true, data: { id: 'new' } }, 201);
    });
    await expect(api.post('/api/projects', { name: 'n' })).resolves.toEqual({ id: 'new' });
    expect(f.mock.calls[0][0]).toBe('/api/auth/csrf');
    const init = f.mock.calls[1][1]!;
    expect(init.method).toBe('POST');
    expect((init.headers as Record<string, string>)['X-CSRF-TOKEN']).toBe('tok-123');
    expect((init.headers as Record<string, string>)['Content-Type']).toBe('application/json');
    expect(init.body).toBe(JSON.stringify({ name: 'n' }));
  });

  it('reuses an existing XSRF-TOKEN cookie without calling /csrf', async () => {
    setCookie('XSRF-TOKEN=existing; path=/');
    const f = mockFetch(() => jsonResponse({ success: true, data: null }));
    await api.del('/api/projects/1');
    expect(f).toHaveBeenCalledTimes(1);
    expect((f.mock.calls[0][1]!.headers as Record<string, string>)['X-CSRF-TOKEN']).toBe('existing');
  });

  it('does not send a CSRF header on GET', async () => {
    const f = mockFetch(() => jsonResponse({ success: true, data: 1 }));
    await api.get('/api/auth/me');
    expect((f.mock.calls[0][1]!.headers as Record<string, string>)['X-CSRF-TOKEN']).toBeUndefined();
  });

  it('maps an error envelope to ApiError with code and message', async () => {
    mockFetch(() => jsonResponse({ success: false, error: { code: 'NOT_DEPLOYED', message: 'No deployment', details: { env: 'production' } } }, 409));
    const err = await api.get('/api/x').catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err).toMatchObject({ status: 409, code: 'NOT_DEPLOYED', message: 'No deployment', details: { env: 'production' } });
    expect(errorText(err)).toBe('NOT_DEPLOYED: No deployment');
  });

  it('maps a non-JSON failure to an HTTP_<status> code', async () => {
    mockFetch(() => new Response('boom', { status: 502, statusText: 'Bad Gateway' }));
    await expect(api.get('/api/x')).rejects.toMatchObject({ status: 502, code: 'HTTP_502', message: 'Bad Gateway' });
  });

  it('maps network failures to NETWORK_ERROR', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch'); }));
    await expect(api.get('/api/x')).rejects.toMatchObject({ code: 'NETWORK_ERROR' });
  });

  it('treats 204 No Content as success', async () => {
    setCookie('XSRF-TOKEN=t; path=/');
    mockFetch(() => new Response(null, { status: 204 }));
    await expect(api.del('/api/x')).resolves.toBeNull();
  });

  it('sends record patches as merge-patch with If-Match', async () => {
    setCookie('XSRF-TOKEN=t; path=/');
    const f = mockFetch(() => jsonResponse({ success: true, data: { id: 'r1' } }));
    await records.patch('p', 'sandbox', 'todos', 'r1', '"v2"', { done: true });
    const [url, init] = f.mock.calls[0];
    expect(url).toBe('/api/projects/p/environments/sandbox/resources/todos/records/r1');
    const h = init!.headers as Record<string, string>;
    expect(init!.method).toBe('PATCH');
    expect(h['Content-Type']).toBe('application/merge-patch+json');
    expect(h['If-Match']).toBe('"v2"');
  });

  it('encodes record filters as filter[field]=<json>', async () => {
    const f = mockFetch(() => jsonResponse({ success: true, data: { data: [], pagination: { limit: 10, nextCursor: null } } }));
    await records.list('p', 'production', 'todos', { limit: 10, cursor: 'c1', filters: { done: 'false' } });
    const url = new URL(f.mock.calls[0][0] as string, 'http://x');
    expect(url.searchParams.get('filter[done]')).toBe('false');
    expect(url.searchParams.get('cursor')).toBe('c1');
    expect(url.searchParams.get('limit')).toBe('10');
  });
});
