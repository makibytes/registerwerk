import { describe, expect, it, vi } from 'vitest';
import type { Address } from 'viem';
import { executeRepay } from './repay.flow';

const MARKET = '0x00000000000000000000000000000000000000a1' as Address;
const LOAN = '0x00000000000000000000000000000000000000b2' as Address;
const ME = '0x00000000000000000000000000000000000000c3' as Address;
const market = { marketAddress: MARKET, loanTokenAddress: LOAN, baseRateWad: '0', slopeWad: '0', chainId: 1, chainName: 'Ethereum' };

function wallet(opts: { onchainDebt: bigint; afterDebt?: bigint; allowanceLeft?: bigint; chainOk?: boolean }) {
  const calls: string[] = [];
  let debtReads = 0;
  const w = {
    ensureChain: vi.fn(async () => { calls.push('chain'); if (opts.chainOk === false) throw new Error('Switch your wallet'); }),
    readContract: vi.fn(async (p: { functionName: string }) => {
      calls.push('read:' + p.functionName);
      if (p.functionName === 'debtOf') return debtReads++ === 0 ? opts.onchainDebt : (opts.afterDebt ?? 0n);
      if (p.functionName === 'allowance') return opts.allowanceLeft ?? 0n;
      throw new Error('unexpected');
    }),
    ensureAllowance: vi.fn(async () => { calls.push('allowance'); return []; }),
    writeContract: vi.fn(async (p: { functionName: string }) => { calls.push('write:' + p.functionName); return '0xabc'; }),
    waitForTransaction: vi.fn(async () => ({})),
  };
  return { w: w as never, calls, raw: w };
}

describe('executeRepay', () => {
  it('full repay requests more than the snapshot AND the fresh debt, allowance equals the request', async () => {
    const snapshot = 123456789012345678901n; // 18 decimals, beyond Number precision
    const { w, raw } = wallet({ onchainDebt: snapshot + 7n });
    const res = await executeRepay(w, { market, borrower: ME, snapshotDebt: snapshot, full: true, amountUnits: null, debtOnly: false });
    expect(res.requested > snapshot + 7n).toBe(true);
    expect(raw.ensureAllowance).toHaveBeenCalledWith(LOAN, MARKET, res.requested);
    expect(raw.writeContract.mock.calls[0][0]).toMatchObject({ functionName: 'repay', args: [res.requested] });
    expect(res.residualDebt).toBe(0n);
  });

  it('reports residual debt when the position was not fully closed and clears leftover allowance', async () => {
    const { w, raw } = wallet({ onchainDebt: 1_000n, afterDebt: 3n, allowanceLeft: 40n });
    const res = await executeRepay(w, { market, borrower: ME, snapshotDebt: 1_000n, full: true, amountUnits: null, debtOnly: false });
    expect(res.residualDebt).toBe(3n);
    const last = raw.writeContract.mock.calls.at(-1)![0];
    expect(last).toMatchObject({ functionName: 'approve', args: [MARKET, 0n] });
  });

  it('partial repay sends exactly the entered amount and checks nothing afterwards', async () => {
    const { w, raw } = wallet({ onchainDebt: 1_000n });
    const res = await executeRepay(w, { market, borrower: ME, snapshotDebt: 1_000n, full: false, amountUnits: 250n, debtOnly: true });
    expect(res.requested).toBe(250n);
    expect(raw.writeContract.mock.calls[0][0]).toMatchObject({ functionName: 'repayDebtOnly', args: [250n] });
    expect(res.residualDebt).toBeNull();
  });

  it('wrong chain: throws before any read, approve or write', async () => {
    const { w, raw } = wallet({ onchainDebt: 1n, chainOk: false });
    await expect(executeRepay(w, { market, borrower: ME, snapshotDebt: 1n, full: true, amountUnits: null, debtOnly: false })).rejects.toThrow('Switch your wallet');
    expect(raw.readContract).not.toHaveBeenCalled();
    expect(raw.ensureAllowance).not.toHaveBeenCalled();
    expect(raw.writeContract).not.toHaveBeenCalled();
  });
});
