import { describe, expect, it } from 'vitest';
import { bondStatusLabel, corporateActionProgress, couponNote, couponWindow, hasHeldEntitlements } from './lifecycle.labels';
import type { CouponScheduleEntry } from '../models';

const base = { settlementTxHash: null, issuerAttestedAt: null, dualControlApprovedAt: null, heldOutstanding: false };
const coupon = (periodNo: number, paymentDate: string, couponStatus: CouponScheduleEntry['couponStatus']): CouponScheduleEntry => ({
  periodNo, periodStart: null, periodEnd: null, announcementDate: null, recordDate: null, paymentDate,
  dayCountFraction: null, amountPerUnit: 1, couponStatus, paidDate: couponStatus === 'PAID' ? paymentDate : null, scheduleVersion: 1,
});

describe('corporate action progress', () => {
  it('SNAPSHOT_BLOCKED is a wait state, not "awaiting your attestation"', () => {
    for (const p of ['issuer', 'investor'] as const) {
      const text = corporateActionProgress({ ...base, status: 'SNAPSHOT_BLOCKED' }, p);
      expect(text).toBe('Waiting for register reconciliation - no action needed from you');
      expect(text).not.toMatch(/attest/i);
    }
  });

  it('heldOutstanding marks a settled action as only partly paid out', () => {
    const a = { ...base, status: 'SETTLED' as const, heldOutstanding: true };
    expect(corporateActionProgress(a, 'investor')).toContain('Settled - some entitlements still held');
    expect(hasHeldEntitlements(a)).toBe(true);
    expect(hasHeldEntitlements({ ...a, heldOutstanding: false })).toBe(false);
    expect(corporateActionProgress({ ...base, status: 'SETTLED' }, 'issuer')).toBe('Settled off-chain');
  });

  it('perspective-specific attestation wording', () => {
    expect(corporateActionProgress({ ...base, status: 'ANNOUNCED' }, 'issuer')).toBe('Awaiting your attestation');
    expect(corporateActionProgress({ ...base, status: 'ANNOUNCED' }, 'investor')).toBe('Awaiting issuer attestation');
  });
});

describe('coupon window', () => {
  const now = Date.parse('2026-10-01T00:00:00Z');
  const rows = [
    coupon(1, '2025-04-01', 'PAID'), coupon(2, '2025-10-01', 'PAID'), coupon(3, '2026-04-01', 'MISSED'),
    coupon(4, '2026-09-21', 'OVERDUE'), coupon(5, '2027-04-01', 'SCHEDULED'), coupon(6, '2027-10-01', 'SCHEDULED'),
    coupon(7, '2028-04-01', 'SCHEDULED'), coupon(8, '2028-10-01', 'SCHEDULED'), coupon(9, '2029-04-01', 'SCHEDULED'),
  ];

  it('never drops OVERDUE or MISSED rows and lists newest first', () => {
    const w = couponWindow(rows);
    expect(w.map(c => c.periodNo)).toEqual([8, 7, 6, 5, 4, 3, 2, 1]);
    expect(w.some(c => c.couponStatus === 'MISSED')).toBe(true);
  });

  it('notes are factual and carry the days since the payment date', () => {
    expect(couponNote(rows[3], now)).toBe('Payment overdue - payment date passed 10 days ago');
    expect(couponNote(rows[2], now)).toMatch(/^Payment missed - payment date passed \d+ days ago$/);
    expect(couponNote(rows[4], now)).toBeNull();
    expect(bondStatusLabel('OVERDUE')).toBe('Payment overdue');
    expect(bondStatusLabel('DEFAULTED')).toBe('Defaulted (registry status)');
  });
});
