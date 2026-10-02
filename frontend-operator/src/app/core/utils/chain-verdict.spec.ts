import { describe, expect, it } from 'vitest';
import { canAcknowledgeChain, chainVerdict } from './chain-verdict';
import { ChainVerificationResult } from '../models';

const base: ChainVerificationResult = { valid: true, rowsChecked: 10, checkedAt: '2026-10-01T00:00:00Z' };

describe('chainVerdict', () => {
  it('UNKNOWN is neutral and never claims a break', () => {
    const v = chainVerdict({ valid: false, rowsChecked: 0, checkedAt: null, status: 'UNKNOWN', reason: 'No audit chain verification has been recorded yet' });
    expect(v.kind).toBe('unknown');
    expect(v.title).toBe('Not yet verified');
    expect(`${v.title}${v.detail}`).not.toMatch(/BROKEN/);
  });

  it('BROKEN with a sequence shows the sequence and reason; a null sequence never prints undefined/null', () => {
    const broken = chainVerdict({ ...base, valid: false, status: 'BROKEN', firstBrokenSequenceNo: 42, reason: 'hash mismatch' });
    expect(broken.kind).toBe('broken');
    expect(broken.detail).toContain('sequence_no=42');
    expect(broken.detail).toContain('hash mismatch');
    const pending = chainVerdict({ ...base, valid: false, status: 'BROKEN', firstBrokenSequenceNo: null, reason: null });
    expect(pending.kind).toBe('pending-ack');
    expect(`${pending.title} ${pending.detail}`).not.toMatch(/undefined|null/);
  });

  it('VALID is intact', () => {
    expect(chainVerdict({ ...base, status: 'VALID' }).kind).toBe('valid');
  });

  it('only REGISTRY_ADMIN sees the acknowledge action, and only while BROKEN', () => {
    const broken: ChainVerificationResult = { ...base, valid: false, status: 'BROKEN', verificationId: 'v-1' };
    expect(canAcknowledgeChain(broken, true)).toBe(true);
    expect(canAcknowledgeChain(broken, false)).toBe(false);
    expect(canAcknowledgeChain({ ...base, status: 'VALID', verificationId: 'v-1' }, true)).toBe(false);
    expect(canAcknowledgeChain(null, true)).toBe(false);
  });
});
