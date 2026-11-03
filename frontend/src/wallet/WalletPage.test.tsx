import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../auth/AuthContext';
import { Session } from '../auth/session';
import { WalletPage } from './WalletPage';

const ANA = '7f3c2b1a-0d4e-4f5a-8b6c-9d0e1f2a3b4c';
const BETO = '2b9d4e6f-1a3c-4e5b-9d7f-0a1b2c3d4e5f';
const TRANSFER = 'a1b2c3d4-e5f6-7890-abcd-ef1234567890';

const token = () => `h.${btoa(JSON.stringify({ sub: ANA, roles: ['USER'] })).replace(/=+$/, '')}.s`;
const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status });

type Handler = (init: RequestInit) => Response | Promise<Response>;

// A fake backend: each route answers from a queue, the last answer repeats.
function backend(routes: Record<string, Handler[]>) {
  const calls: { url: string; init: RequestInit }[] = [];
  const spy = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init = {}) => {
    const url = String(input);
    calls.push({ url, init });
    if (url === '/api/auth/refresh') return json(200, { accessToken: token(), refreshToken: 'rt_2' });
    const queue = routes[url];
    if (!queue) return json(404, { code: 'NOT_FOUND' });
    const handler = queue.length > 1 ? queue.shift()! : queue[0];
    return handler(init);
  });
  return { spy, calls: (url: string) => calls.filter((c) => c.url === url) };
}

const balance = (available: string) => () => json(200, { walletId: 'w', available, currency: 'COP', version: 1, asOf: '' });
const view = (status: string, failureReason: string | null = null) => () =>
  json(200, { transferId: TRANSFER, amount: '150000.00', currency: 'COP', status, failureReason, createdAt: '2026-11-03T15:00:00Z' });

async function open() {
  const session = new Session();
  await session.restore();
  render(<AuthProvider session={session}><WalletPage pollMs={10} /></AuthProvider>);
}

async function fillSend(user: ReturnType<typeof userEvent.setup>, email = 'beto@example.com') {
  await user.click(await screen.findByRole('button', { name: 'Enviar dinero' }));
  await user.type(screen.getByLabelText('Correo de quien recibe'), email);
  await user.type(screen.getByLabelText('Monto en pesos'), '150000');
  await user.type(screen.getByLabelText('Descripción (opcional)'), 'Cena');
  await user.click(screen.getByRole('button', { name: 'Continuar' }));
}

describe('WalletPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    sessionStorage.clear();
    localStorage.clear();
    sessionStorage.setItem('finova.refreshToken', 'rt_1');
    sessionStorage.setItem('finova.email', 'ana@example.com');
  });

  it('sends money with the PIN and follows it until Beto receives it', async () => {
    const api = backend({
      '/api/wallets/me/balance': [balance('500000.00'), balance('350000.00')],
      '/api/users/lookup': [() => json(200, { userId: BETO })],
      '/api/auth/financial-session': [() => json(401, { code: 'STEP_UP_FAILED' }), () => json(201, { financialSessionToken: 'fs_1' })],
      '/api/transfers': [() => json(202, { transferId: TRANSFER, status: 'PENDING', createdAt: '2026-11-03T15:00:00Z' })],
      [`/api/transfers/${TRANSFER}`]: [view('PENDING'), view('DEBITED'), view('COMPLETED')],
    });
    const user = userEvent.setup();
    await open();
    expect(await screen.findByText(/500\.000,00/)).toBeInTheDocument();
    expect(screen.getByText('Aún no has hecho envíos. Cuando hagas uno, lo sigues desde aquí.')).toBeInTheDocument();

    await fillSend(user);
    expect(await screen.findByRole('heading', { name: 'Confirma con tu PIN' })).toBeInTheDocument();
    expect(screen.getByText('Cuenta de ejemplo: tu PIN es 4821.')).toBeInTheDocument();

    await user.type(screen.getByLabelText('PIN de 4 dígitos'), '0000');
    await user.click(screen.getByRole('button', { name: 'Confirmar y enviar' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('PIN incorrecto');

    await user.type(screen.getByLabelText('PIN de 4 dígitos'), '4821');
    await user.click(screen.getByRole('button', { name: 'Confirmar y enviar' }));

    expect(await screen.findByText('Listo. Beto ya recibió el dinero.')).toBeInTheDocument();
    expect(screen.getByText('FN-A1B2C3D4')).toBeInTheDocument();
    expect(await screen.findByText(/350\.000,00/)).toBeInTheDocument();

    const [send] = api.calls('/api/transfers');
    const headers = send.init.headers as Record<string, string>;
    expect(headers['X-Financial-Session']).toBe('fs_1');
    expect(headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/);
    expect(JSON.parse(String(send.init.body))).toEqual({ recipientUserId: BETO, amount: '150000.00', currency: 'COP', description: 'Cena' });
    expect(api.calls('/api/users/lookup')[0].init.body).toBe(JSON.stringify({ email: 'beto@example.com' }));

    await user.click(screen.getByRole('button', { name: 'Listo' }));
    expect(screen.getByRole('button', { name: /Beto.*Completado/ })).toBeInTheDocument();
  });

  it('retries a send whose answer was lost with the same key and confirmation, without a new PIN', async () => {
    const api = backend({
      '/api/wallets/me/balance': [balance('500000.00')],
      '/api/users/lookup': [() => json(200, { userId: BETO })],
      '/api/auth/financial-session': [() => json(201, { financialSessionToken: 'fs_1' })],
      '/api/transfers': [() => { throw new TypeError('offline'); },
        () => json(202, { transferId: TRANSFER, status: 'PENDING', createdAt: '2026-11-03T15:00:00Z' })],
      [`/api/transfers/${TRANSFER}`]: [view('REVERSED', 'CREDIT_TIMEOUT')],
    });
    const user = userEvent.setup();
    await open();
    await fillSend(user);
    await user.type(await screen.findByLabelText('PIN de 4 dígitos'), '4821');
    await user.click(screen.getByRole('button', { name: 'Confirmar y enviar' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('No supimos si tu envío salió');
    expect(screen.queryByLabelText('PIN de 4 dígitos')).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Intentar de nuevo' }));

    expect(await screen.findByText('No se pudo completar. Tu dinero ya está de vuelta en tu billetera.')).toBeInTheDocument();
    const [first, second] = api.calls('/api/transfers').map((c) => c.init.headers as Record<string, string>);
    expect(second['Idempotency-Key']).toBe(first['Idempotency-Key']);
    expect(second['X-Financial-Session']).toBe('fs_1');
    expect(api.calls('/api/auth/financial-session')).toHaveLength(1);
  });

  it('asks for the PIN again when the confirmation expired, keeping the same send', async () => {
    const api = backend({
      '/api/wallets/me/balance': [balance('500000.00')],
      '/api/users/lookup': [() => json(200, { userId: BETO })],
      '/api/auth/financial-session': [() => json(201, { financialSessionToken: 'fs_1' }), () => json(201, { financialSessionToken: 'fs_2' })],
      '/api/transfers': [() => json(403, { code: 'FINANCIAL_SESSION_EXPIRED' }),
        () => json(202, { transferId: TRANSFER, status: 'PENDING', createdAt: '2026-11-03T15:00:00Z' })],
      [`/api/transfers/${TRANSFER}`]: [view('COMPLETED')],
    });
    const user = userEvent.setup();
    await open();
    await fillSend(user);
    await user.type(await screen.findByLabelText('PIN de 4 dígitos'), '4821');
    await user.click(screen.getByRole('button', { name: 'Confirmar y enviar' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Confirma de nuevo con tu PIN');

    await user.type(screen.getByLabelText('PIN de 4 dígitos'), '4821');
    await user.click(screen.getByRole('button', { name: 'Confirmar y enviar' }));
    expect(await screen.findByText('Listo. Beto ya recibió el dinero.')).toBeInTheDocument();
    const [first, second] = api.calls('/api/transfers').map((c) => c.init.headers as Record<string, string>);
    expect(second['Idempotency-Key']).toBe(first['Idempotency-Key']);
    expect(second['X-Financial-Session']).toBe('fs_2');
  });

  it('tells an unknown or own email apart before asking for the PIN', async () => {
    backend({
      '/api/wallets/me/balance': [balance('500000.00')],
      '/api/users/lookup': [() => json(404, { code: 'USER_NOT_FOUND' })],
    });
    const user = userEvent.setup();
    await open();
    await fillSend(user, 'nadie@example.com');
    expect(await screen.findByText('No encontramos una cuenta Finova con ese correo.')).toBeInTheDocument();

    const email = screen.getByLabelText('Correo de quien recibe');
    await user.clear(email);
    await user.type(email, 'ANA@example.com');
    expect(screen.getByText('Ese correo es el tuyo.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Continuar' })).toBeDisabled();

    await user.clear(screen.getByLabelText('Monto en pesos'));
    await user.type(screen.getByLabelText('Monto en pesos'), '1.234');
    expect(screen.getByText('Escribe un monto mayor a 0 con máximo 2 decimales.')).toBeInTheDocument();
  });

  it('explains a wallet that is not ready and keeps sending off', async () => {
    backend({ '/api/wallets/me/balance': [() => json(404, { code: 'WALLET_NOT_FOUND' })] });
    await open();
    expect(await screen.findByRole('alert')).toHaveTextContent('Tu cuenta aún no está lista');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Enviar dinero' })).toBeDisabled());
  });
});
