import type { Address, Hash } from 'viem';
import type { WalletService } from '../wallet/wallet.service';
import { erc20Abi, repoMarketAbi } from '../wallet/abi/repo-market.abi';
import type { LendingMarket } from '../models';
import { fullRepayRequest } from './token-amount.util';

type WalletPort = Pick<WalletService, 'ensureChain' | 'readContract' | 'ensureAllowance' | 'writeContract' | 'waitForTransaction'>;

export interface RepayParams {
  market: Pick<LendingMarket, 'marketAddress' | 'loanTokenAddress' | 'baseRateWad' | 'slopeWad' | 'chainId' | 'chainName'>;
  borrower: Address;
  /** Debt (base units) shown to the user; the on-chain value is re-read just before a full repay. */
  snapshotDebt: bigint;
  full: boolean;
  /** Exact amount for a partial repay (ignored when `full`). */
  amountUnits: bigint | null;
  debtOnly: boolean;
}

export interface RepayResult {
  hash: Hash;
  requested: bigint;
  /** On-chain debt after a full repay; > 0 means the position was NOT closed. Null when not checked. */
  residualDebt: bigint | null;
}

/**
 * Repay executor (8A-01). A full repay requests the freshly read on-chain debt plus a safety margin so that
 * interest accrued before mining cannot push the call into the partial branch (residual debt + collateral
 * still pledged); the allowance is exactly that request and is reset to 0 afterwards (best effort).
 */
export async function executeRepay(wallet: WalletPort, p: RepayParams): Promise<RepayResult> {
  await wallet.ensureChain(p.market.chainId, p.market.chainName);
  const marketAddress = p.market.marketAddress as Address;
  const loanToken = p.market.loanTokenAddress as Address;

  let requested: bigint;
  if (p.full) {
    let debt = p.snapshotDebt;
    try {
      const fresh = await wallet.readContract<bigint>({
        address: marketAddress, abi: repoMarketAbi, functionName: 'debtOf', args: [p.borrower],
      });
      if (fresh > debt) debt = fresh;
    } catch {
      /* fall back to the snapshot; the margin still applies */
    }
    requested = fullRepayRequest(debt, p.market);
  } else {
    if (p.amountUnits === null || p.amountUnits <= 0n) throw new Error('Enter a valid repayment amount.');
    requested = p.amountUnits;
  }

  await wallet.ensureAllowance(loanToken, marketAddress, requested);
  const hash = await wallet.writeContract({
    address: marketAddress, abi: repoMarketAbi, functionName: p.debtOnly ? 'repayDebtOnly' : 'repay', args: [requested],
  });
  await wallet.waitForTransaction(hash);

  let residualDebt: bigint | null = null;
  if (p.full) {
    try {
      residualDebt = await wallet.readContract<bigint>({
        address: marketAddress, abi: repoMarketAbi, functionName: 'debtOf', args: [p.borrower],
      });
    } catch { /* unknown */ }
    // Only part of the request is pulled; clear what is left so no stale allowance lingers.
    try {
      const left = await wallet.readContract<bigint>({
        address: loanToken, abi: erc20Abi, functionName: 'allowance', args: [p.borrower, marketAddress],
      });
      if (left > 0n) {
        const reset = await wallet.writeContract({
          address: loanToken, abi: erc20Abi, functionName: 'approve', args: [marketAddress, 0n],
        });
        await wallet.waitForTransaction(reset);
      }
    } catch { /* wallet prompt rejected or unreadable: ignore */ }
  }
  return { hash, requested, residualDebt };
}
