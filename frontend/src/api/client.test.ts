import { describe, expect, it, vi } from 'vitest';
import { ApiFailure, request } from './client';

function reply(status: number, body: string, headers: Record<string, string> = {}) {
  return vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(body || null, { status, headers }));
}

async function failure(promise: Promise<unknown>): Promise<ApiFailure> {
  try {
    await promise;
  } catch (e) {
    return e as ApiFailure;
  }
  throw new Error('expected a failure');
}

describe('request', () => {
  it('sends JSON to /api and answers the parsed body', async () => {
    const fetch = reply(200, '{"userId":"u-1"}');

    const response = await request<{ userId: string }>('/users/lookup', {
      method: 'POST', body: { email: 'beto@example.com' }, headers: { Authorization: 'Bearer t' },
    });

    expect(response.body.userId).toBe('u-1');
    expect(fetch).toHaveBeenCalledWith('/api/users/lookup', {
      method: 'POST',
      headers: { Accept: 'application/json', Authorization: 'Bearer t', 'Content-Type': 'application/json' },
      body: '{"email":"beto@example.com"}',
    });
  });

  it('a GET has no body and an empty answer is undefined', async () => {
    const fetch = reply(204, '');

    const response = await request('/auth/logout');

    expect(response.status).toBe(204);
    expect(response.body).toBeUndefined();
    expect(fetch.mock.calls[0][1]).toMatchObject({ method: 'GET', body: undefined });
  });

  it('turns the spec error body into an ApiFailure', async () => {
    reply(429, '{"code":"RATE_LIMIT_EXCEEDED","message":"Too many requests.","correlationId":"c-1"}', { 'Retry-After': '42' });

    const e = await failure(request('/auth/login', { method: 'POST', body: {} }));

    expect(e).toBeInstanceOf(ApiFailure);
    expect(e.error).toEqual({
      status: 429, code: 'RATE_LIMIT_EXCEEDED', message: 'Too many requests.', correlationId: 'c-1', retryAfterSeconds: 42,
    });
  });

  it('an error without the spec body still has a code and the correlation header', async () => {
    reply(502, '<html>Bad Gateway</html>', { 'X-Correlation-Id': 'c-2' });
    expect((await failure(request('/wallets/me/balance'))).error).toMatchObject({
      status: 502, code: 'UPSTREAM_UNAVAILABLE', correlationId: 'c-2', retryAfterSeconds: undefined,
    });

    reply(500, '');
    expect((await failure(request('/wallets/me/balance'))).error).toMatchObject({ code: 'INTERNAL_ERROR' });
  });

  it('a network failure is NETWORK_ERROR', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('Failed to fetch'));

    expect((await failure(request('/wallets/me/balance'))).error).toMatchObject({ status: 0, code: 'NETWORK_ERROR' });
  });
});
