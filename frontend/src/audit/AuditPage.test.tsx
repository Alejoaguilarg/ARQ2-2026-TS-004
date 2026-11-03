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
    expect(await screen.findByText('5 records')).toBeInTheDocument();
    const list = screen.getByRole('region', { name: 'Resultados' });
    expect(within(list).getByText('Ana asked to send money')).toBeInTheDocument();
    expect(within(list).getByText('Beto received the money')).toBeInTheDocument();
    expect(within(list).getByText('Transfer blocked: the PIN confirmation expired')).toBeInTheDocument();
    expect(urls(api)[0]).toMatch(/from=.*&page=0&size=20/);

    await user().click(within(list).getByText('Transfer blocked: the PIN confirmation expired'));
    const detail = screen.getByRole('complementary');
    expect(within(detail).getByText('No money moved.')).toBeInTheDocument();
    expect(within(detail).getByText('FINANCIAL_SESSION_EXPIRED')).not.toBeVisible();
    expect(within(detail).getByRole('button', { name: 'See this whole transfer' })).toBeDisabled();
  });

  it('opens a whole transfer from one of its records', async () => {
    const api = backend({});
    await open();
    await user().click(await screen.findByText('Beto received the money'));
    await user().click(screen.getByRole('button', { name: 'See this whole transfer' }));
    expect(urls(api).at(-1)).toBe(`/api/audit/records?transferId=${T1}&page=0&size=20`);
    expect(screen.getByLabelText('Transfer')).toHaveValue(T1);
  });

  it('checks the form before searching', async () => {
    const api = backend({});
    await open();
    await screen.findByText('5 records');
    const u = user();
    await u.clear(screen.getByLabelText('From'));
    await u.click(screen.getByRole('button', { name: 'Search' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Choose a customer, paste a transfer or give a start date.');

    await u.type(screen.getByLabelText('Transfer'), 'FN-A1B2C3D4');
    await u.click(screen.getByRole('button', { name: 'Search' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Paste the full transfer identifier');

    await u.clear(screen.getByLabelText('Transfer'));
    await u.selectOptions(screen.getByLabelText('Customer'), 'Beto');
    await u.click(screen.getByRole('button', { name: 'Search' }));
    expect(urls(api).at(-1)).toBe(`/api/audit/records?userId=${BETO}&page=0&size=20`);
  });

  it('pages through long results', async () => {
    const api = backend({ records: () => json(200, { page: 0, size: 20, total: 45, items: SAGA }) });
    await open();
    expect(await screen.findByText('1 of 3')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Previous page' })).toBeDisabled();
    await user().click(screen.getByRole('button', { name: 'Next page' }));
    expect(await screen.findByText('2 of 3')).toBeInTheDocument();
    expect(urls(api).at(-1)).toMatch(/page=1&size=20$/);
  });

  it('says whether the record is intact', async () => {
    backend({ verify: () => json(200, { valid: false, checkedRecords: 10231, firstInvalidSeq: 8812, verifiedAt: '' }) });
    await open();
    await user().click(screen.getByRole('button', { name: 'Check now' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Record no. 8,812 does not match');
  });

  it('confirms an intact record and explains a failed search', async () => {
    backend({ records: () => json(403, { code: 'FORBIDDEN_ROLE' }) });
    await open();
    expect(await screen.findByRole('alert')).toHaveTextContent('for authorized staff only');
    await user().click(screen.getByRole('button', { name: 'Check now' }));
    expect(await screen.findByRole('status')).toHaveTextContent('We checked 10,231 records and none was changed.');
  });
});

describe('read', () => {
  it.each<[Partial<AuditRecord>, string]>([
    [{ source: 'GATEWAY', outcome: 'DUPLICATE', reason: 'DUPLICATE_REPLAYED' }, 'Ana tapped send again'],
    [{ source: 'GATEWAY', outcome: 'FAILED' }, 'The transfer service did not answer'],
    [{ outcome: 'REJECTED', reason: 'SOMETHING_NEW' }, 'Transfer blocked: it broke the rules'],
    [{ source: 'WALLET', stage: 'DEBIT', outcome: 'REJECTED', reason: 'INSUFFICIENT_FUNDS' }, 'Ana did not have enough balance'],
    [{ source: 'WALLET', stage: 'DEBIT', outcome: 'REJECTED', reason: 'SENDER_WALLET_NOT_FOUND' }, 'Ana has no wallet'],
    [{ source: 'WALLET', stage: 'CREDIT', outcome: 'FAILED', reason: 'RECIPIENT_WALLET_NOT_FOUND' }, 'The recipient has no wallet'],
    [{ source: 'WALLET', stage: 'CREDIT', outcome: 'FAILED', reason: 'TRANSFER_REVERSED' }, 'Late credit discarded'],
    [{ stage: 'CREDIT', outcome: 'FAILED', reason: 'CREDIT_TIMEOUT' }, 'The credit did not answer in time'],
    [{ source: 'WALLET', stage: 'REVERSAL', outcome: 'APPLIED' }, 'The money was returned to Ana'],
    [{ source: 'WALLET', stage: 'REVERSAL', outcome: 'REJECTED', reason: 'ALREADY_CREDITED' }, 'Refund not needed'],
    [{ stage: 'TRANSFER', outcome: 'REJECTED' }, 'Transfer rejected'],
    [{ stage: 'TRANSFER', outcome: 'COMPENSATED' }, 'Transfer returned'],
    [{ source: 'WALLET', stage: 'TRANSFER', outcome: 'ACCEPTED' }, 'Record stored'],
    [{ outcome: 'ACCEPTED', userId: null }, 'No session asked to send money'],
    [{ outcome: 'ACCEPTED', userId: '99999999-0000-4000-8000-000000000000' }, 'Customer 99999999 asked to send money'],
  ])('reads %j', (over, what) => expect(read(record(1, over)).what).toBe(what));

  it('tells a blocked request from a rejected one', () => {
    expect(read(record(1, { source: 'GATEWAY', outcome: 'REJECTED' })).badge).toBe('Blocked');
    expect(read(record(1, { source: 'WALLET', stage: 'DEBIT', outcome: 'REJECTED' })).badge).toBe('Rejected');
  });

  it('turns a day into its bounds and ignores anything else', () => {
    expect(dayBound('2026-11-03', false)).toBe(new Date('2026-11-03T00:00:00').toISOString());
    expect(dayBound('2026-11-03', true)).toBe(new Date('2026-11-03T23:59:59.999').toISOString());
    expect(dayBound('', false)).toBeUndefined();
  });
});

function user() {
  return userEvent.setup();
}
