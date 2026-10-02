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
});
