import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiFailure } from '../api/client';
import { Session } from './session';

const ANA = '7f3c2b1a-0d4e-4f5a-8b6c-9d0e1f2a3b4c';

function token(claims: Record<string, unknown>): string {
  const payload = btoa(JSON.stringify(claims)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
  return `h.${payload}.s`;
}

const pair = (n: number, roles = ['USER']) =>
  ({ accessToken: token({ sub: ANA, roles, n }), refreshToken: `rt_${n}`, tokenType: 'Bearer' });

type Handler = (url: string, init: RequestInit) => Response | Promise<Response>;

function server(handler: Handler) {
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (url, init) => handler(String(url), init ?? {}));
}

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status });
const error = (status: number, code: string) => json(status, { code, message: code });
const auth = (init: RequestInit) => (init.headers as Record<string, string>).Authorization;

describe('Session', () => {
  let tab: Storage;
  let browser: Storage;
  let session: Session;

  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
    tab = sessionStorage;
    browser = localStorage;
    session = new Session(tab, browser);
  });

  it('signs in with a stable device id and keeps only the refresh token in the tab', async () => {
    const fetch = server(() => json(200, pair(1, ['USER', 'AUDITOR'])));
    const listener = vi.fn();
    session.subscribe(listener);

    const user = await session.login(' Ana@Example.com ', 'Finova2026!');

    expect(user).toEqual({ userId: ANA, email: 'ana@example.com', roles: ['USER', 'AUDITOR'] });
    const body = JSON.parse(String(fetch.mock.calls[0][1]!.body));
    expect(body).toEqual({ email: 'Ana@Example.com', password: 'Finova2026!', deviceId: expect.stringMatching(/^web-/) });
    expect(session.deviceId()).toBe(body.deviceId);
    expect(tab.getItem('finova.refreshToken')).toBe('rt_1');
    expect(JSON.stringify({ ...tab, ...browser })).not.toContain(pair(1).accessToken);
    // The sign-in screen decides when the app sees the user.
    expect(listener).not.toHaveBeenCalled();
    session.publish();
    expect(listener).toHaveBeenCalledWith(user);
  });

  it('a failed sign-in throws the API failure', async () => {
    server(() => error(401, 'INVALID_CREDENTIALS'));

    await expect(session.login('ana@example.com', 'x')).rejects.toBeInstanceOf(ApiFailure);
    expect(session.user).toBeNull();
  });

  it('restores the session after a reload with the refresh token', async () => {
    tab.setItem('finova.refreshToken', 'rt_0');
    tab.setItem('finova.email', 'ana@example.com');
    const fetch = server(() => json(200, pair(1)));

    expect(await session.restore()).toMatchObject({ userId: ANA, email: 'ana@example.com' });
    expect(fetch.mock.calls[0][0]).toBe('/api/auth/refresh');
    expect(tab.getItem('finova.refreshToken')).toBe('rt_1');
  });

  it('nothing to restore without a refresh token', async () => {
    const fetch = server(() => json(200, pair(1)));

    expect(await session.restore()).toBeNull();
    expect(await session.refresh()).toBe(false);
    expect(fetch).not.toHaveBeenCalled();
  });

  it('calls as the user and refreshes once when the access token expired', async () => {
    let balanceCalls = 0;
    const fetch = server((url, init) => {
      if (url === '/api/auth/login') return json(200, pair(1));
      if (url === '/api/auth/refresh') return json(200, pair(2));
      balanceCalls++;
      return auth(init) === `Bearer ${pair(2).accessToken}` ? json(200, { available: '1.00' }) : error(401, 'UNAUTHORIZED');
    });
    await session.login('ana@example.com', 'p');

    const [a, b] = await Promise.all([session.call('/wallets/me/balance'), session.call('/wallets/me/balance')]);

    expect(a.body).toEqual({ available: '1.00' });
    expect(b.body).toEqual({ available: '1.00' });
    // Both 401s shared one refresh: a second one would reuse rt_1 and Identity would revoke the session.
    expect(fetch.mock.calls.filter(([url]) => url === '/api/auth/refresh')).toHaveLength(1);
    expect(balanceCalls).toBe(4);
  });

  it('a refresh that Identity rejects signs the user out', async () => {
    server((url) => {
      if (url === '/api/auth/login') return json(200, pair(1));
      if (url === '/api/auth/refresh') return error(401, 'REFRESH_TOKEN_REUSED');
      return error(401, 'UNAUTHORIZED');
    });
    await session.login('ana@example.com', 'p');
    const listener = vi.fn();
    session.subscribe(listener);

    await expect(session.call('/wallets/me/balance')).rejects.toBeInstanceOf(ApiFailure);
    expect(session.user).toBeNull();
    expect(tab.getItem('finova.refreshToken')).toBeNull();
    expect(listener).toHaveBeenCalledWith(null);
  });

  it('a refresh that cannot reach the server keeps the tokens for later', async () => {
    tab.setItem('finova.refreshToken', 'rt_0');
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('offline'));

    expect(await session.refresh()).toBe(false);
    expect(tab.getItem('finova.refreshToken')).toBe('rt_0');

    vi.spyOn(globalThis, 'fetch').mockResolvedValue(error(503, 'SECURITY_STORE_UNAVAILABLE'));
    expect(await session.refresh()).toBe(false);
    expect(tab.getItem('finova.refreshToken')).toBe('rt_0');
  });

  it('a revoked token signs out; other errors pass through untouched', async () => {
    let answer = error(403, 'FORBIDDEN_ROLE');
    server((url) => (url === '/api/auth/login' ? json(200, pair(1)) : answer));
    await session.login('ana@example.com', 'p');

    await expect(session.call('/audit/records')).rejects.toMatchObject({ error: { code: 'FORBIDDEN_ROLE' } });
    expect(session.user).not.toBeNull();

    answer = error(401, 'TOKEN_REVOKED');
    await expect(session.call('/audit/records')).rejects.toMatchObject({ error: { code: 'TOKEN_REVOKED' } });
    expect(session.user).toBeNull();

    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new Error('boom'));
    await expect(session.call('/x')).rejects.toBeInstanceOf(ApiFailure);
  });

  it('logs out on the server and locally, even when the server fails', async () => {
    const fetch = server((url) => (url === '/api/auth/login' ? json(200, pair(1)) : new Response(null, { status: 204 })));
    await session.login('ana@example.com', 'p');

    await session.logout();

    const [url, init] = fetch.mock.calls[1];
    expect(url).toBe('/api/auth/logout');
    expect(auth(init!)).toBe(`Bearer ${pair(1).accessToken}`);
    expect(session.user).toBeNull();

    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('offline'));
    await session.logout();
    expect(session.user).toBeNull();
  });

  it('a malformed token gives a user without claims; unsubscribe stops the updates', async () => {
    server(() => json(200, { accessToken: 'not-a-jwt', refreshToken: 'rt_9' }));
    const listener = vi.fn();
    session.subscribe(listener)();

    expect(await session.login('ana@example.com', 'p')).toEqual({ userId: '', email: 'ana@example.com', roles: [] });
    session.publish();
    expect(listener).not.toHaveBeenCalled();
  });
});
