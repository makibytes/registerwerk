import { describe, expect, it } from 'vitest';
import { buildApprovalRequestBlock, describeApprovalRequest, parseApprovalRequestBlock } from './approval-request';

describe('approval request block', () => {
  const request = { action: 'ISSUER_MINT', target: 'POST /api/v1/assets/a1/deployments/d1/issuer/mint', targetBody: { toAddress: '0x1', amount: '1000' } };

  it('round-trips the exact target and body', () => {
    const parsed = parseApprovalRequestBlock(buildApprovalRequestBlock(request));
    expect(parsed.request).toEqual(request);
  });

  it('carries no credential material, only action / target / body', () => {
    expect(Object.keys(JSON.parse(buildApprovalRequestBlock(request))).sort()).toEqual(['action', 'target', 'targetBody']);
    expect(Object.keys(JSON.parse(buildApprovalRequestBlock({ action: 'A', target: 'POST /api/v1/x' })))).toEqual(['action', 'target']);
  });

  it('rejects malformed blocks and non-API targets', () => {
    expect(parseApprovalRequestBlock('not json').error).toBeTruthy();
    expect(parseApprovalRequestBlock('{"action":"","target":"POST /api/v1/x"}').error).toBeTruthy();
    expect(parseApprovalRequestBlock('{"action":"A","target":"POST https://evil.example/x"}').error).toBeTruthy();
    expect(parseApprovalRequestBlock('{"action":"A","target":"/api/v1/x"}').error).toBeTruthy();
  });

  it('describes method, path and body for the approver', () => {
    const lines = describeApprovalRequest(request);
    expect(lines.map(l => l.label)).toEqual(['Action', 'Method', 'Path', 'Body']);
    expect(lines[1].value).toBe('POST');
    expect(lines[2].value).toBe('/api/v1/assets/a1/deployments/d1/issuer/mint');
  });

  // C2: what the approver reviews is what the backend binds (DualControlTarget.canonicalJson).
  describe('canonical request (C2)', () => {
    it('shows the body exactly as it is bound: keys sorted, no whitespace, plain decimals', () => {
      const lines = describeApprovalRequest({
        action: 'KYC_APPROVE', target: 'POST /api/v1/entities/e1/kyc/approve',
        targetBody: { overrideNote: 'ok', expiresAt: '2030-01-01', n: 1.50 },
      });
      expect(lines.find(l => l.label === 'Body')?.value).toBe('{"expiresAt":"2030-01-01","n":1.5,"overrideNote":"ok"}');
    });

    it('refuses a pasted block with a repeated key: two parsers would disagree on the value', () => {
      const dupBody = parseApprovalRequestBlock('{"action":"A","target":"POST /api/v1/x","targetBody":{"amount":"1","amount":"999"}}');
      expect(dupBody.error).toMatch(/repeated|duplicate/i);
      const dupTop = parseApprovalRequestBlock('{"action":"A","action":"B","target":"POST /api/v1/x"}');
      expect(dupTop.error).toMatch(/repeated|duplicate/i);
    });

    it('refuses a number the browser cannot represent exactly - amounts travel as strings', () => {
      const lossy = parseApprovalRequestBlock('{"action":"A","target":"POST /api/v1/x","targetBody":{"amount":12345678901234567890}}');
      expect(lossy.error).toMatch(/exact|string/i);
      const fine = parseApprovalRequestBlock('{"action":"A","target":"POST /api/v1/x","targetBody":{"amount":"12345678901234567890","n":100.50}}');
      expect(fine.request?.targetBody).toEqual({ amount: '12345678901234567890', n: 100.5 });
    });

    it('refuses a target whose query repeats a parameter (the backend cannot bind it)', () => {
      expect(parseApprovalRequestBlock('{"action":"A","target":"POST /api/v1/x?a=1&a=2"}').error).toMatch(/repeated|twice|duplicate/i);
      expect(parseApprovalRequestBlock('{"action":"A","target":"POST /api/v1/x?a=1&b=2"}').request).toBeTruthy();
    });

    it('the copied block carries the body in canonical form, so the approver pastes what the backend binds', () => {
      const block = buildApprovalRequestBlock({ action: 'A', target: 'POST /api/v1/x', targetBody: { b: 1, a: 'x' } });
      expect(block).toContain('"targetBody": {"a":"x","b":1}');
      expect(parseApprovalRequestBlock(block).request?.targetBody).toEqual({ a: 'x', b: 1 });
    });
  });
});
