import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';
import { Session } from './auth/session';

function token(roles: string[]): string {
  const payload = btoa(JSON.stringify({ sub: 'u-1', roles })).replace(/=+$/, '');
  return `h.${payload}.s`;
}

const json = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers });

function backend(login: () => Response, roles = ['USER']) {
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (url) => {
    if (url === '/api/auth/login') return login();
    if (url === '/api/auth/refresh') return json(200, { accessToken: token(roles), refreshToken: 'rt_2' });
    if (url === '/api/auth/logout') return new Response(null, { status: 204 });
    return json(404, { code: 'NOT_FOUND' });
  });
}

async function signIn(user: ReturnType<typeof userEvent.setup>, email: string, password: string) {
  await user.type(screen.getByLabelText('Email'), email);
  await user.type(screen.getByLabelText('Password'), password);
  await user.click(screen.getByRole('button', { name: 'Sign in' }));
}

describe('App', () => {
  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
    window.history.replaceState(null, '', '/');
  });

  it('signs in, celebrates and opens the wallet', async () => {
    backend(() => json(200, { accessToken: token(['USER']), refreshToken: 'rt_1' }));
    const user = userEvent.setup();
    render(<App session={new Session()} />);
    expect(await screen.findByRole('heading', { name: 'Welcome back' })).toBeInTheDocument();
    await waitFor(() => expect(window.location.pathname).toBe('/sign-in'));

    await signIn(user, 'ana@example.com', 'Finova2026!');

    expect(screen.getByRole('img', { name: 'The assistant celebrates' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Hi, Ana!' })).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: 'Available balance' }, { timeout: 2000 })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/wallet');
    expect(screen.queryByRole('navigation', { name: 'Sections' })).toBeNull();
  });

  it('the robot looks at the email, covers its eyes for the password and peeks when it is shown', async () => {
    backend(() => json(200, {}));
    const user = userEvent.setup();
    render(<App session={new Session()} />);

    await user.click(await screen.findByLabelText('Email'));
    expect(screen.getByRole('img', { name: 'The assistant is watching you' })).toBeInTheDocument();

    await user.click(screen.getByLabelText('Password'));
    expect(screen.getByRole('img', { name: 'The assistant covers its eyes' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'I am not looking' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Show password' }));
    expect(screen.getByRole('img', { name: 'The assistant peeks with one eye' })).toBeInTheDocument();
    expect(screen.getByLabelText('Password')).toHaveAttribute('type', 'text');
    await user.click(screen.getByRole('button', { name: 'Hide password' }));
    expect(screen.getByLabelText('Password')).toHaveAttribute('type', 'password');
  });

  it('wrong credentials: the robot shakes its head and the message is in plain words', async () => {
    backend(() => json(401, { code: 'INVALID_CREDENTIALS', message: 'Invalid email or password.' }));
    const user = userEvent.setup();
    render(<App session={new Session()} />);
    await screen.findByLabelText('Email');

    await signIn(user, 'ana@example.com', 'wrong');

    expect(await screen.findByRole('alert')).toHaveTextContent('Wrong email or password');
    expect(screen.getByRole('alert')).not.toHaveTextContent('INVALID_CREDENTIALS');
    expect(screen.getByRole('img', { name: 'The assistant shakes its head' })).toBeInTheDocument();

    // Typing again calms it down.
    await user.type(screen.getByLabelText('Password'), 'x');
    expect(screen.getByRole('img', { name: 'The assistant covers its eyes' })).toBeInTheDocument();
  });

  it('a lockout, a rate limit and an unreachable server each say what to do', async () => {
    let answer = json(423, { code: 'ACCOUNT_LOCKED' });
    backend(() => answer);
    const user = userEvent.setup();
    render(<App session={new Session()} />);
    await screen.findByLabelText('Email');

    await signIn(user, 'ana@example.com', 'x');
    expect(await screen.findByRole('alert')).toHaveTextContent('15 minutes');

    answer = json(429, { code: 'RATE_LIMIT_EXCEEDED' }, { 'Retry-After': '30' });
    await user.click(screen.getByRole('button', { name: 'Sign in' }));
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Wait 30 seconds'));

    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('offline'));
    await user.click(screen.getByRole('button', { name: 'Sign in' }));
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('We could not connect'));
  });

  it('an example account fills the form; an auditor lands on Activity and can switch tabs', async () => {
    backend(() => json(200, { accessToken: token(['USER', 'AUDITOR']), refreshToken: 'rt_1' }), ['USER', 'AUDITOR']);
    const user = userEvent.setup();
    render(<App session={new Session()} />);

    await user.click(await screen.findByRole('button', { name: 'Auditor' }));
    expect(screen.getByLabelText('Email')).toHaveValue('auditor@example.com');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('heading', { name: 'Transfer activity' }, { timeout: 2000 })).toBeInTheDocument();
    await user.click(screen.getByRole('link', { name: 'Wallet' }));
    expect(screen.getByRole('heading', { name: 'Available balance' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Wallet' })).toHaveAttribute('aria-current', 'page');
    await user.click(screen.getByRole('link', { name: 'Activity' }));
    expect(window.location.pathname).toBe('/activity');
  });

  it('a reload keeps the session; a customer cannot open Activity; logging out goes back to sign-in', async () => {
    sessionStorage.setItem('finova.refreshToken', 'rt_1');
    sessionStorage.setItem('finova.email', 'ana@example.com');
    window.history.replaceState(null, '', '/activity');
    const fetch = backend(() => json(500, {}));
    const user = userEvent.setup();
    render(<App session={new Session()} />);

    expect(screen.getByRole('status', { name: 'Loading' })).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: 'Available balance' })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/wallet');
    expect(screen.getByText('ana@example.com')).toBeInTheDocument();
    expect(fetch.mock.calls.map(([url]) => String(url)).filter((url) => url.startsWith('/api/audit'))).toEqual([]);

    await user.click(screen.getByRole('button', { name: 'Sign out' }));

    expect(await screen.findByRole('heading', { name: 'Welcome back' })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/sign-in');
    expect(fetch.mock.calls.some(([url]) => url === '/api/auth/logout')).toBe(true);
    expect(sessionStorage.getItem('finova.refreshToken')).toBeNull();
  });

  it('the back button follows the history', async () => {
    sessionStorage.setItem('finova.refreshToken', 'rt_1');
    backend(() => json(500, {}), ['USER', 'AUDITOR']);
    const user = userEvent.setup();
    render(<App session={new Session()} />);
    await user.click(await screen.findByRole('link', { name: 'Wallet' }));
    expect(window.location.pathname).toBe('/wallet');

    await act(async () => {
      window.history.back();
      await new Promise((resolve) => setTimeout(resolve, 50));
    });

    expect(window.location.pathname).toBe('/activity');
    expect(screen.getByRole('heading', { name: 'Transfer activity' })).toBeInTheDocument();
  });
});
