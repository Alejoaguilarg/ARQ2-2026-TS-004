import type { Session } from '../auth/session';

// The audit trail (spec.md §3.10, §3.11, §4.8) and how each record reads to a person who is not an engineer.

export interface AuditRecord {
  seq: number;
  eventId: string;
  source: 'GATEWAY' | 'TRANSFER' | 'WALLET';
  stage: 'REQUEST' | 'DEBIT' | 'CREDIT' | 'REVERSAL' | 'TRANSFER';
  outcome: 'ACCEPTED' | 'REJECTED' | 'DUPLICATE' | 'FAILED' | 'COMPENSATED' | 'COMPLETED' | 'APPLIED';
  reason: string | null;
  transferId: string | null;
  userId: string | null;
  idempotencyKey: string | null;
  amount: string | null;
  httpStatus: number | null;
  correlationId: string;
  occurredAt: string;
  recordedAt: string;
  prevHash: string;
  hash: string;
}

export interface AuditPage {
  page: number;
  size: number;
  total: number;
  items: AuditRecord[];
}

export interface Verification {
  valid: boolean;
  checkedRecords: number;
  firstInvalidSeq: number | null;
  verifiedAt: string;
}

export interface Search {
  userId?: string;
  transferId?: string;
  from?: string;
  to?: string;
}

export const PAGE_SIZE = 20;

export async function records(session: Session, search: Search, page: number): Promise<AuditPage> {
  const query = new URLSearchParams();
  Object.entries(search).forEach(([k, v]) => v && query.set(k, v));
  query.set('page', String(page));
  query.set('size', String(PAGE_SIZE));
  return (await session.call<AuditPage>(`/audit/records?${query}`)).body;
}

export const verify = async (session: Session) => (await session.call<Verification>('/audit/verify')).body;

// Example accounts seeded by identity-service; anyone else is shown by the start of their id.
export const CUSTOMERS = [
  { id: '7f3c2b1a-0d4e-4f5a-8b6c-9d0e1f2a3b4c', name: 'Ana' },
  { id: '2b9d4e6f-1a3c-4e5b-9d7f-0a1b2c3d4e5f', name: 'Beto' },
  { id: '5e8a1c3d-7b9f-4d2e-8a6c-1f3b5d7e9a0c', name: 'Auditor' },
];

export function customer(userId: string | null): string {
  if (!userId) {
    return 'No session';
  }
  return CUSTOMERS.find((c) => c.id === userId)?.name ?? `Customer ${userId.slice(0, 8)}`;
}

export type Tone = 'ok' | 'info' | 'warn' | 'bad' | 'plain';

export interface Reading {
  what: string;
  explain: string;
  badge: string;
  tone: Tone;
}

const BADGES: Record<AuditRecord['outcome'], [string, Tone]> = {
  ACCEPTED: ['Received', 'info'],
  APPLIED: ['Applied', 'ok'],
  COMPLETED: ['Completed', 'ok'],
  DUPLICATE: ['Repeated', 'plain'],
  REJECTED: ['Blocked', 'bad'],
  FAILED: ['Failed', 'bad'],
  COMPENSATED: ['Returned', 'warn'],
};

const BLOCKED: Record<string, string> = {
  FINANCIAL_SESSION_EXPIRED: 'the PIN confirmation expired',
  FINANCIAL_SESSION_USED: 'the PIN confirmation was already used',
  FINANCIAL_SESSION_INVALID: 'the PIN confirmation was not valid',
  FINANCIAL_SESSION_REQUIRED: 'the PIN confirmation was missing',
  UNAUTHORIZED: 'the session was not valid',
  TOKEN_REVOKED: 'the session was already closed',
  RATE_LIMIT_EXCEEDED: 'too many attempts in a row',
  MISSING_KEY: 'the app did not identify the transfer',
  KEY_REUSED: 'a transfer was repeated with different data',
  IDEMPOTENCY_KEY_REUSED: 'a transfer was repeated with different data',
  SECURITY_STORE_UNAVAILABLE: 'the security system was not available',
  IDEMPOTENCY_STORE_UNAVAILABLE: 'the security system was not available',
  SELF_TRANSFER_NOT_ALLOWED: 'tried to send money to themselves',
  VALIDATION_ERROR: 'the transfer data was not valid',
};

export function read(r: AuditRecord): Reading {
  const [shown, tone] = BADGES[r.outcome] ?? ['Recorded', 'plain'];
  // Only the Gateway blocks; a no from Transfer or Wallet is a rejection.
  const badge = r.outcome === 'REJECTED' && r.source !== 'GATEWAY' ? 'Rejected' : shown;
  const who = customer(r.userId);
  const say = (what: string, explain: string): Reading => ({ what, explain, badge, tone });
  const key = `${r.source} ${r.stage} ${r.outcome}`;

  switch (key) {
    case 'TRANSFER REQUEST ACCEPTED':
      return say(`${who} asked to send money`, 'The request arrived and is in progress.');
    case 'GATEWAY REQUEST DUPLICATE':
    case 'TRANSFER REQUEST DUPLICATE':
      return say(`${who} tapped send again`, 'It was the same transfer repeated, so it was not charged again.');
    case 'GATEWAY REQUEST REJECTED':
    case 'TRANSFER REQUEST REJECTED':
      return say(`Transfer blocked: ${BLOCKED[r.reason ?? ''] ?? 'it broke the rules'}`, 'No money moved.');
    case 'GATEWAY REQUEST FAILED':
      return say('The transfer service did not answer', 'The request could not be served. A retry is never charged twice.');
    case 'WALLET DEBIT APPLIED':
      return say(`The money was taken from ${who}'s account`, `${who}'s balance went down by the transfer amount.`);
    case 'WALLET DEBIT REJECTED':
      return r.reason === 'INSUFFICIENT_FUNDS'
        ? say(`${who} did not have enough balance`, 'Nothing was taken.')
        : say(`${who} has no wallet`, 'Nothing was taken.');
    case 'WALLET CREDIT APPLIED':
      return say(`${who} received the money`, `${who}'s balance went up by the transfer amount.`);
    case 'WALLET CREDIT FAILED':
      return r.reason === 'TRANSFER_REVERSED'
        ? say('Late credit discarded', 'The transfer had already been returned, so it was not credited.')
        : say('The recipient has no wallet', 'It could not be credited; the money goes back to the sender.');
    case 'TRANSFER CREDIT FAILED':
      return say('The credit did not answer in time', 'It is retried up to 3 times; if it never answers, the money is returned.');
    case 'WALLET REVERSAL APPLIED':
      return say(`The money was returned to ${who}`, `${who}'s balance is back to what it was before the transfer.`);
    case 'WALLET REVERSAL REJECTED':
      return say('Refund not needed', 'The recipient had already received the money.');
    case 'TRANSFER TRANSFER COMPLETED':
      return say('Transfer completed', 'The transfer went well from start to end.');
    case 'TRANSFER TRANSFER REJECTED':
      return say('Transfer rejected', 'The transfer ended without moving money.');
    case 'TRANSFER TRANSFER COMPENSATED':
      return say('Transfer returned', 'The transfer did not complete and the money went back to the sender.');
    default:
      return say('Record stored', 'See the technical data for more detail.');
  }
}

// A date input ("2026-11-03") as the start or the end of that day in the browser's time zone.
export function dayBound(day: string, end: boolean): string | undefined {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(day)) {
    return undefined;
  }
  const date = new Date(`${day}T${end ? '23:59:59.999' : '00:00:00'}`);
  return Number.isNaN(date.getTime()) ? undefined : date.toISOString();
}

export const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
