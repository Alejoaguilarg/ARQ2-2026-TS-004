import { describe, expect, it } from 'vitest';
import { friendly, supportReference } from './messages';

const error = (code: string, extra = {}) => ({ status: 400, code, message: 'x', ...extra });

describe('friendly', () => {
  it('never shows the code and always gives a next step', () => {
    for (const code of ['INVALID_CREDENTIALS', 'ACCOUNT_LOCKED', 'RATE_LIMIT_EXCEEDED', 'SECURITY_STORE_UNAVAILABLE',
      'IDEMPOTENCY_STORE_UNAVAILABLE', 'UPSTREAM_UNAVAILABLE', 'NETWORK_ERROR', 'VALIDATION_ERROR', 'INTERNAL_ERROR',
      'STEP_UP_FAILED', 'STEP_UP_LOCKED', 'FINANCIAL_SESSION_REQUIRED', 'FINANCIAL_SESSION_INVALID', 'FINANCIAL_SESSION_EXPIRED',
      'FINANCIAL_SESSION_USED', 'SELF_TRANSFER_NOT_ALLOWED', 'IDEMPOTENCY_IN_PROGRESS', 'IDEMPOTENCY_KEY_REUSED', 'USER_NOT_FOUND',
      'WALLET_NOT_FOUND', 'TRANSFER_NOT_FOUND', 'FORBIDDEN_ROLE']) {
      const message = friendly(error(code));
      expect(message.title + message.text).not.toContain(code);
      expect(message.text.length).toBeGreaterThan(10);
    }
  });

  it('says how long to wait when the Gateway says it', () => {
    expect(friendly(error('RATE_LIMIT_EXCEEDED', { retryAfterSeconds: 42 })).text).toContain('42 segundos');
    expect(friendly(error('RATE_LIMIT_EXCEEDED')).text).toBe('Espera un momento y vuelve a intentarlo.');
  });

  it('an unexpected error carries a short support reference', () => {
    expect(friendly(error('INTERNAL_ERROR', { correlationId: '5d0f3f0e-8a4b-4a39-9a51-0f1c1b2a7e11' })).text)
      .toContain('FN-5D0F3F0E');
    expect(friendly(error('SOMETHING_NEW')).text).toBe('Si vuelve a pasar, escríbenos.');
    expect(supportReference({})).toBeUndefined();
  });
});
