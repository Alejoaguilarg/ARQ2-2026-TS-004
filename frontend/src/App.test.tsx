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
  await user.type(screen.getByLabelText('Correo'), email);
  await user.type(screen.getByLabelText('Contraseña'), password);
  await user.click(screen.getByRole('button', { name: 'Ingresar' }));
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
    expect(await screen.findByRole('heading', { name: 'Hola de nuevo' })).toBeInTheDocument();
    await waitFor(() => expect(window.location.pathname).toBe('/ingresar'));

    await signIn(user, 'ana@example.com', 'Finova2026!');

    expect(screen.getByRole('img', { name: 'El asistente celebra' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '¡Hola, Ana!' })).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: 'Saldo disponible' }, { timeout: 2000 })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/billetera');
    expect(screen.queryByRole('navigation', { name: 'Secciones' })).toBeNull();
  });

  it('the robot looks at the email, covers its eyes for the password and peeks when it is shown', async () => {
    backend(() => json(200, {}));
    const user = userEvent.setup();
    render(<App session={new Session()} />);

    await user.click(await screen.findByLabelText('Correo'));
    expect(screen.getByRole('img', { name: 'El asistente te mira' })).toBeInTheDocument();

    await user.click(screen.getByLabelText('Contraseña'));
    expect(screen.getByRole('img', { name: 'El asistente se tapa los ojos' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'No estoy mirando' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Mostrar contraseña' }));
    expect(screen.getByRole('img', { name: 'El asistente espía con un ojo' })).toBeInTheDocument();
    expect(screen.getByLabelText('Contraseña')).toHaveAttribute('type', 'text');
    await user.click(screen.getByRole('button', { name: 'Ocultar contraseña' }));
    expect(screen.getByLabelText('Contraseña')).toHaveAttribute('type', 'password');
  });

  it('wrong credentials: the robot shakes its head and the message is in plain words', async () => {
    backend(() => json(401, { code: 'INVALID_CREDENTIALS', message: 'Invalid email or password.' }));
    const user = userEvent.setup();
    render(<App session={new Session()} />);
    await screen.findByLabelText('Correo');

    await signIn(user, 'ana@example.com', 'wrong');

    expect(await screen.findByRole('alert')).toHaveTextContent('Correo o contraseña incorrectos');
    expect(screen.getByRole('alert')).not.toHaveTextContent('INVALID_CREDENTIALS');
    expect(screen.getByRole('img', { name: 'El asistente niega con la cabeza' })).toBeInTheDocument();

    // Typing again calms it down.
    await user.type(screen.getByLabelText('Contraseña'), 'x');
    expect(screen.getByRole('img', { name: 'El asistente se tapa los ojos' })).toBeInTheDocument();
  });

  it('a lockout, a rate limit and an unreachable server each say what to do', async () => {
    let answer = json(423, { code: 'ACCOUNT_LOCKED' });
    backend(() => answer);
    const user = userEvent.setup();
    render(<App session={new Session()} />);
    await screen.findByLabelText('Correo');

    await signIn(user, 'ana@example.com', 'x');
    expect(await screen.findByRole('alert')).toHaveTextContent('15 minutos');

    answer = json(429, { code: 'RATE_LIMIT_EXCEEDED' }, { 'Retry-After': '30' });
    await user.click(screen.getByRole('button', { name: 'Ingresar' }));
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Espera 30 segundos'));

    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('offline'));
    await user.click(screen.getByRole('button', { name: 'Ingresar' }));
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('No pudimos conectarnos'));
  });

  it('an example account fills the form; an auditor lands on Actividad and can switch tabs', async () => {
    backend(() => json(200, { accessToken: token(['USER', 'AUDITOR']), refreshToken: 'rt_1' }), ['USER', 'AUDITOR']);
    const user = userEvent.setup();
    render(<App session={new Session()} />);

    await user.click(await screen.findByRole('button', { name: 'Auditor' }));
    expect(screen.getByLabelText('Correo')).toHaveValue('auditor@example.com');
    await user.click(screen.getByRole('button', { name: 'Ingresar' }));

    expect(await screen.findByRole('heading', { name: 'Actividad de envíos' }, { timeout: 2000 })).toBeInTheDocument();
    await user.click(screen.getByRole('link', { name: 'Billetera' }));
    expect(screen.getByRole('heading', { name: 'Saldo disponible' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Billetera' })).toHaveAttribute('aria-current', 'page');
    await user.click(screen.getByRole('link', { name: 'Actividad' }));
    expect(window.location.pathname).toBe('/actividad');
  });

  it('a reload keeps the session; a customer cannot open Actividad; logging out goes back to sign-in', async () => {
    sessionStorage.setItem('finova.refreshToken', 'rt_1');
    sessionStorage.setItem('finova.email', 'ana@example.com');
    window.history.replaceState(null, '', '/actividad');
    const fetch = backend(() => json(500, {}));
    const user = userEvent.setup();
    render(<App session={new Session()} />);

    expect(screen.getByRole('status', { name: 'Cargando' })).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: 'Saldo disponible' })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/billetera');
    expect(screen.getByText('ana@example.com')).toBeInTheDocument();
    expect(fetch.mock.calls.map(([url]) => String(url)).filter((url) => url.startsWith('/api/audit'))).toEqual([]);

    await user.click(screen.getByRole('button', { name: 'Cerrar sesión' }));

    expect(await screen.findByRole('heading', { name: 'Hola de nuevo' })).toBeInTheDocument();
    expect(window.location.pathname).toBe('/ingresar');
    expect(fetch.mock.calls.some(([url]) => url === '/api/auth/logout')).toBe(true);
    expect(sessionStorage.getItem('finova.refreshToken')).toBeNull();
  });

  it('the back button follows the history', async () => {
    sessionStorage.setItem('finova.refreshToken', 'rt_1');
    backend(() => json(500, {}), ['USER', 'AUDITOR']);
    const user = userEvent.setup();
    render(<App session={new Session()} />);
    await user.click(await screen.findByRole('link', { name: 'Billetera' }));
    expect(window.location.pathname).toBe('/billetera');

    await act(async () => {
      window.history.back();
      await new Promise((resolve) => setTimeout(resolve, 50));
    });

    expect(window.location.pathname).toBe('/actividad');
    expect(screen.getByRole('heading', { name: 'Actividad de envíos' })).toBeInTheDocument();
  });
});
