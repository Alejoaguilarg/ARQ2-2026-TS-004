import { describe, expect, it } from 'vitest';
import { progress, receipt, saveSent, sentList, toAmount, type SentItem } from './wallet';

describe('toAmount', () => {
  it.each([
    ['150000', '150000.00'],
    ['1500.5', '1500.50'],
    ['1.500,5', '1500.50'],
    ['1,500.25', '1500.25'],
    [' 007 ', '7.00'],
    ['0,10', '0.10'],
  ])('reads %j as %s', (typed, amount) => expect(toAmount(typed)).toBe(amount));

  it.each(['', '0', '0.00', '-5', '1.234', 'abc', '12345678901234'])('rejects %j', (typed) =>
    expect(toAmount(typed)).toBeNull());
});

describe('progress', () => {
  it('walks a send to completion', () => {
    expect(progress('PENDING', null, 'Beto').steps.map((s) => s.kind)).toEqual(['done', 'active', 'todo']);
    expect(progress('DEBITED', null, 'Beto').steps.map((s) => s.kind)).toEqual(['done', 'done', 'active']);
    const done = progress('COMPLETED', null, 'Beto');
    expect(done.headline).toBe('Done. Beto received the money.');
    expect(done.tone).toBe('ok');
  });

  it('says nothing was charged when the funds were short', () => {
    const p = progress('REJECTED', 'INSUFFICIENT_FUNDS', 'Beto');
    expect(p.badge).toBe('Rejected');
    expect(p.steps[1].label).toBe('Insufficient balance');
    expect(progress('REJECTED', 'SENDER_WALLET_NOT_FOUND', 'Beto').steps[1].label).toBe('Transfer not made');
  });

  it('shows the money coming back after a failed credit', () => {
    expect(progress('COMPENSATING', 'CREDIT_TIMEOUT', 'Beto').steps[3].kind).toBe('active');
    const p = progress('REVERSED', 'RECIPIENT_WALLET_NOT_FOUND', 'Beto');
    expect(p.badge).toBe('Returned');
    expect(p.steps[2].detail).toBe('That person does not have a Finova wallet yet.');
    expect(p.steps[3].kind).toBe('back');
  });
});

describe('sent list', () => {
  const item = (id: string): SentItem => ({
    transferId: id, to: 'beto@example.com', amount: '1.00', description: '', createdAt: '2026-11-03T15:00:00Z', status: 'PENDING',
  });

  it('keeps the newest ten per user and updates in place', () => {
    for (let i = 0; i < 12; i++) {
      saveSent('u-1', item(`t-${i}`));
    }
    saveSent('u-1', { ...item('t-5'), status: 'COMPLETED' });
    const list = sentList('u-1');
    expect(list).toHaveLength(10);
    expect(list[0]).toMatchObject({ transferId: 't-5', status: 'COMPLETED' });
    expect(sentList('u-2')).toEqual([]);
  });

  it('survives a broken entry', () => {
    localStorage.setItem('finova.sent.u-3', '{nope');
    expect(sentList('u-3')).toEqual([]);
  });

  it('prints a short receipt', () => expect(receipt('a1b2c3d4-e5f6')).toBe('FN-A1B2C3D4'));
});
