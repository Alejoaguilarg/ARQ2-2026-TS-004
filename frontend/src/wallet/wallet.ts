import type { Session } from '../auth/session';

// The wallet calls (spec.md §3.6 to §3.9, §3.12) and the pure helpers the wallet screen needs.

export interface Balance {
  walletId: string;
  available: string;
  currency: string;
  version: number;
  asOf: string;
}

export type TransferStatus = 'PENDING' | 'DEBITED' | 'COMPLETED' | 'REJECTED' | 'COMPENSATING' | 'REVERSED';

export interface TransferView {
  transferId: string;
  amount: string;
  currency: string;
  description?: string | null;
  status: TransferStatus;
  failureReason?: string | null;
  createdAt: string;
}

export interface Accepted {
  transferId: string;
  status: TransferStatus;
  createdAt: string;
}

export interface TransferRequest {
  recipientUserId: string;
  amount: string;
  description: string;
}

export const balance = async (session: Session) => (await session.call<Balance>('/wallets/me/balance')).body;

export const findRecipient = async (session: Session, email: string) =>
  (await session.call<{ userId: string }>('/users/lookup', { method: 'POST', body: { email: email.trim() } })).body.userId;

export const confirmWithPin = async (session: Session, pin: string) =>
  (await session.call<{ financialSessionToken: string }>('/auth/financial-session', {
    method: 'POST',
    body: { method: 'PIN', credential: pin, scope: 'transfer:create' },
  })).body.financialSessionToken;

// The same Idempotency-Key and financial session on a retry: the Gateway replays the first answer instead of
// sending the money twice, and accepts the session again because it serves that one intent (spec.md §3.1.1).
export const sendMoney = async (session: Session, transfer: TransferRequest, key: string, financialSession: string) =>
  (await session.call<Accepted>('/transfers', {
    method: 'POST',
    headers: { 'Idempotency-Key': key, 'X-Financial-Session': financialSession },
    body: {
      recipientUserId: transfer.recipientUserId,
      amount: transfer.amount,
      currency: 'COP',
      ...(transfer.description ? { description: transfer.description } : {}),
    },
  })).body;

export const transfer = async (session: Session, id: string) =>
  (await session.call<TransferView>(`/transfers/${encodeURIComponent(id)}`)).body;

export const isFinal = (status: TransferStatus) => status === 'COMPLETED' || status === 'REJECTED' || status === 'REVERSED';

// "1.500,5" or "1500.5" -> "1500.50"; null when it is not a positive amount with at most 2 decimals.
export function toAmount(typed: string): string | null {
  const text = typed.trim().replace(/\s/g, '');
  const normalized = /,\d{1,2}$/.test(text) ? text.replace(/\./g, '').replace(',', '.') : text.replace(/,/g, '');
  const match = /^(\d{1,13})(?:\.(\d{1,2}))?$/.exec(normalized);
  if (!match) {
    return null;
  }
  const whole = match[1].replace(/^0+(?=\d)/, '');
  const amount = `${whole}.${(match[2] ?? '').padEnd(2, '0')}`;
  return /^0\.00$/.test(amount) ? null : amount;
}

const money = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

export const formatMoney = (amount: string) => money.format(Number(amount));

export const receipt = (transferId: string) => 'FN-' + transferId.slice(0, 8).toUpperCase();

// ---------- sends made from this browser (the backend has no "my transfers" list)

export interface SentItem {
  transferId: string;
  to: string;
  amount: string;
  description: string;
  createdAt: string;
  status: TransferStatus;
  failureReason?: string | null;
}

const MAX_SENT = 10;
const sentKey = (userId: string) => `finova.sent.${userId}`;

export function sentList(userId: string, store: Storage = localStorage): SentItem[] {
  try {
    const items = JSON.parse(store.getItem(sentKey(userId)) ?? '[]');
    return Array.isArray(items) ? items : [];
  } catch {
    return [];
  }
}

export function saveSent(userId: string, item: SentItem, store: Storage = localStorage): SentItem[] {
  const items = [item, ...sentList(userId, store).filter((i) => i.transferId !== item.transferId)].slice(0, MAX_SENT);
  try {
    store.setItem(sentKey(userId), JSON.stringify(items));
  } catch {
    // Storage full or blocked: the list is a convenience only.
  }
  return items;
}

// ---------- how a transfer reads to the customer

export type StepKind = 'done' | 'active' | 'todo' | 'fail' | 'back';

export interface Step {
  label: string;
  detail: string;
  kind: StepKind;
}

export interface Progress {
  badge: string;
  tone: 'info' | 'ok' | 'bad' | 'warn';
  headline: string;
  steps: Step[];
}

const NOT_CREDITED: Record<string, string> = {
  CREDIT_TIMEOUT: 'We could not complete the credit in time.',
  RECIPIENT_WALLET_NOT_FOUND: 'That person does not have a Finova wallet yet.',
};

export function progress(status: TransferStatus, reason: string | null | undefined, to: string): Progress {
  const received: Step = { label: 'Request received', detail: 'We received your transfer', kind: 'done' };
  switch (status) {
    case 'REJECTED':
      return {
        badge: 'Rejected', tone: 'bad',
        headline: reason === 'INSUFFICIENT_FUNDS'
          ? 'You do not have enough balance for this transfer. Nothing was taken.'
          : 'We could not make this transfer. Nothing was taken from your account.',
        steps: [received, {
          label: reason === 'INSUFFICIENT_FUNDS' ? 'Insufficient balance' : 'Transfer not made',
          detail: 'Nothing was taken from your account', kind: 'fail',
        }],
      };
    case 'COMPENSATING':
    case 'REVERSED': {
      const back = status === 'REVERSED';
      return {
        badge: back ? 'Returned' : 'Returning', tone: 'warn',
        headline: back
          ? 'It could not be completed. Your money is back in your wallet.'
          : 'We could not credit the recipient. We are returning your money.',
        steps: [
          received,
          { label: 'Debit from your wallet', detail: 'Applied', kind: 'done' },
          { label: 'Credit to the recipient', detail: NOT_CREDITED[reason ?? ''] ?? 'It could not be completed', kind: 'fail' },
          { label: 'Refund to your wallet', detail: back ? 'The money is back' : 'Returning the money…', kind: back ? 'back' : 'active' },
        ],
      };
    }
    default: {
      const pending = status === 'PENDING';
      const done = status === 'COMPLETED';
      return {
        badge: done ? 'Completed' : 'In progress', tone: done ? 'ok' : 'info',
        headline: done ? `Done. ${to} received the money.`
          : pending ? 'We received your transfer. We are checking your funds.'
            : 'We took the money. We are crediting it to the recipient.',
        steps: [
          received,
          { label: 'Debit from your wallet', detail: pending ? 'Checking funds…' : 'Applied', kind: pending ? 'active' : 'done' },
          { label: 'Credit to the recipient', detail: done ? 'Received' : 'Pending', kind: done ? 'done' : pending ? 'todo' : 'active' },
        ],
      };
    }
  }
}
