import { describe, expect, it } from 'vitest';
import { VaultRequest } from '../../../../core/models';
import { canFulfil, formatCutoffUtc, fulfilHint, parseCutoffUtc, periodLabel, periodSecondsOf } from './vault-dealing';

function request(over: Partial<VaultRequest> = {}): VaultRequest {
  return {
    id: 'r1', assetId: 'a1', requestId: '7', requestType: 'DEPOSIT', controllerAddr: '0x1', ownerAddr: '0x2',
    requestStatus: 'PENDING', requestedAt: '2027-01-14T18:00:00Z', awaitingConfirmation: false,
    complianceHold: false, ...over,
  };
}

describe('vault dealing helpers (T1-07)', () => {
  it('formats and parses the daily UTC cut-off', () => {
    expect(formatCutoffUtc(61200)).toBe('17:00');
    expect(formatCutoffUtc(0)).toBe('00:00');
    expect(formatCutoffUtc(86399)).toBe('23:59');
    expect(parseCutoffUtc('17:00')).toBe(61200);
    expect(parseCutoffUtc('00:05')).toBe(300);
    expect(parseCutoffUtc('24:00')).toBeNull();
    expect(parseCutoffUtc('17:60')).toBeNull();
    expect(parseCutoffUtc('5pm')).toBeNull();
    expect(parseCutoffUtc('')).toBeNull();
  });

  it('accepts dealing periods from one hour to 31 days only', () => {
    expect(periodSecondsOf(24)).toBe(86400);
    expect(periodSecondsOf(1)).toBe(3600);
    expect(periodSecondsOf(744)).toBe(2678400);
    expect(periodSecondsOf(0.5)).toBeNull();
    expect(periodSecondsOf(745)).toBeNull();
    expect(periodSecondsOf(Number.NaN)).toBeNull();
  });

  it('labels the period', () => {
    expect(periodLabel(86400)).toBe('daily');
    expect(periodLabel(604800)).toBe('weekly');
    expect(periodLabel(43200)).toBe('every 12 h');
  });

  it('holds Fulfil back while the request waits for a NAV struck after its dealing point', () => {
    expect(canFulfil(request(), false)).toBe(true);
    expect(canFulfil(request({ awaitingNavStrike: true }), false)).toBe(false);
    expect(canFulfil(request({ complianceHold: true }), false)).toBe(false);
    expect(canFulfil(request({ awaitingConfirmation: true }), false)).toBe(false);
    expect(canFulfil(request(), true)).toBe(false);
  });

  it('explains why Fulfil is unavailable, naming the dealing point in UTC', () => {
    const hint = fulfilHint(request({ awaitingNavStrike: true, dealingPoint: '2027-01-15T17:00:00Z' }));
    expect(hint).toContain('Waiting for the next NAV strike');
    expect(hint).toContain('2027-01-15 17:00 UTC');
    expect(fulfilHint(request())).toBe('Fulfil');
  });
});
