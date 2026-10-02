import { describe, expect, it, vi } from 'vitest';
import type { Address } from 'viem';
import { executeWithdraw } from './withdraw.flow';

const MARKET = '0x00000000000000000000000000000000000000a1' as Address;
const ME = '0x00000000000000000000000000000000000000c3' as Address;
const market = { marketAddress: MARKET, chainId: 1, chainName: 'Ethereum' };

function wallet(claims: bigint[], chainOk = true) {
  const order: string[] = [];
  const raw = {
    ensureChain: vi.fn(async () => { order.push('chain'); if (!chainOk) throw new Error('Switch your wallet'); }),
    readContract: vi.fn(async () => { order.push('balanceOf'); return claims.shift()!; }),
    writeContract: vi.fn(async (p: { functionName: string }) => { order.push('write:' + p.functionName); return '0xabc'; }),
    waitForTransaction: vi.fn(async () => ({})),
  };
  return { w: raw as never, raw, order };
}

describe('executeWithdraw', () => {
  it('withdraw-all reads balanceOf on chain right before withdrawing exactly that value and reports the remainder', async () => {
    const { w, raw, order } = wallet([5_000_123n, 2n]);
    const res = await executeWithdraw(w, { market, owner: ME, all: true, amountUnits: null });
    expect(order).toEqual(['chain', 'balanceOf', 'write:withdraw', 'balanceOf']);
    expect(raw.writeContract.mock.calls[0][0]).toMatchObject({ functionName: 'withdraw', args: [5_000_123n] });
    expect(res.remainingClaim).toBe(2n);
  });

  it('partial withdrawal sends the exact amount', async () => {
    const { w, raw } = wallet([10n]);
    const res = await executeWithdraw(w, { market, owner: ME, all: false, amountUnits: 7n });
    expect(raw.writeContract.mock.calls[0][0]).toMatchObject({ args: [7n] });
    expect(res.withdrawn).toBe(7n);
  });

  it('refuses on the wrong chain before touching the wallet', async () => {
    const { w, raw } = wallet([1n], false);
    await expect(executeWithdraw(w, { market, owner: ME, all: true, amountUnits: null })).rejects.toThrow('Switch your wallet');
    expect(raw.writeContract).not.toHaveBeenCalled();
  });

  it('nothing to withdraw is an explicit error', async () => {
    const { w } = wallet([0n]);
    await expect(executeWithdraw(w, { market, owner: ME, all: true, amountUnits: null })).rejects.toThrow('nothing to withdraw');
  });
});
