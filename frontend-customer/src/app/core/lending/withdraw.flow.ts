import type { Address, Hash } from 'viem';
import type { WalletService } from '../wallet/wallet.service';
import { repoMarketAbi } from '../wallet/abi/repo-market.abi';
import type { LendingMarket } from '../models';

type WalletPort = Pick<WalletService, 'ensureChain' | 'readContract' | 'writeContract' | 'waitForTransaction'>;

export interface WithdrawParams {
  market: Pick<LendingMarket, 'marketAddress' | 'chainId' | 'chainName'>;
  owner: Address;
  /** Withdraw the whole claim as read on-chain just before the transaction. */
  all: boolean;
  amountUnits: bigint | null;
}

export interface WithdrawResult {
  hash: Hash;
  withdrawn: bigint;
  /** Claim still on the market afterwards (interest accrued while the transaction was mined); null if unknown. */
  remainingClaim: bigint | null;
}

/**
 * Supplier withdrawal (8A-01). `withdraw(amount)` reverts above the claim and has no "all", so "withdraw all"
 * reads `balanceOf(owner)` on-chain immediately before the transaction and withdraws exactly that; seconds of
 * accrual can remain and are reported instead of being claimed to be zero.
 */
export async function executeWithdraw(wallet: WalletPort, p: WithdrawParams): Promise<WithdrawResult> {
  await wallet.ensureChain(p.market.chainId, p.market.chainName);
  const address = p.market.marketAddress as Address;
  const readClaim = () => wallet.readContract<bigint>({
    address, abi: repoMarketAbi, functionName: 'balanceOf', args: [p.owner],
  });
  let withdrawn: bigint;
  if (p.all) {
    withdrawn = await readClaim();
    if (withdrawn <= 0n) throw new Error('There is nothing to withdraw in this market.');
  } else {
    if (p.amountUnits === null || p.amountUnits <= 0n) throw new Error('Enter a valid withdrawal amount.');
    withdrawn = p.amountUnits;
  }
  const hash = await wallet.writeContract({ address, abi: repoMarketAbi, functionName: 'withdraw', args: [withdrawn] });
  await wallet.waitForTransaction(hash);
  let remainingClaim: bigint | null = null;
  try { remainingClaim = await readClaim(); } catch { /* unknown */ }
  return { hash, withdrawn, remainingClaim };
}
