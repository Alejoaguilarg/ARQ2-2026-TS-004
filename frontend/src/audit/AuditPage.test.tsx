import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../auth/AuthContext';
import { Session } from '../auth/session';
import { AuditPage } from './AuditPage';
import { dayBound, read, type AuditRecord } from './audit';

const ANA = '7f3c2b1a-0d4e-4f5a-8b6c-9d0e1f2a3b4c';
const BETO = '2b9d4e6f-1a3c-4e5b-9d7f-0a1b2c3d4e5f';
const T1 = 'a1b2c3d4-e5f6-4890-abcd-ef1234567890';

const token = () => `h.${btoa(JSON.stringify({ sub: 'aud', roles: ['AUDITOR'] })).replace(/=+$/, '')}.s`;
const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status });

const record = (seq: number, over: Partial<AuditRecord>): AuditRecord => ({
  seq, eventId: `e-${seq}`, source: 'TRANSFER', stage: 'REQUEST', outcome: 'ACCEPTED', reason: null, transferId: T1,
  userId: ANA, idempotencyKey: null, amount: '150000.00', httpStatus: null, correlationId: 'c-1',
  occurredAt: '2026-11-03T15:00:00Z', recordedAt: '2026-11-03T15:00:00.040Z', prevHash: '0'.repeat(64), hash: 'a'.repeat(64), ...over,
});

const SAGA = [
  record(1, {}),
  record(2, { source: 'WALLET', stage: 'DEBIT', outcome: 'APPLIED' }),
  record(3, { source: 'WALLET', stage: 'CREDIT', outcome: 'APPLIED', userId: BETO }),
  record(4, { stage: 'TRANSFER', outcome: 'COMPLETED' }),
  record(5, { source: 'GATEWAY', outcome: 'REJECTED', reason: 'FINANCIAL_SESSION_EXPIRED', transferId: null, amount: null, httpStatus: 403 }),
];

function backend(answers: { records?: () => Response; verify?: () => Response }) {
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
    const url = String(input);
    if (url === '/api/auth/refresh') return json(200, { accessToken: token(), refreshToken: 'rt_2' });
    if (url.startsWith('/api/audit/records')) return (answers.records ?? (() => json(200, { page: 0, size: 20, total: 5, items: SAGA })))();
    if (url === '/api/audit/verify') return (answers.verify ?? (() => json(200, { valid: true, checkedRecords: 10231, firstInvalidSeq: null, verifiedAt: '' })))();
    return json(404, {});
  });
}

async function open() {
  const session = new Session();
  await session.restore();
  render(<AuthProvider session={session}><AuditPage /></AuthProvider>);
}

const urls = (spy: ReturnType<typeof backend>) => spy.mock.calls.map(([u]) => String(u)).filter((u) => u.includes('/audit/records'));

describe('AuditPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    sessionStorage.clear();
    sessionStorage.setItem('finova.refreshToken', 'rt_1');
    sessionStorage.setItem('finova.email', 'auditor@example.com');
  });

  it('lists the last week in plain words and keeps the technical data folded', async () => {
    const api = backend({});
    await open();
    expect(await screen.findByText('5 movimientos')).toBeInTheDocument();
    const list = screen.getByRole('region', { name: 'Resultados' });
    expect(within(list).getByText('Ana pidió enviar dinero')).toBeInTheDocument();
    expect(within(list).getByText('Beto recibió el dinero')).toBeInTheDocument();
    expect(within(list).getByText('Envío bloqueado: la confirmación con PIN venció')).toBeInTheDocument();
    expect(urls(api)[0]).toMatch(/from=.*&page=0&size=20/);

    await user().click(within(list).getByText('Envío bloqueado: la confirmación con PIN venció'));
    const detail = screen.getByRole('complementary');
    expect(within(detail).getByText('No se movió dinero.')).toBeInTheDocument();
    expect(within(detail).getByText('FINANCIAL_SESSION_EXPIRED')).not.toBeVisible();
    expect(within(detail).getByRole('button', { name: 'Ver todo este envío' })).toBeDisabled();
  });

  it('opens a whole transfer from one of its records', async () => {
    const api = backend({});
    await open();
    await user().click(await screen.findByText('Beto recibió el dinero'));
    await user().click(screen.getByRole('button', { name: 'Ver todo este envío' }));
    expect(urls(api).at(-1)).toBe(`/api/audit/records?transferId=${T1}&page=0&size=20`);
    expect(screen.getByLabelText('Envío')).toHaveValue(T1);
  });

  it('checks the form before searching', async () => {
    const api = backend({});
    await open();
    await screen.findByText('5 movimientos');
    const u = user();
    await u.clear(screen.getByLabelText('Desde'));
    await u.click(screen.getByRole('button', { name: 'Buscar' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Elige un cliente, pega un envío o indica desde qué fecha buscar.');

    await u.type(screen.getByLabelText('Envío'), 'FN-A1B2C3D4');
    await u.click(screen.getByRole('button', { name: 'Buscar' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Pega el identificador completo del envío');

    await u.clear(screen.getByLabelText('Envío'));
    await u.selectOptions(screen.getByLabelText('Cliente'), 'Beto');
    await u.click(screen.getByRole('button', { name: 'Buscar' }));
    expect(urls(api).at(-1)).toBe(`/api/audit/records?userId=${BETO}&page=0&size=20`);
  });

  it('pages through long results', async () => {
    const api = backend({ records: () => json(200, { page: 0, size: 20, total: 45, items: SAGA }) });
    await open();
    expect(await screen.findByText('1 de 3')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Página anterior' })).toBeDisabled();
    await user().click(screen.getByRole('button', { name: 'Página siguiente' }));
    expect(await screen.findByText('2 de 3')).toBeInTheDocument();
    expect(urls(api).at(-1)).toMatch(/page=1&size=20$/);
  });

  it('says whether the record is intact', async () => {
    backend({ verify: () => json(200, { valid: false, checkedRecords: 10231, firstInvalidSeq: 8812, verifiedAt: '' }) });
    await open();
    await user().click(screen.getByRole('button', { name: 'Revisar ahora' }));
    expect(await screen.findByRole('status')).toHaveTextContent('El movimiento n.º 8.812 no coincide');
  });

  it('confirms an intact record and explains a failed search', async () => {
    backend({ records: () => json(403, { code: 'FORBIDDEN_ROLE' }) });
    await open();
    expect(await screen.findByRole('alert')).toHaveTextContent('solo para personal autorizado');
    await user().click(screen.getByRole('button', { name: 'Revisar ahora' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Revisamos 10.231 movimientos y ninguno fue modificado.');
  });
});

describe('read', () => {
  it.each<[Partial<AuditRecord>, string]>([
    [{ source: 'GATEWAY', outcome: 'DUPLICATE', reason: 'DUPLICATE_REPLAYED' }, 'Ana tocó enviar otra vez'],
    [{ source: 'GATEWAY', outcome: 'FAILED' }, 'El servicio de envíos no respondió'],
    [{ outcome: 'REJECTED', reason: 'SOMETHING_NEW' }, 'Envío bloqueado: no cumplió las reglas'],
    [{ source: 'WALLET', stage: 'DEBIT', outcome: 'REJECTED', reason: 'INSUFFICIENT_FUNDS' }, 'Ana no tenía saldo suficiente'],
    [{ source: 'WALLET', stage: 'DEBIT', outcome: 'REJECTED', reason: 'SENDER_WALLET_NOT_FOUND' }, 'Ana no tiene billetera'],
    [{ source: 'WALLET', stage: 'CREDIT', outcome: 'FAILED', reason: 'RECIPIENT_WALLET_NOT_FOUND' }, 'El destinatario no tiene billetera'],
    [{ source: 'WALLET', stage: 'CREDIT', outcome: 'FAILED', reason: 'TRANSFER_REVERSED' }, 'Abono tardío descartado'],
    [{ stage: 'CREDIT', outcome: 'FAILED', reason: 'CREDIT_TIMEOUT' }, 'El abono no respondió a tiempo'],
    [{ source: 'WALLET', stage: 'REVERSAL', outcome: 'APPLIED' }, 'Se le devolvió el dinero a Ana'],
    [{ source: 'WALLET', stage: 'REVERSAL', outcome: 'REJECTED', reason: 'ALREADY_CREDITED' }, 'Devolución no necesaria'],
    [{ stage: 'TRANSFER', outcome: 'REJECTED' }, 'Envío rechazado'],
    [{ stage: 'TRANSFER', outcome: 'COMPENSATED' }, 'Envío devuelto'],
    [{ source: 'WALLET', stage: 'TRANSFER', outcome: 'ACCEPTED' }, 'Movimiento registrado'],
    [{ outcome: 'ACCEPTED', userId: null }, 'Sin sesión pidió enviar dinero'],
    [{ outcome: 'ACCEPTED', userId: '99999999-0000-4000-8000-000000000000' }, 'Cliente 99999999 pidió enviar dinero'],
  ])('reads %j', (over, what) => expect(read(record(1, over)).what).toBe(what));

  it('turns a day into its bounds and ignores anything else', () => {
    expect(dayBound('2026-11-03', false)).toBe(new Date('2026-11-03T00:00:00').toISOString());
    expect(dayBound('2026-11-03', true)).toBe(new Date('2026-11-03T23:59:59.999').toISOString());
    expect(dayBound('', false)).toBeUndefined();
  });
});

function user() {
  return userEvent.setup();
}
