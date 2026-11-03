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
    return 'Sin sesión';
  }
  return CUSTOMERS.find((c) => c.id === userId)?.name ?? `Cliente ${userId.slice(0, 8)}`;
}

export type Tone = 'ok' | 'info' | 'warn' | 'bad' | 'plain';

export interface Reading {
  what: string;
  explain: string;
  badge: string;
  tone: Tone;
}

const BADGES: Record<AuditRecord['outcome'], [string, Tone]> = {
  ACCEPTED: ['Recibido', 'info'],
  APPLIED: ['Aplicado', 'ok'],
  COMPLETED: ['Completado', 'ok'],
  DUPLICATE: ['Repetido', 'plain'],
  REJECTED: ['Bloqueado', 'bad'],
  FAILED: ['Falló', 'bad'],
  COMPENSATED: ['Devuelto', 'warn'],
};

const BLOCKED: Record<string, string> = {
  FINANCIAL_SESSION_EXPIRED: 'la confirmación con PIN venció',
  FINANCIAL_SESSION_USED: 'la confirmación con PIN ya se había usado',
  FINANCIAL_SESSION_INVALID: 'la confirmación con PIN no era válida',
  FINANCIAL_SESSION_REQUIRED: 'faltó la confirmación con PIN',
  UNAUTHORIZED: 'la sesión no era válida',
  TOKEN_REVOKED: 'la sesión ya estaba cerrada',
  RATE_LIMIT_EXCEEDED: 'demasiados intentos seguidos',
  MISSING_KEY: 'la app no identificó el envío',
  KEY_REUSED: 'se repitió un envío con datos distintos',
  IDEMPOTENCY_KEY_REUSED: 'se repitió un envío con datos distintos',
  SECURITY_STORE_UNAVAILABLE: 'el sistema de seguridad no estaba disponible',
  IDEMPOTENCY_STORE_UNAVAILABLE: 'el sistema de seguridad no estaba disponible',
  SELF_TRANSFER_NOT_ALLOWED: 'intentó enviarse dinero a sí mismo',
  VALIDATION_ERROR: 'los datos del envío no eran válidos',
};

export function read(r: AuditRecord): Reading {
  const [badge, tone] = BADGES[r.outcome] ?? ['Registrado', 'plain'];
  const who = customer(r.userId);
  const say = (what: string, explain: string): Reading => ({ what, explain, badge, tone });
  const key = `${r.source} ${r.stage} ${r.outcome}`;

  switch (key) {
    case 'TRANSFER REQUEST ACCEPTED':
      return say(`${who} pidió enviar dinero`, 'La solicitud llegó bien y quedó en proceso.');
    case 'GATEWAY REQUEST DUPLICATE':
    case 'TRANSFER REQUEST DUPLICATE':
      return say(`${who} tocó enviar otra vez`, 'Era el mismo envío repetido, así que no se cobró de nuevo.');
    case 'GATEWAY REQUEST REJECTED':
    case 'TRANSFER REQUEST REJECTED':
      return say(`Envío bloqueado: ${BLOCKED[r.reason ?? ''] ?? 'no cumplió las reglas'}`, 'No se movió dinero.');
    case 'GATEWAY REQUEST FAILED':
      return say('El servicio de envíos no respondió', 'La solicitud no se pudo atender. Si se reintenta, no se cobra dos veces.');
    case 'WALLET DEBIT APPLIED':
      return say(`Se descontó el dinero de la cuenta de ${who}`, `El saldo de ${who} bajó por el valor del envío.`);
    case 'WALLET DEBIT REJECTED':
      return r.reason === 'INSUFFICIENT_FUNDS'
        ? say(`${who} no tenía saldo suficiente`, 'No se descontó nada.')
        : say(`${who} no tiene billetera`, 'No se descontó nada.');
    case 'WALLET CREDIT APPLIED':
      return say(`${who} recibió el dinero`, `El saldo de ${who} subió por el valor del envío.`);
    case 'WALLET CREDIT FAILED':
      return r.reason === 'TRANSFER_REVERSED'
        ? say('Abono tardío descartado', 'El envío ya se había devuelto, así que no se abonó.')
        : say('El destinatario no tiene billetera', 'No se pudo abonar; el dinero se devuelve a quien envió.');
    case 'TRANSFER CREDIT FAILED':
      return say('El abono no respondió a tiempo', 'Se reintenta hasta 3 veces; si no responde, se devuelve el dinero.');
    case 'WALLET REVERSAL APPLIED':
      return say(`Se le devolvió el dinero a ${who}`, `El saldo de ${who} volvió a quedar como antes del envío.`);
    case 'WALLET REVERSAL REJECTED':
      return say('Devolución no necesaria', 'El destinatario ya había recibido el dinero.');
    case 'TRANSFER TRANSFER COMPLETED':
      return say('Envío completado', 'El envío terminó bien de principio a fin.');
    case 'TRANSFER TRANSFER REJECTED':
      return say('Envío rechazado', 'El envío terminó sin mover dinero.');
    case 'TRANSFER TRANSFER COMPENSATED':
      return say('Envío devuelto', 'El envío no se completó y el dinero volvió a quien lo envió.');
    default:
      return say('Movimiento registrado', 'Revisa los datos técnicos para más detalle.');
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
